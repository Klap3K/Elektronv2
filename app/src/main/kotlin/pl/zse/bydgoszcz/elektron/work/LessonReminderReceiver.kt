package pl.zse.bydgoszcz.elektron.work

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
import pl.zse.bydgoszcz.elektron.domain.model.LessonReminders
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun scheduler(): LessonReminderScheduler
    fun notifications(): LocalNotificationSink
}

/** Alarm przypomnienia: pokazuje powiadomienie i ustawia następne przypomnienie. */
class LessonReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // goAsync daje ok. 10 s - dłużej system uznałby odbiornik za zawieszony.
                withTimeoutOrNull(8_000) { handle(ep, intent) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LessonReminder", "Nie udało się sprawdzić przypomnienia", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(ep: ReminderEntryPoint, intent: Intent) {
        val now = LocalDateTime.now()
        val startMs = intent.getLongExtra(LessonReminderScheduler.EXTRA_START, 0L)
        if (startMs > 0) {
            val zone = ZoneId.systemDefault()
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), zone)
            val number = intent.getIntExtra(LessonReminderScheduler.EXTRA_NUMBER, -1)
            val delivery = ep.scheduler().delivery(
                intent.getStringExtra(LessonReminderScheduler.EXTRA_CLASS), number, start, now
            )
            // Przed przeliczeniem: spóźniony alarm zostaje w kolejce tylko do chwili, gdy przyjdzie.
            ep.scheduler().markHandled(number, start)
            if (delivery != null) ep.notifications().postReminder(
                id = delivery.id, title = delivery.title, body = delivery.body,
                timeoutMs = (delivery.end.atZone(zone).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(60_000L)
            )
        }
        ep.scheduler().reschedule(now)
    }
}
