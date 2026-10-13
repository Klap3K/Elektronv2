package pl.zse.bydgoszcz.elektron.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class PushMessageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val handler: PushMessageHandler
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        handler.handle(inputData.keyValueMap.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap())
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
