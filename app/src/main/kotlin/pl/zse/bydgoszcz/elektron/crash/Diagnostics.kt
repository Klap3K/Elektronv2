package pl.zse.bydgoszcz.elektron.crash

import androidx.core.app.NotificationManagerCompat
import android.os.PowerManager
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.content.Context
import android.app.AlarmManager
import android.os.Build
import android.os.Process
import pl.zse.bydgoszcz.elektron.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Dane techniczne do raportów: logi własnego procesu i nagłówek z wersją/telefonem. */
object Diagnostics {

    /**
     * Ostatnie [maxLines] linii logcata WŁASNEGO procesu (Android pozwala aplikacji czytać
     * własne logi bez uprawnień). Widać tam np. błędy HTTP i ostrzeżenia parserów tuż przed
     * problemem. Najwyżej [timeoutMs] - raport nie może zawiesić aplikacji (ani obsługi awarii).
     */
    fun appLogs(maxLines: Int = 150, timeoutMs: Long = 1_500): String? = runCatching {
        // Szerszy zakres z logcata, potem filtr bez szumu systemowego (CrashReport.filterLogs).
        val proc = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", "1500", "--pid=${Process.myPid()}"
        ).redirectErrorStream(true).start()
        var out = ""
        val reader = Thread { out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("") }
        reader.start()
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) proc.destroy()
        reader.join(300)
        CrashReport.filterLogs(out, APP_TAGS, maxLines).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Tagi logów aplikacji (stałe TAG w klasach). */
    private val APP_TAGS = setOf(
        "AnnouncementMapper", "AnnouncementsRepositoryImpl", "ApkUpdateInstaller", "ClassSelection",
        "ElektronFcmService", "FcmTopicManager", "LessonReminders", "LocalNotificationSink", "ElektronActivity", "SubstitutionsWidget", "NextLessonWidget", "DayPlanWidget",
        "NextLessonTile", "OptivumListaParser", "OptivumTimetableParser", "RssParser", "SafeUrls",
        "SettingsViewModel", "SetupViewModel", "SubstitutionMapper", "SubstitutionsRepositoryImpl",
        "SyncCoordinator", "SyncWorker", "TimetableRepositoryImpl", "WidgetUpdater", "ZastepstwaParser",
        "ZseRssAnnouncementsSource", "ZseSubstitutionsSource", "ZseTimetableSource",
        "AppIcons", "AppShortcuts", "ElektronApp", "LessonNotes", "LessonReminder", "NoteReminders",
        "SettingsRepository", "SystemEvents", "TimetableMapper", "WidgetData", "WidgetSession"
        // Bez logów Odjazdów (Transit*): zawierają przystanki, a cel podróży bywa adresem domowym.
    )

    /**
     * Stan telefonu ważny przy zgłoszeniach "nie przyszło powiadomienie" / "nie odświeża się":
     * powiadomienia, oszczędzanie baterii, dokładne alarmy, połączenie z internetem.
     */
    fun deviceState(context: Context): List<Pair<String, String>> = runCatching<List<Pair<String, String>>> {
        val yes: (Boolean) -> String = { b -> if (b) "tak" else "nie" }
        val power: PowerManager? = context.getSystemService(PowerManager::class.java)
        val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)
        val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
        val caps: NetworkCapabilities? = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val kind: String = when {
            caps == null -> "brak"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "komórkowa"
            else -> "inna"
        }
        val validated: String = when {
            caps == null -> ""
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> ", internet działa"
            else -> ", bez potwierdzonego internetu"
        }
        val network: String = kind + validated
        listOf<Pair<String, String>>(
            "Powiadomienia włączone" to yes(NotificationManagerCompat.from(context).areNotificationsEnabled()),
            "Bez oszczędzania baterii" to yes(power?.isIgnoringBatteryOptimizations(context.packageName) == true),
            "Dokładne alarmy" to (if (Build.VERSION.SDK_INT >= 31) yes(alarms?.canScheduleExactAlarms() == true) else "tak"),
            "Sieć" to network
        )
    }.getOrDefault(emptyList<Pair<String, String>>())

    fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    /** Raport bez awarii ("Zgłoś problem"): nagłówek, [details], stan telefonu i logi. */
    fun feedbackReport(context: Context, details: List<Pair<String, String>>): String = CrashReport.format(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        flavor = BuildConfig.FLAVOR,
        androidVersion = Build.VERSION.RELEASE ?: "?",
        sdkInt = Build.VERSION.SDK_INT,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        time = now(),
        stackTrace = "",
        details = details + deviceState(context),
        logs = appLogs()
    )
}
