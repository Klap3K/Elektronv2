package pl.zse.bydgoszcz.elektron.crash

import java.net.URLEncoder

/**
 * Treść raportu awarii - czysta logika (bez Androida), testowana w CrashReportTest.
 * Raport zawiera wyłącznie dane techniczne: wersję, wariant, model telefonu i ślad stosu.
 * Nic nie jest wysyłane automatycznie - użytkownik sam decyduje, czy go udostępnić.
 */
object CrashReport {

    const val ISSUES_URL = "https://github.com/Klap3K/Elektronv2/issues/new"

    /** Oficjalny adres kontaktowy dewelopera (raporty błędów, pytania). */
    const val CONTACT_EMAIL = "klap3k@users.noreply.github.com"

    /** Dłuższe ślady stosu są przycinane (w e-mailu jest pełny raport z logami). */
    private const val MAX_STACK_CHARS = 8_000

    /** Ile znaków treści trafia do adresu mailto: (część programów pocztowych czyta tylko adres). */
    private const val MAX_MAILTO_BODY = 6_000

    fun format(
        versionName: String,
        versionCode: Int,
        flavor: String,
        androidVersion: String,
        sdkInt: Int,
        device: String,
        time: String,
        stackTrace: String,
        /** Wątek, na którym poleciał wyjątek (null - zgłoszenie bez awarii). */
        thread: String? = null,
        /** Dodatkowe informacje, np. klasa i stan synchronizacji. */
        details: List<Pair<String, String>> = emptyList(),
        /** Ostatnie logi aplikacji (logcat własnego procesu). */
        logs: String? = null
    ): String = buildString {
        appendLine("Elektron-Vulcan $versionName ($versionCode, $flavor)")
        appendLine("Android $androidVersion (API $sdkInt), $device")
        appendLine("Czas: $time")
        thread?.let { appendLine("Wątek: $it") }
        details.forEach { (k, v) -> appendLine("$k: $v") }
        if (stackTrace.isNotBlank()) {
            appendLine()
            append(trimStack(stackTrace))
        }
        logs?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine()
            appendLine("--- Ostatnie logi aplikacji ---")
            append(it)
        }
    }

    /** Linia logcata w formacie "-v time": "10-03 12:29:29.048 D/Zygote (20620): ...". */
    private val LOG_LINE = Regex("""^\S+\s+\S+\s+([VDIWEFA])/([^(]+?)\s*\(\s*\d+\):""")

    /** Tagi spoza aplikacji, które też pomagają (praca w tle, awarie, wyjątki). */
    private val USEFUL_SYSTEM_TAGS = setOf("WM-WorkerWrapper", "AndroidRuntime", "System.err", "FirebaseMessaging")

    /**
     * Logi do raportu bez szumu systemowego: zostają linie aplikacji ([appTags]), pracy w tle
     * i wszystkie ostrzeżenia/błędy (W/E/F/A) - odpadają np. Zygote, nativeloader, Surface,
     * InsetsController (w pierwszym raporcie z 1.0.0-rc1 było ich ~90%). Najwyżej [maxLines]
     * ostatnich linii.
     */
    fun filterLogs(raw: String, appTags: Set<String>, maxLines: Int = 150): String =
        raw.lineSequence()
            .filter { line ->
                val m = LOG_LINE.find(line) ?: return@filter false
                val level = m.groupValues[1]
                val tag = m.groupValues[2].trim()
                level in setOf("W", "E", "F", "A") || tag in appTags || tag in USEFUL_SYSTEM_TAGS
            }
            .toList()
            .takeLast(maxLines)
            .joinToString("\n")

    /** Temat e-maila z raportem. */
    fun emailSubject(report: String): String =
        if (report.lineSequence().any { it.startsWith("--- ") } && title(report) == "Awaria aplikacji") "Elektron-Vulcan: zgłoszenie problemu"
        else "Elektron-Vulcan: " + title(report)

    /**
     * Adres mailto: z tematem i treścią (przyciętą - pełna treść idzie też jako EXTRA_TEXT,
     * którą czyta większość programów pocztowych, np. Gmail).
     */
    fun mailtoUri(subject: String, body: String): String {
        val shortBody = if (body.length <= MAX_MAILTO_BODY) body
            else body.take(MAX_MAILTO_BODY).trimEnd() + "\n… (przycięto - pełny raport skopiuj przyciskiem Kopiuj logi)"
        return "mailto:$CONTACT_EMAIL?subject=${enc(subject)}&body=${enc(shortBody)}"
    }

    fun trimStack(stack: String): String =
        if (stack.length <= MAX_STACK_CHARS) stack.trimEnd()
        else stack.take(MAX_STACK_CHARS).trimEnd() + "\n… (przycięto)"

    /** Pierwsza linia śladu stosu, np. "java.lang.IllegalStateException: …" - tytuł zgłoszenia. */
    fun title(report: String): String {
        val line = report.lineSequence()
            .dropWhile { it.isNotBlank() }   // pomijamy nagłówek z wersją
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { !it.startsWith("--- ") }   // zgłoszenie bez awarii: od razu logi
            ?: return "Awaria aplikacji"
        val simple = line.substringBefore(':').substringAfterLast('.')
        val message = if (':' in line) ":" + line.substringAfter(':') else ""
        return ("Awaria: $simple$message").take(110)
    }

    /**
     * Adres nowego zgłoszenia na GitHubie z wypełnioną treścią. GitHub odrzuca zbyt długie
     * adresy, a po zakodowaniu każdy znak nowej linii zajmuje 3 znaki - dlatego raport jest
     * skracany, aż adres zmieści się w limicie. Pełny raport zostaje do "Udostępnij".
     */
    fun issueUrl(report: String, maxLength: Int = 7_500): String {
        var text = report
        while (true) {
            val body = "Co robiłeś/aś tuż przed awarią?\n\n\n---\n```\n$text\n```"
            val url = "$ISSUES_URL?title=${enc(title(report))}&body=${enc(body)}&labels=crash"
            if (url.length <= maxLength || text.length < 200) return url
            text = text.take(text.length * 3 / 4).trimEnd() + "\n… (przycięto)"
        }
    }

    /** "Zgłoś problem" z Ustawień: zgłoszenie bez awarii, z danymi technicznymi na dole. */
    fun feedbackUrl(
        versionName: String, versionCode: Int, flavor: String,
        androidVersion: String, sdkInt: Int, device: String
    ): String {
        val body = "Opisz problem - co się stało i co spodziewałeś/aś się zobaczyć?\n\n\n---\n" +
            "Elektron-Vulcan $versionName ($versionCode, $flavor)\nAndroid $androidVersion (API $sdkInt), $device"
        return "$ISSUES_URL?body=${enc(body)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
