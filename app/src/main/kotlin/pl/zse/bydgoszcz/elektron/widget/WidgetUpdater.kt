package pl.zse.bydgoszcz.elektron.widget

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Odświeżanie widżetów.
 *
 * Standardowy harmonogram widżetów (updatePeriodMillis) to minimum 30 min — za rzadko,
 * żeby "Teraz" / "Następna" przeskakiwało z dzwonkiem. Dlatego każdy render widżetu
 * planuje jednorazowe odświeżenie na moment, w którym stan się zmieni (koniec trwającej
 * lekcji, początek najbliższej, północ). Gdy na ekranie nie ma żadnego widżetu, Glance
 * nie wywołuje renderu i łańcuch sam wygasa.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tickMutex = kotlinx.coroutines.sync.Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Odśwież widżety w tle (po syncu, zmianie klasy lub grup). */
    fun requestUpdate() {
        scope.launch { updateNow() }
    }

    suspend fun requestDurableUpdate() = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(1, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "elektron_widget_refresh", ExistingWorkPolicy.REPLACE, request).result.get()
        Unit
    }

    suspend fun updateNow() {
        WidgetDataVersion.bump()     // otwarte sesje Glance wczytają dane od nowa
        refreshWidgetTargets(
            { NextLessonWidget().updateAll(context) },
            { DayPlanWidget().updateAll(context) },
            { SubstitutionsWidget().updateAll(context) },
            { NextLessonTileService.requestUpdate(context) }
        ).forEach { result -> result.onFailure { Log.w(TAG, "Nie udało się odświeżyć jednego z widżetów", it) } }
    }

    /** Zaplanuj odświeżenie na [at] (z ograniczeniem 1 min – 24 h, zabezpieczenie przed zmianą czasu). */
    fun scheduleTick(at: LocalDateTime): kotlinx.coroutines.Job = scope.launch(Dispatchers.IO) {
            tickMutex.lock()
            try {
                val now = AppClock.now()
                // Strefa czasowa: przy zmianie czasu (DST) różnica lokalnych godzin nie jest prawdziwym odstępem.
                val zone = java.time.ZoneId.systemDefault()
                val delay = Duration.between(now.atZone(zone), at.atZone(zone)).plusSeconds(5).coerceIn(Duration.ofMinutes(1), Duration.ofDays(1))
                val planned = System.currentTimeMillis() + delay.toMillis()
                val wm = WorkManager.getInstance(context)
                val earlier = wm.getWorkInfosForUniqueWork(WORK_TICK).get().any { info ->
                    info.state == androidx.work.WorkInfo.State.ENQUEUED && info.tags.any { tag ->
                        tag.removePrefix("tick-at:").toLongOrNull()?.let { it <= planned } == true
                    }
                }
                if (earlier) return@launch
                val request = OneTimeWorkRequestBuilder<WidgetTickWorker>().addTag("tick-at:$planned")
                    .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build()
                wm.enqueueUniqueWork(WORK_TICK, ExistingWorkPolicy.REPLACE, request).result.get()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Nie udało się zaplanować odświeżenia widżetów", e)
            } finally { tickMutex.unlock() }
        }


    companion object {
        private const val TAG = "WidgetUpdater"
        private const val WORK_TICK = "elektron_widget_tick"
    }
}

/** Jednorazowe odświeżenie widżetów w chwili dzwonka (planowane przez [WidgetUpdater.scheduleTick]). */
@HiltWorker
class WidgetTickWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val updater: WidgetUpdater
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runTickUpdate { updater.updateNow() }
        return Result.success()
    }
}

/**
 * Odświeżenie w ticku, którego nie przerwie anulowanie workera.
 *
 * updateAll() tylko zleca sesję Glance (osobna praca WorkManagera), więc provideGlance
 * pierwszego widżetu rusza, zanim ten worker odświeży kolejne. provideGlance planuje następny
 * tick przez enqueueUniqueWork(REPLACE) pod TĄ SAMĄ nazwą - REPLACE anuluje działającego
 * workera, a pozostałe widżety (i kafelek) zostawały nieodświeżone do następnego dzwonka.
 */
internal suspend fun runTickUpdate(update: suspend () -> Unit) = withContext(NonCancellable) { update() }

/** Trwałe odświeżenie po serii pushy; nie zależy od życia usługi FCM. */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val updater: WidgetUpdater
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        updater.updateNow()
        return Result.success()
    }
}

/** One broken launcher widget must not prevent updating the remaining targets. */
internal suspend fun refreshWidgetTargets(vararg targets: suspend () -> Unit): List<Result<Unit>> =
    targets.map { target -> runCatchingCancellable { target() } }
