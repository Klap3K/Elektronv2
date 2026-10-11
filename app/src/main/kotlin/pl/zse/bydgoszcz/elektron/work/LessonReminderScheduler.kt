package pl.zse.bydgoszcz.elektron.work

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.model.LessonReminders
import pl.zse.bydgoszcz.elektron.domain.model.ReminderMode
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Przypomnienia przed lekcją. Zawsze ustawiony jest najwyżej JEDEN alarm - na najbliższe
 * przypomnienie. Przeliczany po każdej synchronizacji (co 15 min - uwzględnia nowe zastępstwa,
 * zmianę grup i restart telefonu), po zmianie ustawień i po wyświetleniu przypomnienia.
 *
 * Dokładne alarmy tylko, gdy system na to pozwala (Android 12+: "Alarmy i przypomnienia");
 * inaczej alarm może przyjść z kilkuminutowym opóźnieniem.
 */
@Singleton
class LessonReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val timetableRepo: TimetableRepository,
    private val noteReminders: NoteReminderScheduler? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    data class Status(val at: LocalDateTime? = null, val lesson: String? = null,
        val exact: Boolean = false, val error: Boolean = false, val ready: Boolean = false)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** Przelicz w tle (bez czekania). */
    fun requestReschedule() {
        scope.launch { reschedule() }
    }

    suspend fun reschedule(now: LocalDateTime = LocalDateTime.now()) = mutex.withLock {
        runCatchingCancellable { noteReminders?.reschedule(now) }.onFailure {
            Log.w(TAG, "Nie udało się przeliczyć przypomnień notatek", it)
        }
        runCatchingCancellable { rescheduleLocked(now) }.onFailure {
            _status.value = Status(error = true, ready = true)
            Log.w(TAG, "Nie udało się ustawić przypomnienia", it)
        }
    }

    private suspend fun rescheduleLocked(now: LocalDateTime) {
        val am = context.getSystemService(AlarmManager::class.java) ?: error("Brak usługi alarmów")
        val reminderSettings = settings.reminderSettings.first()
        val classId = settings.selectedClassId.first()
        val reminder = if (reminderSettings.mode == ReminderMode.OFF || classId == null) null else {
            val today = now.toLocalDate()
            val lessons = LessonGroups.filter(
                timetableRepo.getLessonsOnce(classId, today, today.plusDays(7)),
                settings.groupSelections(classId).first()
            )
            LessonReminders.next(lessons, now, reminderSettings, handled = handledKey())
        }

        if (reminder == null) {
            PendingIntent.getBroadcast(context, REQUEST_CODE, baseIntent(),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it) }
            _status.value = Status(ready = true)
            return
        }

        val (what, body) = LessonReminders.describe(reminder.lesson, settings.subjectStyles.first())
        val zone = ZoneId.systemDefault()
        val intent = baseIntent()
            .putExtra(EXTRA_CLASS, reminder.lesson.classId)
            .putExtra(EXTRA_NUMBER, reminder.lesson.number)
            .putExtra(EXTRA_ID, reminder.lesson.id.hashCode())
            .putExtra(EXTRA_WHAT, what)
            .putExtra(EXTRA_BODY, body)
            .putExtra(EXTRA_START, reminder.lessonStart.atZone(zone).toInstant().toEpochMilli())
            .putExtra(EXTRA_END, LocalDateTime.of(reminder.lesson.date, reminder.lesson.timeTo)
                .atZone(zone).toInstant().toEpochMilli())
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = reminder.at.atZone(zone).toInstant().toEpochMilli()
        var exact = canUseExactAlarms(context)
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            exact = false
            // Uprawnienie cofnięte w międzyczasie - alarm niedokładny.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        _status.value = Status(reminder.at, what, exact, ready = true)
    }

    data class Delivery(val id: Int, val title: String, val body: String, val end: LocalDateTime)

    suspend fun delivery(classId: String?, number: Int, start: LocalDateTime, now: LocalDateTime): Delivery? {
        if (classId == null || settings.selectedClassId.first() != classId) return null
        val lessons = LessonGroups.filter(
            timetableRepo.getLessonsOnce(classId, start.toLocalDate(), start.toLocalDate()),
            settings.groupSelections(classId).first()
        )
        val lesson = LessonReminders.due(lessons, number, start, now, settings.reminderSettings.first()) ?: return null
        val (what, body) = LessonReminders.describe(lesson, settings.subjectStyles.first())
        return Delivery(lesson.id.hashCode(), LessonReminders.title(what, start, now), body,
            LocalDateTime.of(lesson.date, lesson.timeTo))
    }

    /** Alarm przypomnienia przyszedł (pokazany albo już nieaktualny) - nie ustawiamy go ponownie. */
    fun markHandled(number: Int, start: LocalDateTime) {
        prefs().edit().putString(KEY_HANDLED, LessonReminders.key(number, start)).apply()
    }

    private fun handledKey(): String? = prefs().getString(KEY_HANDLED, null)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun baseIntent() = Intent(context, LessonReminderReceiver::class.java)

    companion object {
        private const val TAG = "LessonReminders"
        private const val REQUEST_CODE = 4101
        private const val PREFS = "lesson_reminders"
        private const val KEY_HANDLED = "handled"
        const val EXTRA_CLASS = "reminder_class"
        const val EXTRA_NUMBER = "reminder_number"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_WHAT = "reminder_what"
        const val EXTRA_BODY = "reminder_body"
        const val EXTRA_START = "reminder_start"
        const val EXTRA_END = "reminder_end"

        fun canUseExactAlarms(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }
    }
}
