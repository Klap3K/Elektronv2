package pl.zse.bydgoszcz.elektron.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import pl.zse.bydgoszcz.elektron.domain.model.SyncErrors
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator
import pl.zse.bydgoszcz.elektron.domain.sync.SyncRequest

/**
 * Cykliczna synchronizacja w tle (co 15 min). Całą pracę - pobieranie, komunikaty, powiadomienia,
 * przypomnienia, widżety - wykonuje SyncCoordinator; tu tylko polityka ponowień WorkManagera.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SyncCoordinator,
    private val notificationsRepo: NotificationsRepository,
    private val updateRepo: pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository,
    private val dndScheduler: pl.zse.bydgoszcz.elektron.service.AutoDndScheduler
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        try {
            val request = if (inputData.getString("source") == pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome.SUBSTITUTIONS)
                SyncRequest.of(pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome.SUBSTITUTIONS)
            else SyncRequest.full(background = inputData.getBoolean("background", false))
            val outcome = coordinator.sync(request)
            // Przerwane przez zmianę klasy / reset: dane pobiera właśnie synchronizacja nowej klasy.
            if (outcome.interrupted) return Result.success()
            if (outcome.shouldRetry) {
                // Bug audytu #9: bez limitu Result.retry() ponawiał się w nieskończoność
                // przy dłuższej awarii strony szkoły. Po MAX_RETRY_ATTEMPTS oddajemy
                // kontrolę cyklicznemu harmonogramowi (za 15 min) zamiast dobijać się dalej.
                return if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.failure() else Result.retry()
            }
            notificationsRepo.pruneHistory()

            // Automatyczne sprawdzanie nowej wersji aplikacji w tle (co 12h, bezpieczne dzięki ETag/cache)
            pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable {
                updateRepo.check(force = false)
            }

            // Automatyczne zarządzanie wyciszaniem telefonu na lekcjach (Auto DND)
            pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable {
                dndScheduler.evaluateAndSchedule()
            }

            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SyncWorker wyjątek", e)
            pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable {
                notificationsRepo.setLastSyncError(SyncErrors.userMessage(e))
            }
            return if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}
