package pl.zse.bydgoszcz.elektron.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveBellEntryPoint {
    fun liveBellScheduler(): LiveBellScheduler
}

/**
 * Odbiornik alarmu systemowego przejścia stanu dzwonka (początek/koniec lekcji).
 * Aktualizuje stan powiadomienia i planuje kolejny dzwonek.
 */
class LiveBellReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, LiveBellEntryPoint::class.java)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(8_000) {
                    ep.liveBellScheduler().evaluateAndSchedule()
                }
            } catch (e: Exception) {
                android.util.Log.w("LiveBellReceiver", "Błąd aktualizacji Live Bell", e)
            } finally {
                pending.finish()
            }
        }
    }
}
