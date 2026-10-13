package pl.zse.bydgoszcz.elektron.data.remote.http

import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Centralny klient HTTP dla wszystkich źródeł eLektron.
 * Cechy: User-Agent z faktyczną wersją appki, timeouty, retry z backoffem,
 * dyskowy cache HTTP (zapytania warunkowe ETag/Last-Modified -> 304 zamiast pełnej strony).
 */
object OkHttpClientProvider {

    private const val CACHE_SIZE_BYTES = 10L * 1024 * 1024

    fun create(debug: Boolean, versionName: String, cacheDir: File): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (debug) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val userAgent = "Elektron-Vulcan/$versionName (+https://github.com/Klap3K/Elektronv2)"
        return OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "http"), CACHE_SIZE_BYTES))
            .addInterceptor(HeaderInterceptor(userAgent))
            .addInterceptor(logging)
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // callTimeout obejmuje całe wywołanie łącznie z retry z RetryInterceptora.
            .callTimeout(45, TimeUnit.SECONDS)
            // Własny RetryInterceptor już ponawia przy IOException — wbudowane
            // retryOnConnectionFailure dublowało próby (do 6 połączeń zamiast 3).
            .retryOnConnectionFailure(false)
            // Nie podążamy za przekierowaniem z HTTPS na HTTP (obniżenie szyfrowania).
            .followSslRedirects(false)
            .build()
    }

    /**
     * Klient do pobierania dużych plików (APK aktualizacji, ok. 20 MB) - na bazie [base]
     * (wspólna pula połączeń), ale BEZ:
     *  - callTimeout: limit 45 s obejmuje też czytanie treści, więc na wolnym łączu pobieranie
     *    zawsze się przerywało; zostają limity połączenia i bezczynności odczytu,
     *  - interceptorów dla stron szkoły (Accept HTML, wymuszona rewalidacja, RetryInterceptor
     *    ponawiający całe pobieranie) i logowania,
     *  - cache HTTP (plik APK nie ma tam czego szukać).
     */
    fun createDownloadClient(base: OkHttpClient, versionName: String): OkHttpClient {
        val userAgent = "Elektron-Vulcan/$versionName (+https://github.com/Klap3K/Elektronv2)"
        return base.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .cache(null)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private class HeaderInterceptor(private val userAgent: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val req = original.newBuilder()
                .header("User-Agent", userAgent)
                // Domyślne Accept (strony szkoły) tylko gdy zapytanie nie ma własnego
                // (np. API GitHuba przy sprawdzaniu aktualizacji).
                .header("Accept", original.header("Accept")
                    ?: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pl,en;q=0.5")
                // Zawsze rewalidacja u serwera (If-None-Match / If-Modified-Since).
                // Bez tego OkHttp dla stron z Last-Modified bez Cache-Control liczy
                // heurystyczną "świeżość" i mógłby serwować zastępstwa z cache godzinami.
                // max-age=0 zachowuje walidatory; no-cache w OkHttp omija tę ścieżkę.
                .cacheControl(CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build())
                .build()
            return chain.proceed(req)
        }
    }

    private class RetryInterceptor(private val maxRetries: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            // The pedestrian routing service allows at most one request per second.
            // Its caller handles throttling; do not bypass it with automatic retries.
            if (chain.request().url.host == "routing.openstreetmap.de") return chain.proceed(chain.request())
            var attempt = 0
            var lastError: IOException? = null
            while (attempt < maxRetries) {
                // Anulowane wywołanie (np. anulowany sync) nie powinno dalej ponawiać.
                if (chain.call().isCanceled()) throw IOException("Canceled")
                try {
                    val response = chain.proceed(chain.request())
                    if (response.code in 500..599 && attempt < maxRetries - 1) {
                        response.close()
                        backoff(attempt)
                        attempt++
                        continue
                    }
                    return response
                } catch (e: IOException) {
                    lastError = e
                    if (attempt >= maxRetries - 1 || chain.call().isCanceled()) throw e
                    backoff(attempt)
                    attempt++
                }
            }
            throw lastError ?: IOException("RetryInterceptor: exhausted retries")
        }

        private fun backoff(attempt: Int) {
            try {
                Thread.sleep((500L * (1L shl attempt)).coerceAtMost(4_000L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted", e)
            }
        }
    }
}
