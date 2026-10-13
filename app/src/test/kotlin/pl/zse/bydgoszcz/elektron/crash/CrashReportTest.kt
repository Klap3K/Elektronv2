package pl.zse.bydgoszcz.elektron.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    private val stack = "java.lang.IllegalStateException: Brak lekcji\n\tat pl.zse.A.b(A.kt:10)\n"
    private fun report(s: String = stack) =
        CrashReport.format("0.6.0-alpha", 13, "gms", "15", 35, "Xiaomi 2201117TY", "2026-10-01 07:40", s)

    @Test fun headerAndStack() {
        val r = report()
        assertTrue(r.startsWith("Elektron-Vulcan 0.6.0-alpha (13, gms)\nAndroid 15 (API 35), Xiaomi 2201117TY"))
        assertTrue(r.contains("at pl.zse.A.b(A.kt:10)"))
    }

    @Test fun titleIsSimpleExceptionName() {
        assertEquals("Awaria: IllegalStateException: Brak lekcji", CrashReport.title(report()))
    }

    @Test fun longStackIsTrimmed() {
        val r = report("java.lang.Error\n" + "\tat x.Y.z(Y.kt:1)\n".repeat(2000))
        assertTrue(r.length < 8_300)
        assertTrue(r.endsWith("(przycięto)"))
    }

    @Test fun issueUrlIsEncodedAndBounded() {
        val url = CrashReport.issueUrl(report("java.lang.Error: zażółć\n" + "\tat x.Y.z(Y.kt:1)\n".repeat(2000)))
        assertTrue(url.startsWith("https://github.com/Klap3K/Elektronv2/issues/new?title=Awaria"))
        assertTrue(!url.contains(' ') && !url.contains('\n'))
        assertTrue(url.length < 8_000)   // GitHub odrzuca dłuższe adresy
    }

    @Test fun feedbackUrlContainsDeviceInfo() {
        val url = CrashReport.feedbackUrl("0.6.0-alpha", 13, "foss", "14", 34, "Google Pixel 7")
        assertTrue(url.startsWith("https://github.com/Klap3K/Elektronv2/issues/new?body="))
        assertTrue(url.contains("Pixel%207"))
        assertTrue(url.contains("foss"))
    }

    @Test fun reportWithThreadDetailsAndLogs() {
        val r = CrashReport.format("1.0.0-rc1", 20, "gms", "14", 34, "Google Pixel 7", "2026-10-02 08:15", stack,
            thread = "main", details = listOf("Klasa" to "1D"), logs = "10-02 08:14:59.123 W/ZseSubstitutionsSource: HTTP 503")
        assertTrue(r.contains("Wątek: main"))
        assertTrue(r.contains("Klasa: 1D"))
        assertTrue(r.contains("--- Ostatnie logi aplikacji ---\n10-02 08:14:59.123 W/ZseSubstitutionsSource"))
        assertTrue(CrashReport.title(r).startsWith("Awaria: IllegalStateException"))
    }

    @Test fun mailtoGoesToContactAddressAndIsBounded() {
        val uri = CrashReport.mailtoUri("eLektron: test", "x".repeat(20_000))
        assertTrue(uri.startsWith("mailto:klap3k@users.noreply.github.com?subject=eLektron%3A%20test&body="))
        assertTrue(uri.length < 20_000)
    }

    @Test fun feedbackWithoutCrashHasPlainSubject() {
        val r = CrashReport.format("1.0.0-rc1", 20, "foss", "14", 34, "X", "t", "", logs = "linia")
        assertEquals("Elektron-Vulcan: zgłoszenie problemu", CrashReport.emailSubject(r))
    }

    @Test fun logsWithoutSystemNoise() {
        // Linie z prawdziwego raportu 1.0.0-rc1 (Pixel 9a, Android 17).
        val raw = """
            --------- beginning of main
            10-03 12:29:29.048 D/Zygote (20620): FORCIBLY_ENABLE_MEMORY_TAGGING is set
            10-03 12:29:29.059 E/FeatureFlagsImplExport(20620): AconfigStorageReadException: package android.xr cannot be found
            10-03 12:29:29.096 D/nativeloader(20620): InitApexLibraries:
            10-03 12:29:29.201 I/Surface (20620): Creating surface for consumer unnamed-20620-0
            10-03 12:29:29.503 I/OptivumListaParser(20620): Sparsowano 201 pozycji
            10-03 12:29:32.007 I/ZastepstwaParser(20620): Sparsowano 9 zastępstw (ostrzeżeń=0)
            10-03 12:30:31.928 I/WM-WorkerWrapper(20620): Worker result SUCCESS for Work [ tags={ SyncWorker } ]
            10-03 12:30:36.561 D/InsetsController(20620): hide(ime())
            10-03 12:31:02.100 W/ZseSubstitutionsSource(20620): HTTP 503 dla https://zastepstwa.zse.bydgoszcz.pl/index.html
        """.trimIndent()
        val f = CrashReport.filterLogs(raw, setOf("OptivumListaParser", "ZastepstwaParser", "ZseSubstitutionsSource"))
        val lines = f.lines()
        assertEquals(5, lines.size)
        assertTrue(lines[0].contains("E/FeatureFlagsImplExport"))     // błędy zawsze
        assertTrue(lines[1].contains("OptivumListaParser"))
        assertTrue(lines[3].contains("WM-WorkerWrapper"))
        assertTrue(lines[4].contains("HTTP 503"))
        assertTrue(f.lines().none { "Zygote" in it || "nativeloader" in it || "Surface" in it || "InsetsController" in it || "beginning of" in it })
    }

    @Test fun headerLinesAreSeparate() {
        val r = CrashReport.format("1.0.0-rc1", 20, "gms", "17", 37, "Google Pixel 9a", "t", "")
        assertEquals("Elektron-Vulcan 1.0.0-rc1 (20, gms)", r.lines()[0])
        assertEquals("Android 17 (API 37), Google Pixel 9a", r.lines()[1])
    }
}
