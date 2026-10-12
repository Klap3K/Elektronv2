package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Automatyzuje logowanie hasłem do konta eduVULCAN (eduvulcan.pl -> uczen.eduvulcan.pl)
 * oraz wyodrębnianie sesji i kontekstu ucznia (/api/Context).
 */
@Singleton
class EduVulcanWebAuthenticator @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    companion object {
        const val LOGIN_URL = "https://eduvulcan.pl/logowanie?ReturnUrl=%2Fdostep-do-dziennika%2F"
        const val DIARY_ACCESS_URL = "https://eduvulcan.pl/dostep-do-dziennika/"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * Zbiera wszystkie ciasteczka sesyjne z systemowego CookieManagera dla domeny eduVULCAN.
         */
        fun collectCookiesHeader(domain: String, symbol: String): String {
            val cm = CookieManager.getInstance()
            runCatching { cm.flush() }

            val urls = listOf(
                "https://uczen.$domain/$symbol",
                "https://uczen.$domain",
                "https://$domain"
            )
            val cookieMap = LinkedHashMap<String, String>()
            for (u in urls) {
                val raw = runCatching { cm.getCookie(u) }.getOrNull().orEmpty()
                if (raw.isNotBlank()) {
                    raw.split(";").forEach { part ->
                        val trimmed = part.trim()
                        val eqIdx = trimmed.indexOf('=')
                        if (eqIdx > 0) {
                            val k = trimmed.substring(0, eqIdx).trim()
                            val v = trimmed.substring(eqIdx + 1).trim()
                            if (k.isNotBlank() && !cookieMap.containsKey(k)) {
                                cookieMap[k] = v
                            }
                        }
                    }
                }
            }
            return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        /**
         * Parsuje odpowiedź JSON z `/api/Context` i tworzy obiekt [EduVulcanSession].
         */
        fun parseContextJson(
            domain: String,
            symbol: String,
            contextJson: String,
            loginUsername: String,
            loginPassword: String
        ): Result<EduVulcanSession> {
            return runCatching {
                val root = JSONObject(contextJson)
                val arr = root.optJSONArray("uczniowie")
                    ?: throw IOException("Brak listy uczniów w odpowiedzi /api/Context")
                if (arr.length() == 0) {
                    throw IOException("Na tym koncie eduVULCAN nie znaleziono przypisanego ucznia.")
                }

                // Preferujemy ucznia szkoły (isPrzedszkolak == false), w ostateczności pierwszy wpis
                var chosen: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (!item.optBoolean("isPrzedszkolak", false) && item.optString("key").isNotBlank()) {
                        chosen = item
                        break
                    }
                }
                if (chosen == null) {
                    chosen = arr.optJSONObject(0)
                }
                val u = chosen ?: throw IOException("Nie udało się odczytać danych ucznia z eduVULCAN.")

                val key = u.optString("key").trim()
                if (key.isBlank()) {
                    throw IOException("Brak klucza ucznia (key) w /api/Context.")
                }

                val pupilName = u.optString("uczen").trim().ifBlank { "Uczeń" }
                val idDziennik = u.optInt("idDziennik", 0)
                val schoolName = u.optString("jednostka").trim().ifBlank { symbol }
                val className = u.optString("oddzial").trim()

                val cookiesHeader = collectCookiesHeader(domain, symbol)

                EduVulcanSession(
                    domain = domain,
                    symbol = symbol,
                    studentKey = key,
                    idDziennik = idDziennik,
                    pupilName = pupilName,
                    schoolName = schoolName,
                    className = className,
                    cookiesHeader = cookiesHeader,
                    loginUsername = loginUsername,
                    loginPassword = loginPassword
                )
            }
        }

        /**
         * Buduje skrypt JS wykonujący krok po kroku logowanie na eduvulcan.pl,
         * wybór kafelka ucznia na /dostep-do-dziennika/ oraz pobranie /api/Context z uczen.eduvulcan.pl.
         */
        fun buildAutomationJs(username: String, password: String): String {
            val safeUser = JSONObject.quote(username)
            val safePass = JSONObject.quote(password)

            return """
                (function() {
                    try {
                        var uName = $safeUser;
                        var uPass = $safePass;
                        var host = (window.location.hostname || '').toLowerCase();
                        var path = window.location.pathname || '';
                        var href = window.location.href || '';

                        // 1. Akceptacja banera cookies (w głównym oknie lub iframe)
                        try {
                            var cookieBtn = document.getElementById('save-default-button') ||
                                            document.querySelector('button[id*="accept"], button[class*="accept-all"]');
                            if (cookieBtn && cookieBtn.offsetParent !== null) {
                                cookieBtn.click();
                            }
                            var iframes = document.getElementsByTagName('iframe');
                            for (var i = 0; i < iframes.length; i++) {
                                try {
                                    var idoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                                    if (idoc) {
                                        var ibtn = idoc.getElementById('save-default-button');
                                        if (ibtn) ibtn.click();
                                    }
                                } catch (e) {}
                            }
                        } catch (e) {}

                        function setNativeValue(el, val) {
                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                            if (setter) {
                                setter.call(el, val);
                            } else {
                                el.value = val;
                            }
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                        }

                        // 2. Jeśli jesteśmy już na uczen.{domain}/{city}...
                        if (host.indexOf('uczen.') === 0) {
                            var domain = host.substring('uczen.'.length);
                            var segs = path.split('/').filter(function(s) { return s.length > 0; });
                            var city = segs.length > 0 ? segs[0] : '';
                            if (city && city.toLowerCase() !== 'end') {
                                if (!window.__evContextFetching) {
                                    window.__evContextFetching = true;
                                    AndroidEduVulcan.onStatus('Pobieranie danych ucznia z dziennika (' + city + ')...');
                                    fetch('/' + city + '/api/Context', {
                                        method: 'GET',
                                        credentials: 'include',
                                        headers: { 'Accept': 'application/json' }
                                    })
                                    .then(function(r) { return r.text(); })
                                    .then(function(txt) {
                                        if (txt && txt.indexOf('uczniowie') !== -1) {
                                            AndroidEduVulcan.onContextSuccess(domain, city, txt);
                                        } else {
                                            window.__evContextFetching = false;
                                        }
                                    })
                                    .catch(function(err) {
                                        window.__evContextFetching = false;
                                    });
                                }
                            }
                            return;
                        }

                        // 3. Strona wyboru dziennika (/dostep-do-dziennika lub po zalogowaniu)
                        var selectors = [
                            'a.panel-access__profile',
                            'a.connected-account.access-row',
                            'a[title="Przejdź do Dziennika"]',
                            'a[href*="uczen."]'
                        ];
                        for (var s = 0; s < selectors.length; s++) {
                            var links = document.querySelectorAll(selectors[s]);
                            for (var l = 0; l < links.length; l++) {
                                var linkHref = links[l].href || links[l].getAttribute('href') || '';
                                if (linkHref.indexOf('https://') === 0 && linkHref.indexOf('/End/NieaktywnyUczen') === -1) {
                                    if (!window.__evFollowingTile) {
                                        window.__evFollowingTile = true;
                                        AndroidEduVulcan.onStatus('Otwieranie dziennika ucznia...');
                                        window.location.href = linkHref;
                                    }
                                    return;
                                }
                            }
                        }

                        // 4. Formularz logowania na eduvulcan.pl
                        var passField = document.getElementById('Password') || document.querySelector('input[name="Password"]');
                        var userField = document.getElementById('UserName') || document.querySelector('input[name="UserName"]');

                        // Sprawdzenie komunikatu o błędzie logowania
                        var errSummary = document.querySelector('.validation-summary-errors, .field-validation-error, .error-message, .alert-danger');
                        if (errSummary && errSummary.offsetParent !== null && errSummary.innerText.trim().length > 0) {
                            var errTxt = errSummary.innerText.trim();
                            if (window.__evPassSent || window.__evUserSent) {
                                AndroidEduVulcan.onLoginError(errTxt);
                            }
                        }

                        // Krok B: Pole hasła widoczne
                        if (passField && passField.offsetParent !== null && uPass.length > 0) {
                            if (!window.__evPassSent) {
                                window.__evPassSent = true;
                                AndroidEduVulcan.onStatus('Wprowadzanie hasła...');
                                setNativeValue(passField, uPass);
                                setTimeout(function() {
                                    var submitBtn = document.getElementById('btLogOn') ||
                                                    document.querySelector('button[type="submit"], input[type="submit"]');
                                    if (submitBtn) {
                                        submitBtn.click();
                                    } else if (passField.form) {
                                        passField.form.submit();
                                    }
                                }, 300);
                            }
                            return;
                        }

                        // Krok A: Pole loginu widoczne
                        if (userField && userField.offsetParent !== null && uName.length > 0) {
                            if (!window.__evUserSent) {
                                window.__evUserSent = true;
                                AndroidEduVulcan.onStatus('Wprowadzanie loginu...');
                                setNativeValue(userField, uName);
                                setTimeout(function() {
                                    var nextBtn = document.getElementById('btNext') ||
                                                  document.getElementById('btLogOn') ||
                                                  document.querySelector('button[type="submit"], input[type="submit"]');
                                    if (nextBtn) {
                                        nextBtn.click();
                                    } else if (userField.form) {
                                        userField.form.submit();
                                    }
                                }, 300);
                            }
                            return;
                        }

                        // Jeśli jesteśmy zalogowani na eduvulcan.pl, ale nie na /dostep-do-dziennika i nie na /logowanie
                        if (host.indexOf('eduvulcan.pl') !== -1 &&
                            path.toLowerCase().indexOf('/logowanie') === -1 &&
                            path.toLowerCase().indexOf('/dostep-do-dziennika') === -1 &&
                            !userField && !passField) {
                            if (!window.__evRedirectedAccess) {
                                window.__evRedirectedAccess = true;
                                window.location.href = '$DIARY_ACCESS_URL';
                            }
                        }
                    } catch (e) {}
                })();
            """.trimIndent()
        }
    }

    /**
     * Ciche odświeżenie sesji eduVULCAN w tle przy użyciu zapisanego loginu i hasła.
     * Wykorzystuje bezekranowy (headless) WebView na wątku głównym.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun refreshSessionSilently(
        username: String,
        password: String,
        timeoutMs: Long = 35_000L
    ): Result<EduVulcanSession> {
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(IOException("Brak zapisanego loginu lub hasła do odświeżenia sesji eduVULCAN."))
        }

        return withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                val sessionResult = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<Result<EduVulcanSession>> { cont ->
                        val wv = WebView(appContext)
                        webView = wv

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = USER_AGENT
                        }

                        val jsCode = buildAutomationJs(username, password)

                        wv.addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onStatus(msg: String) {
                                    // Ciche logowanie w tle
                                }

                                @JavascriptInterface
                                fun onLoginError(err: String) {
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(IOException("Logowanie eduVULCAN odrzucone: $err")))
                                    }
                                }

                                @JavascriptInterface
                                fun onContextSuccess(domain: String, city: String, contextJson: String) {
                                    if (cont.isActive) {
                                        val parsed = parseContextJson(domain, city, contextJson, username, password)
                                        cont.resume(parsed)
                                    }
                                }
                            },
                            "AndroidEduVulcan"
                        )

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(jsCode, null)
                                // Ponowne wywołanie po 1.2s i 2.5s (na animację przejścia UserName -> Password)
                                view?.postDelayed({ view.evaluateJavascript(jsCode, null) }, 1200L)
                                view?.postDelayed({ view.evaluateJavascript(jsCode, null) }, 2500L)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }

                        wv.loadUrl(LOGIN_URL)

                        cont.invokeOnCancellation {
                            runCatching {
                                wv.stopLoading()
                                wv.destroy()
                            }
                        }
                    }
                }

                sessionResult ?: Result.failure(
                    IOException("Przekroczono czas oczekiwania na odpowiedź eduVULCAN (możliwa weryfikacja CAPTCHA – zaloguj się w Ustawieniach).")
                )
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                runCatching {
                    webView?.stopLoading()
                    webView?.destroy()
                }
            }
        }
    }
}
