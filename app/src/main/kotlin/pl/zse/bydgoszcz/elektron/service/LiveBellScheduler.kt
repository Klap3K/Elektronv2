package pl.zse.bydgoszcz.elektron.service

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.model.SubstitutionDisplay
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveBellScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val timetableRepo: TimetableRepository,
    private val notificationManager: LiveBellNotificationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    fun requestReschedule() {
        scope.launch { evaluateAndSchedule() }
    }

    suspend fun evaluateAndSchedule(now: LocalDateTime = LocalDateTime.now()) = mutex.withLock {
        runCatchingCancellable {
            evaluateAndScheduleLocked(now)
        }.onFailure {
            Log.w(TAG, "Błąd planowania Live Bell", it)
        }
    }

    private suspend fun evaluateAndScheduleLocked(now: LocalDateTime) {
        val am = context.getSystemService(AlarmManager::class.java)
        val enabled = settings.liveBellNotificationEnabled.first()
        val classId = settings.selectedClassId.first()

        if (!enabled || classId.isNullOrBlank()) {
            notificationManager.cancelNotification()
            cancelAlarm(am)
            return
        }

        // 1. Zaktualizuj powiadomienie
        notificationManager.updateNotification(now)

        val today = now.toLocalDate()
        val time = now.toLocalTime()

        // 2. Pobierz lekcje
        val rawLessons = timetableRepo.getLessonsOnce(classId, today, today)
        val groups = settings.groupSelections(classId).first()
        val lessons = LessonGroups.filter(rawLessons, groups)
        val heldLessons = lessons.filter(SubstitutionDisplay::takesPlace).sortedBy { it.timeFrom }

        val todayTransitions = mutableListOf<LocalTime>()
        val firstLesson = heldLessons.firstOrNull()
        if (firstLesson != null) {
            val preBellTime = firstLesson.timeFrom.minusMinutes(45)
            if (preBellTime.isBefore(firstLesson.timeFrom)) {
                todayTransitions.add(preBellTime)
            }
        }
        heldLessons.forEach {
            todayTransitions.add(it.timeFrom)
            todayTransitions.add(it.timeTo)
        }

        val nextTransitionToday = todayTransitions.filter { it.isAfter(time) }.minOrNull()

        val nextAlarmDateTime: LocalDateTime? = if (nextTransitionToday != null) {
            today.atTime(nextTransitionToday)
        } else {
            // Brak kolejnych dzwonków dzisiaj - sprawdź pierwsze zajęcia w kolejnych 7 dniach
            val futureLessons = timetableRepo.getLessonsOnce(classId, today.plusDays(1), today.plusDays(7))
            val filteredFuture = LessonGroups.filter(futureLessons, groups).filter(SubstitutionDisplay::takesPlace)
            filteredFuture.minWithOrNull(compareBy({ it.date }, { it.timeFrom }))?.let { nextLesson ->
                // Obudź 45 min przed pierwszą lekcją, żeby pokazać powiadomienie przedlekcyjne
                LocalDateTime.of(nextLesson.date, nextLesson.timeFrom).minusMinutes(45)
            }
        }

        if (nextAlarmDateTime == null || am == null) {
            cancelAlarm(am)
            return
        }

        scheduleAlarm(am, nextAlarmDateTime)
    }

    private fun scheduleAlarm(am: AlarmManager, atDateTime: LocalDateTime) {
        val intent = Intent(context, LiveBellReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val zone = ZoneId.systemDefault()
        val atEpochMs = atDateTime.atZone(zone).toInstant().toEpochMilli()

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }

        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
            }
            Log.d(TAG, "Zaplanowano kolejne przełączenie Live Bell na $atDateTime (exact=$canExact)")
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się zaplanować alarmu Live Bell", e)
        }
    }

    private fun cancelAlarm(am: AlarmManager?) {
        if (am == null) return
        val intent = Intent(context, LiveBellReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { am.cancel(it) }
    }

    companion object {
        private const val TAG = "LiveBellScheduler"
        private const val REQUEST_CODE = 4106
    }
}
