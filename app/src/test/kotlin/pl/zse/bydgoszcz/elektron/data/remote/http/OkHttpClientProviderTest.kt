package pl.zse.bydgoszcz.elektron.data.remote.http

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class OkHttpClientProviderTest {

    private val base = OkHttpClientProvider.create(
        debug = false, versionName = "1.0-test", cacheDir = Files.createTempDirectory("http").toFile()
    )

    @Test
    fun downloadClientHasNoCallTimeoutAndNoCache() {
        // Dawniej pobieranie APK dziedziczyło callTimeout 45 s wspólnego klienta - plik ok. 20 MB
        // na wolnym łączu nie zdążył się pobrać i aktualizacja zawsze się przerywała.
        val download = OkHttpClientProvider.createDownloadClient(base, "1.0-test")
        assertEquals(45_000, base.callTimeoutMillis)
        assertEquals(0, download.callTimeoutMillis)
        assertNull(download.cache)
        assertEquals(60_000, download.readTimeoutMillis)
    }

    @Test
    fun downloadClientSendsOnlyUserAgent() {
        // Bez nagłówków dla stron szkoły (Accept HTML, no-cache) i bez ponawiania całego pobierania.
        var seen: Request? = null
        var calls = 0
        val client = OkHttpClientProvider.createDownloadClient(base, "1.0-test").newBuilder()
            .addInterceptor { chain ->
                calls++
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("Service Unavailable").body("".toResponseBody()).build()
            }
            .build()
        client.newCall(Request.Builder().url("https://github.com/x.apk").build()).execute().close()
        assertEquals(1, calls)                                   // 503 bez ponawiania
        assertEquals("Elektron-Vulcan/1.0-test (+https://github.com/Klap3K/Elektronv2)", seen?.header("User-Agent"))
        assertNull(seen?.header("Accept"))
        assertNull(seen?.header("Cache-Control"))
    }
}
