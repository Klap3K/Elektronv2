package pl.zse.bydgoszcz.elektron.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import pl.zse.bydgoszcz.elektron.ElektronActivity
import pl.zse.bydgoszcz.elektron.R
import pl.zse.bydgoszcz.elektron.domain.model.Lesson
import pl.zse.bydgoszcz.elektron.domain.model.LessonClock
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.model.SubstitutionDisplay
import pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository
import pl.zse.bydgoszcz.elektron.domain.repository.DutyRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import pl.zse.bydgoszcz.elektron.work.LocalNotificationSink
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveBellNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val timetableRepo: TimetableRepository,
    private val calendarRepo: CalendarRepository,
    private val dutyRepo: DutyRepository
) {
    companion object {
        const val CHANNEL_ID = "elektron_live_bell"
        const val CHANNEL_NAME = "Status lekcji na żywo („Kiedy dzwonek?”)"
        const val NOTIFICATION_ID = 0x7E000005
        private const val TAG = "LiveBellNotification"
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        mgr.createNotificationChannelGroup(
            NotificationChannelGroup(LocalNotificationSink.GROUP_ELEKTRON, "eLektron").apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    description = "Powiadomienia z aplikacji eLektron"
                }
            }
        )

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ciągłe powiadomienie na ekranie blokady z odliczaniem do dzwonka i informacją o lekcji."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            group = LocalNotificationSink.GROUP_ELEKTRON
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Odświeża lub usuwa powiadomienie na żywo.
     */
    suspend fun updateNotification(now: LocalDateTime = LocalDateTime.now()) {
        runCatchingCancellable {
            updateNotificationInternal(now)
        }.onFailure {
            Log.w(TAG, "Błąd aktualizacji powiadomienia na żywo", it)
        }
    }

    private suspend fun updateNotificationInternal(now: LocalDateTime) {
        val enabled = settings.liveBellNotificationEnabled.first()
        val classId = settings.selectedClassId.first()

        if (!enabled || classId.isNullOrBlank() || !canNotify()) {
            cancelNotification()
            return
        }

        val today = now.toLocalDate()
        val rawLessons = timetableRepo.getLessonsOnce(classId, today, today)
        val groups = settings.groupSelections(classId).first()
        val lessons = LessonGroups.filter(rawLessons, groups)
        val heldLessons = lessons.filter(SubstitutionDisplay::takesPlace).sortedBy { it.timeFrom }

        if (heldLessons.isEmpty()) {
            cancelNotification()
            return
        }

        val clockStatus = LessonClock.status(heldLessons, now)
        val events = runCatchingCancellable { calendarRepo.observeEventsForDate(today).first() }.getOrDefault(emptyList())
        val duty = runCatchingCancellable { dutyRepo.observeDuty(today).first() }.getOrNull()

        when (clockStatus) {
            is LessonClock.During -> {
                val lesson = clockStatus.lesson
                val exam = events.firstOrNull { it.matchesLesson(lesson) && it.type.isAssessment }
                val nextLesson = heldLessons.firstOrNull { it.timeFrom >= lesson.timeTo }
                postDuring(now, lesson, nextLesson, exam?.badgeLabel(), duty?.isDutyToday ?: false)
            }
            is LessonClock.Break -> {
                val nextLesson = clockStatus.next
                val exam = events.firstOrNull { it.matchesLesson(nextLesson) && it.type.isAssessment }
                postBreak(now, clockStatus.from, nextLesson, exam?.badgeLabel(), duty?.isDutyToday ?: false)
            }
            is LessonClock.Before -> {
                val nextLesson = clockStatus.next
                val minutesUntil = clockStatus.minutesUntil
                if (minutesUntil != null && minutesUntil <= 45) {
                    val exam = events.firstOrNull { it.matchesLesson(nextLesson) && it.type.isAssessment }
                    postBefore(now, nextLesson, exam?.badgeLabel(), duty?.isDutyToday ?: false)
                } else {
                    cancelNotification()
                }
            }
            is LessonClock.Nothing -> {
                cancelNotification()
            }
        }
    }

    private fun postDuring(
        now: LocalDateTime,
        lesson: Lesson,
        nextLesson: Lesson?,
        examBadge: String?,
        isDutyToday: Boolean
    ) {
        val today = now.toLocalDate()
        val subject = lesson.groups.firstOrNull()?.subject ?: "Lekcja"
        val room = lesson.groups.firstOrNull()?.room

        val title = buildString {
            append("🔔 ${lesson.number}. $subject")
            if (!room.isNullOrBlank()) append(" • s. $room")
            if (!examBadge.isNullOrBlank()) append(" [📝 $examBadge]")
        }

        val totalMinutes = Duration.between(lesson.timeFrom, lesson.timeTo).toMinutes().toInt().coerceAtLeast(1)
        val elapsedMinutes = Duration.between(lesson.timeFrom, now.toLocalTime()).toMinutes().toInt().coerceIn(0, totalMinutes)
        val minutesLeft = Duration.between(now.toLocalTime(), lesson.timeTo).toMinutes().toInt().coerceAtLeast(0)

        val nextInfo = if (nextLesson != null) {
            val nextSubj = nextLesson.groups.firstOrNull()?.subject ?: "kolejne zajęcia"
            val nextRoom = nextLesson.groups.firstOrNull()?.room
            "Następna: $nextSubj${if (!nextRoom.isNullOrBlank()) " (s. $nextRoom)" else ""}"
        } else {
            "Ostatnia lekcja dzisiaj"
        }

        val text = "$elapsedMinutes/$totalMinutes min • Do dzwonka: $minutesLeft min • $nextInfo"

        val targetEpochMs = today.atTime(lesson.timeTo).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(totalMinutes, elapsedMinutes, false)
            .setWhen(targetEpochMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)

        if (isDutyToday) {
            builder.setSubText("🧹 Dziś Twój dyżur w klasie!")
        }

        publish(builder.build())
    }

    private fun postBreak(
        now: LocalDateTime,
        breakStart: LocalTime,
        nextLesson: Lesson,
        examBadge: String?,
        isDutyToday: Boolean
    ) {
        val today = now.toLocalDate()
        val nextSubj = nextLesson.groups.firstOrNull()?.subject ?: "Lekcja"
        val nextRoom = nextLesson.groups.firstOrNull()?.room

        val title = "☕ Przerwa międzylekcyjna"
        val minutesUntil = Duration.between(now.toLocalTime(), nextLesson.timeFrom).toMinutes().toInt().coerceAtLeast(0)

        val text = buildString {
            append("Do dzwonka: $minutesUntil min • Następna: ${nextLesson.number}. $nextSubj")
            if (!nextRoom.isNullOrBlank()) append(" (s. $nextRoom)")
            if (!examBadge.isNullOrBlank()) append(" [📝 $examBadge!]")
        }

        val totalBreakMin = Duration.between(breakStart, nextLesson.timeFrom).toMinutes().toInt().coerceAtLeast(1)
        val elapsedBreakMin = Duration.between(breakStart, now.toLocalTime()).toMinutes().toInt().coerceIn(0, totalBreakMin)
        val targetEpochMs = today.atTime(nextLesson.timeFrom).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(totalBreakMin, elapsedBreakMin, false)
            .setWhen(targetEpochMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)

        if (isDutyToday) {
            builder.setSubText("🧹 Dziś Twój dyżur w klasie!")
        }

        publish(builder.build())
    }

    private fun postBefore(
        now: LocalDateTime,
        firstLesson: Lesson,
        examBadge: String?,
        isDutyToday: Boolean
    ) {
        val today = now.toLocalDate()
        val subj = firstLesson.groups.firstOrNull()?.subject ?: "Lekcja"
        val room = firstLesson.groups.firstOrNull()?.room
        val minutesUntil = Duration.between(now.toLocalTime(), firstLesson.timeFrom).toMinutes().toInt().coerceAtLeast(0)

        val title = "🔔 Za $minutesUntil min początek zajęć"
        val text = buildString {
            append("1. lekcja: $subj (${firstLesson.timeFrom})")
            if (!room.isNullOrBlank()) append(" • sala $room")
            if (!examBadge.isNullOrBlank()) append(" [📝 $examBadge]")
        }

        val targetEpochMs = today.atTime(firstLesson.timeFrom).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(45, (45 - minutesUntil).coerceIn(0, 45), false)
            .setWhen(targetEpochMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)

        if (isDutyToday) {
            builder.setSubText("🧹 Dziś Twój dyżur w klasie!")
        }

        publish(builder.build())
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        val intent = Intent(context, ElektronActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(LocalNotificationSink.EXTRA_DEEP_LINK, "timetable")
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_elektron)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setGroup(LocalNotificationSink.GROUP_ELEKTRON)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun cancelNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się skasować powiadomienia", e)
        }
    }

    private fun publish(notification: android.app.Notification) {
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak uprawnień do powiadomień", e)
        }
    }

    private fun canNotify(): Boolean =
        notificationManager.areNotificationsEnabled()
}
