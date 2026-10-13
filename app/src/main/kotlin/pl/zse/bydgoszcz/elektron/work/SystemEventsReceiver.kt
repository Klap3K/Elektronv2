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
import pl.zse.bydgoszcz.elektron.widget.WidgetUpdater

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SystemEventsEntryPoint {
    fun noteScheduler(): NoteReminderScheduler
    fun scheduler(): LessonReminderScheduler
    fun widgetUpdater(): WidgetUpdater
    fun dndScheduler(): pl.zse.bydgoszcz.elektron.service.AutoDndScheduler
}

/**
 * Restart telefonu, aktualizacja aplikacji (także z przycisku "Aktualizuj"), zmiana czasu lub
 * strefy: Android kasuje alarmy / zmienia się "teraz" - od razu ustawiamy przypomnienie
 * o najbliższej lekcji i odświeżamy widżety. Dawniej dopiero przy najbliższym syncu (do 15 min),
 * więc przypomnienie tuż po aktualizacji mogło przepaść.
 */
class SystemEventsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, SystemEventsEntryPoint::class.java)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Zainstalowana aktualizacja z przycisku "Aktualizuj" (wersja z GitHuba): pobrany
                // plik APK (ok. 20 MB) nie jest już potrzebny - dawniej leżał do następnego pobrania.
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) runCatching {
                    java.io.File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
                }
                // goAsync daje ok. 10 s.
                withTimeoutOrNull(8_000) {
                    pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable { ep.noteScheduler().reschedule(force = true) }
                        .onFailure { android.util.Log.w("SystemEvents", "Błąd przypomnień notatek", it) }
                    pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable { ep.scheduler().reschedule() }
                        .onFailure { android.util.Log.w("SystemEvents", "Błąd przypomnień lekcji", it) }
                    pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable { ep.dndScheduler().requestReschedule() }
                        .onFailure { android.util.Log.w("SystemEvents", "Błąd harmonogramu Auto DND", it) }
                    // updateNow (czekamy), nie requestUpdate: tamto tylko startuje korutynę
                    // i od razu wraca, więc po finish() proces mógł zostać zamknięty, zanim
                    // widżety (np. z nowymi akcjami wierszy po aktualizacji) się przerysowały.
                    ep.widgetUpdater().updateNow()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("SystemEvents", "Nie udało się odświeżyć po zdarzeniu systemowym", e) }
            finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}
