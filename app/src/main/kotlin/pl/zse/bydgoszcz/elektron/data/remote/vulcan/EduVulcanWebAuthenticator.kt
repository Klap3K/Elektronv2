package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Pojedynczy kafelek profilu ucznia/dziennika wykryty na stronie `/dostep-do-dziennika/`.
 */
data class EduVulcanProfileTile(
    val title: String,
    val subtitle: String,
    val url: String
)

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
            require(EduVulcanWebSecurity.isTrustedUrl("https://uczen.$domain")) { "Nieprawidłowa domena eduVULCAN" }
            val cm = CookieManager.getInstance()
            runCatching { cm.flush() }

            val urls = listOf(
                "https://uczen.$domain/$symbol/api/Context",
                "https://uczen.$domain/$symbol/App",
                "https://uczen.$domain/$symbol",
                "https://uczen.$domain",
                "https://wiadomosci.$domain/$symbol",
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
         * Parsuje listę profili przekazaną z JS (`onProfilesFound`).
         * Obsługuje zarówno format tablicy JSON `[{"title":..., "subtitle":..., "url":...}]`,
         * jak i format tekstowy `tytul||url`.
         */
        fun parseProfileTiles(rawPayload: String): List<EduVulcanProfileTile> {
            val trimmed = rawPayload.trim()
            if (trimmed.isEmpty()) return emptyList()

            if (trimmed.startsWith("[")) {
                return runCatching {
                    val arr = JSONArray(trimmed)
                    val result = mutableListOf<EduVulcanProfileTile>()
                    val seenUrls = HashSet<String>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val url = obj.optString("url").trim()
                        if (!EduVulcanWebSecurity.isTrustedUrl(url) || !seenUrls.add(url)) continue
                        val rawTitle = obj.optString("title").trim()
                            .replace(Regex("\\s+"), " ")
                            .ifBlank { "Profil ucznia #${result.size + 1}" }
                        val rawSubtitle = obj.optString("subtitle").trim()
                            .replace(Regex("\\s+"), " ")
                        result.add(
                            EduVulcanProfileTile(
                                title = rawTitle,
                                subtitle = rawSubtitle,
                                url = url
                            )
                        )
                    }
                    result
                }.getOrDefault(emptyList())
            }

            return trimmed.lines()
                .mapNotNull { line ->
                    val parts = line.split("||", limit = 2)
                    if (parts.size == 2 && EduVulcanWebSecurity.isTrustedUrl(parts[1].trim())) {
                        val label = parts[0].trim().ifBlank { "Profil ucznia" }
                        EduVulcanProfileTile(
                            title = label,
                            subtitle = "",
                            url = parts[1].trim()
                        )
                    } else null
                }
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
                require(EduVulcanWebSecurity.isTrustedUrl("https://uczen.$domain") &&
                    Regex("[A-Za-z0-9_-]+").matches(symbol)) { "Nieprawidłowy adres dziennika eduVULCAN" }
                val root = JSONObject(contextJson)
                val arr = root.optJSONArray("uczniowie")
                    ?: root.optJSONArray("Uczniowie")
                    ?: throw IOException("Brak listy uczniów w odpowiedzi /api/Context")
                if (arr.length() == 0) {
                    throw IOException("Na tym koncie eduVULCAN nie znaleziono przypisanego ucznia.")
                }

                // Preferujemy ucznia szkoły (isPrzedszkolak == false), w ostateczności pierwszy wpis
                var chosen: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val keyCandidate = item.optString("key").ifBlank { item.optString("Key") }
                    if (!item.optBoolean("isPrzedszkolak", false) && keyCandidate.isNotBlank()) {
                        chosen = item
                        break
                    }
                }
                if (chosen == null) {
                    chosen = arr.optJSONObject(0)
                }
                val u = chosen ?: throw IOException("Nie udało się odczytać danych ucznia z eduVULCAN.")

                val key = u.optString("key").ifBlank { u.optString("Key") }.trim()
                if (key.isBlank()) {
                    throw IOException("Brak klucza ucznia (key) w /api/Context.")
                }

                val pupilName = u.optString("uczen").ifBlank { u.optString("Uczen") }.trim().ifBlank { "Uczeń" }
                val idDziennik = u.optInt("idDziennik", u.optString("idDziennik").toIntOrNull() ?: 0)
                val schoolName = u.optString("jednostka").ifBlank { u.optString("Jednostka") }.trim().ifBlank { symbol }
                val className = u.optString("oddzial").ifBlank { u.optString("Oddzial") }.trim()

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
         * Buduje skrypt JS wykonujący krok po kroku:
         * - odblokowanie przewijania strony (CSS overflow: auto !important) i przekierowanie window.open w tym samym oknie,
         * - zamknięcie banera cookies,
         * - logowanie na eduvulcan.pl,
         * - wykrycie wyłącznie właściwych kafelków profili uczniów na /dostep-do-dziennika/ (z pominięciem
         *   przycisków "Kup usługę rozszerzoną" i "Dodaj dostęp"),
         * - automatyczne lub ręczne przejście do dziennika ucznia bez cofania przekierowań SSO,
         * - pobranie /api/Context z uczen.{domain}/{city}/api/Context.
         */
        fun buildAutomationJs(
            username: String,
            password: String,
            autoSelectSingleProfile: Boolean = true
        ): String {
            val safeUser = JSONObject.quote(username)
            val safePass = JSONObject.quote(password)
            val autoSelectFlag = if (autoSelectSingleProfile) "true" else "false"

            return """
                (function() {
                    try {
                        var uName = $safeUser;
                        var uPass = $safePass;
                        var autoSelect = $autoSelectFlag;
                        var host = (window.location.hostname || '').toLowerCase();
                        var path = window.location.pathname || '';
                        var href = window.location.href || '';

                        // 0. Odblokowanie przewijania oraz przechwycenie window.open / target="_blank" w tym samym WebView
                        try {
                            window.open = function(url) {
                                if (url && typeof url === 'string') {
                                    window.location.href = url;
                                }
                                return window;
                            };
                            if (document.documentElement) {
                                document.documentElement.style.setProperty('overflow-y', 'auto', 'important');
                                document.documentElement.style.setProperty('height', 'auto', 'important');
                                document.documentElement.style.setProperty('max-height', 'none', 'important');
                            }
                            if (document.body) {
                                document.body.style.setProperty('overflow-y', 'auto', 'important');
                                document.body.style.setProperty('height', 'auto', 'important');
                                document.body.style.setProperty('max-height', 'none', 'important');
                                document.body.style.setProperty('position', 'static', 'important');
                            }
                        } catch (e) {}

                        // Funkcja pomocnicza wywoływana przy kliknięciu profilu z natywnego UI w dialogu Androida
                        window.__evOpenProfileUrl = function(targetUrl) {
                            try {
                                window.__evFollowingTile = true;
                                sessionStorage.setItem('__evOpeningProfile', '1');
                                sessionStorage.setItem('__evLastTriedProfileUrl', targetUrl);
                            } catch (e) {}
                            try {
                                var allLinks = document.querySelectorAll('a[href]');
                                for (var i = 0; i < allLinks.length; i++) {
                                    var a = allLinks[i];
                                    var h = a.href || a.getAttribute('href') || '';
                                    if (h === targetUrl) {
                                        a.removeAttribute('target');
                                        a.click();
                                        setTimeout(function() {
                                            if (window.location.href === href) {
                                                window.location.assign(targetUrl);
                                            }
                                        }, 250);
                                        return;
                                    }
                                }
                            } catch (e) {}
                            window.location.assign(targetUrl);
                        };

                        // 1. Akceptacja banera cookies (w głównym oknie lub iframe)
                        try {
                            var cookieBtn = document.getElementById('save-default-button') ||
                                            document.querySelector('button[id*="accept"], button[class*="accept-all"], .respect-privacy-Confirm');
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

                        // 2. Obsługa nieaktywnego ucznia (/End/NieaktywnyUczen) – powrót do wyboru profilu
                        if (href.indexOf('/End/NieaktywnyUczen') !== -1 || path.toLowerCase().indexOf('nieaktywnyuczen') !== -1) {
                            try {
                                sessionStorage.removeItem('__evOpeningProfile');
                                var lastTried = sessionStorage.getItem('__evLastTriedProfileUrl');
                                if (lastTried) {
                                    var skippedRaw = sessionStorage.getItem('__evSkippedUrls') || '[]';
                                    var skipped = JSON.parse(skippedRaw);
                                    if (skipped.indexOf(lastTried) === -1) {
                                        skipped.push(lastTried);
                                        sessionStorage.setItem('__evSkippedUrls', JSON.stringify(skipped));
                                    }
                                }
                            } catch (e) {}
                            AndroidEduVulcan.onStatus('Pominięto nieaktywny profil – wybierz aktywny profil ucznia...');
                            window.location.href = '$DIARY_ACCESS_URL';
                            return;
                        }

                        // 3. Jeśli jesteśmy już na uczen.{domain}/{city}...
                        if (host.indexOf('uczen.') === 0) {
                            var domain = host.substring('uczen.'.length);
                            var segs = path.split('/').filter(function(s) { return s.length > 0; });
                            var firstSeg = segs.length > 0 ? segs[0] : '';
                            var lowerSeg = firstSeg.toLowerCase();

                            // Ignorujemy ścieżki techniczne OIDC/logowania zanim nastąpi przekierowanie do /{city}/
                            var reservedSegs = ['end', 'signin-oidc', 'signout-oidc', 'account', 'login', 'logowanie', 'api', 'error'];
                            if (!firstSeg || reservedSegs.indexOf(lowerSeg) !== -1 || lowerSeg.indexOf('.aspx') !== -1) {
                                AndroidEduVulcan.onStatus('Autoryzacja sesji dziennika (' + host + ')...');
                                return;
                            }

                            var city = firstSeg;

                            // 3a. Jeśli przeglądarka otworzyła bezpośrednio /{city}/api/Context, odczytaj JSON z body
                            if (path.toLowerCase().indexOf('/api/context') !== -1) {
                                var rawBody = (document.body && (document.body.innerText || document.body.textContent)) || '';
                                if ((rawBody.indexOf('uczniowie') !== -1 || rawBody.indexOf('Uczniowie') !== -1) && !window.__evContextReported) {
                                    window.__evContextReported = true;
                                    AndroidEduVulcan.onContextSuccess(domain, city, rawBody.trim());
                                }
                                return;
                            }

                            // 3b. Jeśli trwa jeszcze przekierowanie LoginEndpoint.aspx w obrębie /{city}/, poczekaj na docelową stronę
                            if (path.toLowerCase().indexOf('loginendpoint') !== -1 || path.toLowerCase().indexOf('signin') !== -1) {
                                AndroidEduVulcan.onStatus('Finalizowanie logowania w dzienniku (' + city + ')...');
                                return;
                            }

                            if (!window.__evContextFetching && !window.__evContextReported) {
                                window.__evContextFetching = true;
                                window.__evContextAttempts = (window.__evContextAttempts || 0) + 1;
                                AndroidEduVulcan.onStatus('Pobieranie danych ucznia z dziennika (' + city + ')...');
                                fetch('https://uczen.' + domain + '/' + city + '/api/Context', {
                                    method: 'GET',
                                    credentials: 'include',
                                    headers: {
                                        'Accept': 'application/json',
                                        'X-Requested-With': 'XMLHttpRequest'
                                    }
                                })
                                .then(function(r) { return r.text(); })
                                .then(function(txt) {
                                    if (txt && (txt.indexOf('uczniowie') !== -1 || txt.indexOf('Uczniowie') !== -1)) {
                                        if (!window.__evContextReported) {
                                            window.__evContextReported = true;
                                            AndroidEduVulcan.onContextSuccess(domain, city, txt);
                                        }
                                    } else {
                                        window.__evContextFetching = false;
                                        // Fallback jak w vultron.py: bezpośrednie otwarcie /api/Context w oknie przeglądarki
                                        if (window.__evContextAttempts >= 2 && !window.__evNavigatedContext) {
                                            window.__evNavigatedContext = true;
                                            window.location.assign('https://uczen.' + domain + '/' + city + '/api/Context');
                                        }
                                    }
                                })
                                .catch(function(err) {
                                    window.__evContextFetching = false;
                                    if (window.__evContextAttempts >= 2 && !window.__evNavigatedContext) {
                                        window.__evNavigatedContext = true;
                                        window.location.assign('https://uczen.' + domain + '/' + city + '/api/Context');
                                    }
                                });
                            }
                            return;
                        }

                        // 4. Strona wyboru dziennika (/dostep-do-dziennika)
                        // Używamy wyłącznie oficjalnych selektorów profili ucznia (kolejno: nowy WordPress, stary ASP.NET, historyczny),
                        // zatrzymując się na pierwszym pasującym wariancie szablonu, aby NIE łapać linków "Kup usługę rozszerzoną" ani "Dodaj dostęp".
                        var selectorGroups = [
                            'a.panel-access__profile',
                            'a.connected-account.access-row',
                            'a[title="Przejdź do Dziennika"]',
                            'a[href*="uczen."]'
                        ];
                        var foundProfiles = [];
                        var seenHrefs = {};
                        var skippedList = [];
                        try {
                            skippedList = JSON.parse(sessionStorage.getItem('__evSkippedUrls') || '[]');
                        } catch (e) {}

                        function isNonProfileLink(text, url) {
                            var t = (text || '').toLowerCase();
                            var u = (url || '').toLowerCase();
                            if (t.indexOf('kup usług') !== -1 || t.indexOf('kup uslug') !== -1) return true;
                            if (t.indexOf('dodaj dostęp') !== -1 || t.indexOf('dodaj dostep') !== -1) return true;
                            if (t.indexOf('wyloguj') !== -1 || t.indexOf('zmień hasło') !== -1) return true;
                            if (u.indexOf('/End/NieaktywnyUczen') !== -1) return true;
                            if (u.indexOf('/logowanie') !== -1) return true;
                            if (u.indexOf('eduvulcan.pl/konto') !== -1) return true;
                            if (u.indexOf('dodaj-dostep') !== -1 || u.indexOf('sklep') !== -1 || u.indexOf('zakup') !== -1) return true;
                            return false;
                        }

                        for (var s = 0; s < selectorGroups.length; s++) {
                            var links = document.querySelectorAll(selectorGroups[s]);
                            for (var l = 0; l < links.length; l++) {
                                var aEl = links[l];
                                aEl.removeAttribute('target');
                                var rawAttrHref = aEl.getAttribute('href') || '';
                                var linkHref = aEl.href || rawAttrHref || '';
                                if (!rawAttrHref || rawAttrHref === '#' || rawAttrHref.indexOf('javascript:') === 0) {
                                    continue;
                                }
                                var rawText = (aEl.innerText || aEl.textContent || '').trim();
                                if (linkHref.indexOf('https://') === 0 &&
                                    !isNonProfileLink(rawText, linkHref) &&
                                    !seenHrefs[linkHref]) {
                                    seenHrefs[linkHref] = true;
                                    var lines = rawText
                                        .split('\n')
                                        .map(function(x) { return x.trim(); })
                                        .filter(function(x) { return x.length > 0; });
                                    // Pomiń pojedynczą literę inicjału awatara (np. "I" przed "Igor Szwarc (ZSE)")
                                    if (lines.length > 1 && lines[0].length <= 2) {
                                        lines = lines.slice(1);
                                    }
                                    var pTitle = lines.length > 0 ? lines[0] : (aEl.getAttribute('title') || 'Dziennik ucznia');
                                    var pSub = lines.length > 1 ? lines.slice(1).join(' • ') : '';
                                    if (!aEl.__evBoundClick) {
                                        aEl.__evBoundClick = true;
                                        (function(targetHref, targetTitle) {
                                            aEl.addEventListener('click', function() {
                                                try {
                                                    window.__evFollowingTile = true;
                                                    sessionStorage.setItem('__evOpeningProfile', '1');
                                                    sessionStorage.setItem('__evLastTriedProfileUrl', targetHref);
                                                    AndroidEduVulcan.onStatus('Otwieranie dziennika: ' + targetTitle + '...');
                                                } catch (e) {}
                                                setTimeout(function() {
                                                    if (window.location.href === href) {
                                                        window.location.assign(targetHref);
                                                    }
                                                }, 300);
                                            });
                                        })(linkHref, pTitle);
                                    }
                                    foundProfiles.push({
                                        title: pTitle,
                                        subtitle: pSub,
                                        url: linkHref,
                                        skipped: skippedList.indexOf(linkHref) !== -1,
                                        element: aEl
                                    });
                                }
                            }
                            if (foundProfiles.length > 0) {
                                break;
                            }
                        }

                        if (foundProfiles.length > 0) {
                            try {
                                sessionStorage.setItem('__evVisitedAccessPage', '1');
                            } catch (e) {}

                            if (!window.__evScrolledToProfiles) {
                                window.__evScrolledToProfiles = true;
                                try {
                                    foundProfiles[0].element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                } catch (e) {}
                            }

                            var payload = JSON.stringify(foundProfiles.map(function(p) {
                                return { title: p.title, subtitle: p.subtitle, url: p.url };
                            }));
                            if (window.__evLastProfilesPayload !== payload && typeof AndroidEduVulcan.onProfilesFound === 'function') {
                                window.__evLastProfilesPayload = payload;
                                AndroidEduVulcan.onProfilesFound(payload);
                            }

                            var activeProfiles = foundProfiles.filter(function(p) { return !p.skipped; });

                            // Jeśli jest dokładnie 1 aktywny profil ucznia, otwórz go automatycznie!
                            if (activeProfiles.length === 1 && autoSelect) {
                                if (!window.__evFollowingTile) {
                                    window.__evFollowingTile = true;
                                    var chosen = activeProfiles[0];
                                    try {
                                        sessionStorage.setItem('__evOpeningProfile', '1');
                                        sessionStorage.setItem('__evLastTriedProfileUrl', chosen.url);
                                    } catch (e) {}
                                    AndroidEduVulcan.onStatus('Otwieranie dziennika: ' + chosen.title + '...');
                                    try {
                                        chosen.element.removeAttribute('target');
                                        chosen.element.click();
                                    } catch (e) {}
                                    setTimeout(function() {
                                        window.location.assign(chosen.url);
                                    }, 150);
                                }
                            } else if (!window.__evFollowingTile) {
                                AndroidEduVulcan.onStatus('Wybierz profil ucznia z listy poniżej:');
                            }
                            return;
                        }

                        // 5. Formularz logowania na eduvulcan.pl
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

                        // 6. Zapasowe przejście na /dostep-do-dziennika/ TYLKO gdy po zalogowaniu wylądowaliśmy na stronie głównej eduvulcan.pl
                        // i JESZCZE NIE byliśmy na /dostep-do-dziennika/ oraz NIE trwa otwieranie profilu (aby nie przerywać przekierowań SSO!).
                        var visitedAccess = false;
                        var openingProfile = false;
                        try {
                            visitedAccess = sessionStorage.getItem('__evVisitedAccessPage') === '1';
                            openingProfile = sessionStorage.getItem('__evOpeningProfile') === '1';
                        } catch (e) {}

                        if (!visitedAccess && !openingProfile &&
                            (host === 'eduvulcan.pl' || host === 'www.eduvulcan.pl') &&
                            (path === '/' || path === '' || path.toLowerCase() === '/konto') &&
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
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            javaScriptCanOpenWindowsAutomatically = true
                            userAgentString = USER_AGENT
                        }

                        val jsCode = buildAutomationJs(
                            username = username,
                            password = password,
                            autoSelectSingleProfile = true
                        )

                        EduVulcanWebSecurity.installBridge(wv,
                                        object : EduVulcanBridge {

                                override fun onStatus(msg: String) {
                                    // Ciche logowanie w tle
                                }


                                override fun onProfilesFound(profilesJson: String) {
                                    val tiles = parseProfileTiles(profilesJson)
                                    if (tiles.size > 1 && cont.isActive) {
                                        cont.resume(Result.failure(IOException("Wybierz profil ucznia w oknie logowania eduVULCAN.")))
                                    }
                                }


                                override fun onLoginError(err: String) {
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(IOException("Logowanie eduVULCAN odrzucone: $err")))
                                    }
                                }


                                override fun onContextSuccess(domain: String, city: String, contextJson: String) {
                                    if (cont.isActive) {
                                        val parsed = parseContextJson(domain, city, contextJson, username, password)
                                        cont.resume(parsed)
                                    }
                                }
                            }
                        )

                        wv.webChromeClient = WebChromeClient()
                        wv.webViewClient = object : EduVulcanWebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                EduVulcanWebSecurity.evaluate(view, jsCode)
                                view?.postDelayed({ EduVulcanWebSecurity.evaluate(view, jsCode) }, 1200L)
                                view?.postDelayed({ EduVulcanWebSecurity.evaluate(view, jsCode) }, 2500L)
                            }


                        }

                        wv.loadUrl(LOGIN_URL)

                    }
                }

                sessionResult ?: Result.failure(
                    IOException("Przekroczono czas oczekiwania na odpowiedź eduVULCAN (możliwa weryfikacja CAPTCHA – zaloguj się w Ustawieniach).")
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
