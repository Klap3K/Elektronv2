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
import pl.zse.bydgoszcz.elektron.domain.model.LessonClock
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
class AutoDndScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val timetableRepo: TimetableRepository
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
            Log.w(TAG, "Błąd planowania Auto DND", it)
        }
    }

    private suspend fun evaluateAndScheduleLocked(now: LocalDateTime) {
        val am = context.getSystemService(AlarmManager::class.java)
        val enabled = settings.autoDndEnabled.first()
        val classId = settings.selectedClassId.first()

        if (!enabled || classId == null) {
            // Jeśli opcja jest wyłączona lub brak klasy, a telefon był wyciszony przez aplikację, przywróć dźwięk
            AutoDndManager.evaluate(context, enabled = false, isLessonOngoing = false)
            cancelAlarm(am)
            return
        }

        val today = now.toLocalDate()
        val time = now.toLocalTime()

        // Pobierz lekcje na dziś (z uwzględnieniem grup)
        val rawLessons = timetableRepo.getLessonsOnce(classId, today, today)
        val groups = settings.groupSelections(classId).first()
        val lessons = LessonGroups.filter(rawLessons, groups)
        val heldLessons = lessons.filter(SubstitutionDisplay::takesPlace)

        // Sprawdź czy trwa lekcja
        val clockStatus = LessonClock.status(heldLessons, now)
        val isLessonOngoing = (clockStatus is LessonClock.During)

        // Zastosuj wyciszenie / przywrócenie dźwięku
        AutoDndManager.evaluate(context, enabled = true, isLessonOngoing = isLessonOngoing)

        // Wyznacz kolejny punkt przejścia (dzwonek na początek lub koniec lekcji)
        val todayTransitions = mutableListOf<LocalTime>()
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
                nextLesson.date.atTime(nextLesson.timeFrom)
            }
        }

        if (nextAlarmDateTime == null || am == null) {
            cancelAlarm(am)
            return
        }

        scheduleAlarm(am, nextAlarmDateTime)
    }

    private fun scheduleAlarm(am: AlarmManager, atDateTime: LocalDateTime) {
        val intent = Intent(context, AutoDndReceiver::class.java)
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
            Log.d(TAG, "Zaplanowano kolejne przełączenie Auto DND na $atDateTime (exact=$canExact)")
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się zaplanować alarmu Auto DND", e)
        }
    }

    private fun cancelAlarm(am: AlarmManager?) {
        if (am == null) return
        val intent = Intent(context, AutoDndReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { am.cancel(it) }
    }

    companion object {
        private const val TAG = "AutoDndScheduler"
        private const val REQUEST_CODE = 4105
    }
}
