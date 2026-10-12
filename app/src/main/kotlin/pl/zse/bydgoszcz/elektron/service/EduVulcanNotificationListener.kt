package pl.zse.bydgoszcz.elektron.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.data.local.SchoolEventDao
import pl.zse.bydgoszcz.elektron.data.local.SchoolEventEntity
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import pl.zse.bydgoszcz.elektron.domain.repository.AttendanceRepository
import pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository
import pl.zse.bydgoszcz.elektron.widget.WidgetUpdater
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pomocnik do integracji z zainstalowaną na urządzeniu oficjalną aplikacją eduVULCAN
 * (pl.edu.vulcan.hebece / Dzienniczek VULCAN).
 */
object EduVulcanAppBridge {

    val EDUVULCAN_PACKAGES = setOf(
        "pl.edu.vulcan.hebece",
        "pl.edu.vulcan.uonetmobile",
        "pl.vulcan.uonetmobile"
    )

    /**
     * Sprawdza, czy oficjalna aplikacja eduVULCAN jest zainstalowana na telefonie.
     */
    fun isEduVulcanAppInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return EDUVULCAN_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Sprawdza, czy użytkownik przyznał uprawnienie do odczytu powiadomień z aplikacji eduVULCAN.
     */
    fun isNotificationBridgeEnabled(context: Context): Boolean {
        return runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        }.getOrDefault(false)
    }

    /**
     * Otwiera zainstalowaną aplikację eduVULCAN na telefonie.
     */
    fun openInstalledEduVulcanApp(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in EDUVULCAN_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
            }
        }
        return false
    }

    /**
     * Otwiera ustawienia systemowe dostępu do powiadomień (Notification Listener).
     */
    fun openNotificationAccessSettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

/**
 * Usługa nasłuchująca powiadomień z zalogowanej na telefonie oficjalnej aplikacji eduVULCAN.
 * Automatycznie przechwytuje nowe sprawdziany, kartkówki, zadania domowe oraz wpisy frekwencji
 * pojawiające się na urządzeniu i zapisuje je do lokalnej bazy danych Elektron-Vulcan.
 */
class EduVulcanNotificationListener : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ListenerEntryPoint {
        fun schoolEventDao(): SchoolEventDao
        fun gradeDao(): pl.zse.bydgoszcz.elektron.data.local.GradeDao
        fun calendarRepository(): CalendarRepository
        fun attendanceRepository(): AttendanceRepository
        fun widgetUpdater(): WidgetUpdater
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Po podłączeniu skanujemy również aktualnie widoczne powiadomienia z eduVULCAN
        runCatching {
            activeNotifications?.forEach { sbn ->
                processNotification(sbn)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            processNotification(sbn)
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in EduVulcanAppBridge.EDUVULCAN_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        val combined = "$title $text".trim()
        if (combined.isBlank()) return

        val lower = combined.lowercase(Locale.getDefault())

        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(applicationContext, ListenerEntryPoint::class.java)
        }.getOrNull() ?: return

        scope.launch {
            // 1. Sprawdź czy powiadomienie dotyczy sprawdzianu, kartkówki lub zadania domowego
            val isExamOrQuiz = lower.contains("sprawdzian") ||
                lower.contains("kartkówk") ||
                lower.contains("kartkowk") ||
                lower.contains("klasówk") ||
                lower.contains("klasowk") ||
                lower.contains("zadanie domowe") ||
                lower.contains("terminarz") ||
                lower.contains("test")

            if (isExamOrQuiz) {
                val eventType = when {
                    lower.contains("kartk") -> SchoolEventType.QUIZ
                    lower.contains("zadanie") || lower.contains("domow") -> SchoolEventType.HOMEWORK
                    else -> SchoolEventType.EXAM
                }

                val parsedDate = extractDateFromText(combined) ?: LocalDate.now().plusDays(1)
                val parsedSubject = extractSubjectFromText(title, text)
                val eventTitle = title.ifBlank {
                    when (eventType) {
                        SchoolEventType.QUIZ -> "Kartkówka"
                        SchoolEventType.HOMEWORK -> "Zadanie domowe"
                        else -> "Sprawdzian"
                    } + (if (!parsedSubject.isNullOrBlank()) ": $parsedSubject" else "")
                }

                val hashId = "notif_eduvulcan_${(eventTitle + parsedDate.toString() + text).hashCode()}"
                val entity = SchoolEventEntity(
                    id = hashId,
                    title = eventTitle,
                    description = text.ifBlank { null },
                    eventType = eventType.name,
                    subject = parsedSubject,
                    dateEpochDay = parsedDate.toEpochDay(),
                    timeFrom = null,
                    timeTo = null,
                    location = null,
                    rawSummary = "$eventTitle ${parsedSubject.orEmpty()} $text"
                )

                entryPoint.schoolEventDao().upsertAll(listOf(entity))
                entryPoint.widgetUpdater().requestUpdate()
            }

            // 2. Jeśli powiadomienie dotyczy nowej oceny, frekwencji lub terminarza – zsynchronizuj dane z eduVULCAN
            val isGrade = lower.contains("ocen") || lower.contains("stopie")
            if (isGrade) {
                extractGradeFromNotification(title, text)?.let { gradeEntity ->
                    runCatching { entryPoint.gradeDao().upsert(gradeEntity) }
                }
            }

            if (isExamOrQuiz || isGrade || lower.contains("frekwencj") || lower.contains("nieobecno") || lower.contains("spóźnien")) {
                runCatching { entryPoint.calendarRepository().sync() }
                runCatching { entryPoint.attendanceRepository().sync() }
            }
        }
    }

    private fun extractGradeFromNotification(title: String, body: String): pl.zse.bydgoszcz.elektron.data.local.GradeEntity? {
        val combined = "$title $body"
        val gradeMatch = Regex("""(?:ocena|stopień)[:\s]+([1-6][+-]?|\d{1,3}%)""", RegexOption.IGNORE_CASE).find(combined)
            ?: Regex("""\b([1-6][+-]?)\b""").find(body)
            ?: return null
        val gradeValue = gradeMatch.groupValues[1].trim()
        val subject = extractSubjectFromText(title, body) ?: "Przedmiot szkolny"
        val todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        return pl.zse.bydgoszcz.elektron.data.local.GradeEntity(
            id = "manual_notif_${(subject + gradeValue + todayStr + body).hashCode()}",
            subject = subject,
            value = gradeValue,
            weight = 1.0,
            category = "Z powiadomienia eduVULCAN",
            description = body.takeIf { it.isNotBlank() },
            teacher = null,
            dateText = todayStr,
            periodNumber = 1,
            isActivePeriod = true,
            isSuperseded = false,
            apiSubjectAverage = null,
            proposedGrade = null,
            finalGrade = null,
            updatedAtEpochSeconds = System.currentTimeMillis() / 1000
        )
    }

    private fun extractDateFromText(text: String): LocalDate? {
        val isoRegex = Regex("(\\d{4}-\\d{2}-\\d{2})")
        isoRegex.find(text)?.groupValues?.getOrNull(1)?.let { m ->
            runCatching { return LocalDate.parse(m, DateTimeFormatter.ISO_LOCAL_DATE) }
        }

        val dotRegex = Regex("(\\d{1,2}\\.\\d{1,2}\\.\\d{4})")
        dotRegex.find(text)?.groupValues?.getOrNull(1)?.let { m ->
            for (pattern in listOf("dd.MM.yyyy", "d.MM.yyyy")) {
                val d = runCatching { LocalDate.parse(m, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
                if (d != null) return d
            }
        }

        val lower = text.lowercase(Locale.getDefault())
        if (lower.contains("jutro")) return LocalDate.now().plusDays(1)
        if (lower.contains("pojutrze")) return LocalDate.now().plusDays(2)
        if (lower.contains("dzisiaj") || lower.contains("dziś")) return LocalDate.now()
        return null
    }

    private fun extractSubjectFromText(title: String, body: String): String? {
        val combined = "$title: $body"
        val afterColon = combined.substringAfter(":", "").trim()
        if (afterColon.isNotBlank()) {
            val candidate = afterColon.substringBefore("\n").substringBefore("(").substringBefore("-").trim()
            if (candidate.length in 2..40) return candidate
        }
        return null
    }
}
