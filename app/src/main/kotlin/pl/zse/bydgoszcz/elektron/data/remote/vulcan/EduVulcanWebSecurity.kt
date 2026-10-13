package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

interface EduVulcanBridge {
    fun onStatus(msg: String)
    fun onProfilesFound(profilesJson: String)
    fun onLoginError(err: String)
    fun onContextSuccess(domain: String, city: String, contextJson: String)
}

/** Origin-scoped messages also protect against untrusted iframes on a trusted page. */
object EduVulcanWebSecurity {
    private val domains = setOf("eduvulcan.pl", "vulcan.net.pl", "vulcan.edu.pl")
    private val origins = domains.flatMap { listOf("https://$it", "https://*.$it") }.toSet()

    fun isTrustedUrl(value: String?): Boolean {
        val url = value?.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            domains.any { url.host == it || url.host.endsWith(".$it") }
    }

    fun installBridge(view: WebView, bridge: EduVulcanBridge) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            bridge.onLoginError("Zaktualizuj Android System WebView, aby bezpiecznie zalogować się do eduVULCAN.")
            return
        }
        WebViewCompat.addWebMessageListener(view, "ElektronMessages", origins) { _, message, origin, mainFrame, _ ->
            if (mainFrame && isTrustedUrl(origin.toString())) {
                val payload = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
                if (payload != null) {
                    val args = payload.optJSONArray("args")
                    when (payload.optString("method")) {
                        "onStatus" -> bridge.onStatus(args?.optString(0).orEmpty())
                        "onProfilesFound" -> bridge.onProfilesFound(args?.optString(0).orEmpty())
                        "onLoginError" -> bridge.onLoginError(args?.optString(0).orEmpty())
                        "onContextSuccess" -> {
                            val domain = args?.optString(0).orEmpty()
                            if (isTrustedUrl("https://uczen.$domain")) {
                                bridge.onContextSuccess(domain, args?.optString(1).orEmpty(), args?.optString(2).orEmpty())
                            }
                        }
                    }
                }
            }
        }
    }

    fun evaluate(view: WebView?, script: String) {
        if (view != null && isTrustedUrl(view.url) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            view.evaluateJavascript("""
                (function() {
                if (window.location.protocol !== 'https:' ||
                    !/^(?:[a-z0-9-]+\.)*(?:eduvulcan\.pl|vulcan\.net\.pl|vulcan\.edu\.pl)$/i.test(window.location.hostname) ||
                    (window.location.port && window.location.port !== '443')) return;
                window.AndroidEduVulcan = {};
                ['onStatus','onProfilesFound','onLoginError','onContextSuccess'].forEach(function(method) {
                    window.AndroidEduVulcan[method] = function() {
                        ElektronMessages.postMessage(JSON.stringify({method:method,args:Array.prototype.slice.call(arguments)}));
                    };
                });
            """.trimIndent() + "\n" + script + "\n})();", null)
        }
    }
}

open class EduVulcanWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        !EduVulcanWebSecurity.isTrustedUrl(request?.url?.toString())

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
        !EduVulcanWebSecurity.isTrustedUrl(url)

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }
}
