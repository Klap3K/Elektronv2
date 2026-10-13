package pl.zse.bydgoszcz.elektron.work

import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Persist the payload before returning; never perform network I/O in the FCM callback. */
class ElektronFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] !in setOf("substitution", "announcement")) return
        try {
            val data = Data.Builder().apply {
                message.data.forEach { (key, value) -> putString(key, value) }
            }.build()
            WorkManager.getInstance(this).enqueue(
                OneTimeWorkRequestBuilder<PushMessageWorker>().setInputData(data).build()
            )
        } catch (e: IllegalStateException) {
            Log.w("ElektronFcmService", "Nie udało się zapisać powiadomienia push")
        }
    }
}
