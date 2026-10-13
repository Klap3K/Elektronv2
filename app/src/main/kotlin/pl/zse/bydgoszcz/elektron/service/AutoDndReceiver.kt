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
interface AutoDndEntryPoint {
    fun dndScheduler(): AutoDndScheduler
}

/**
 * Odbiornik alarmu systemowego przełączenia trybu Auto DND na dzwonku (początek lub koniec lekcji).
 * Działa w tle nawet przy zablokowanym telefonie w kieszeni.
 */
class AutoDndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, AutoDndEntryPoint::class.java)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(8_000) {
                    ep.dndScheduler().evaluateAndSchedule()
                }
            } catch (e: Exception) {
                android.util.Log.w("AutoDndReceiver", "Błąd przełączenia Auto DND", e)
            } finally {
                pending.finish()
            }
        }
    }
}
