package pl.zse.bydgoszcz.elektron.widget

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import pl.zse.bydgoszcz.elektron.domain.model.LessonClock
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.ElektronActivity
import pl.zse.bydgoszcz.elektron.work.LocalNotificationSink
import java.time.Duration
import java.time.LocalTime

/**
 * Kafelek w szybkich ustawieniach Androida: najbliższa lekcja widoczna po rozwinięciu
 * paska powiadomień. Dane jak w widżetach (WidgetDataLoader: grupy, zastępstwa).
 * W trakcie lekcji kafelek jest aktywny (podświetlony). Dotknięcie otwiera Plan lekcji.
 */
class NextLessonTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        loadJob?.cancel()
        loadJob = scope.launch {
            val state = runCatchingCancellable { WidgetDataLoader.load(applicationContext) }
                .onFailure { Log.w(TAG, "Nie udało się wczytać danych kafelka", it) }
                .getOrNull()
            render(state)
        }
    }

    override fun onStopListening() {
        loadJob?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: WidgetState?) {
        val tile = qsTile ?: return
        when (state) {
            is WidgetState.Ready -> {
                val lesson = state.focus
                val now = AppClock.time()
                tile.label = "${lesson.number}. ${lesson.title}"
                val sub = when {
                    state.focusIsNow ->
                        "Teraz · zostało ${LessonClock.minutesCeil(now, lesson.timeTo)} min"
                    state.breakFrom != null ->
                        "Przerwa · lekcja za ${LessonClock.minutesCeil(now, lesson.timeFrom)} min"
                    state.isToday && now <= lesson.timeFrom &&
                        LessonClock.minutesCeil(now, lesson.timeFrom) <= LessonClock.COUNTDOWN_MINUTES ->
                        "Za ${LessonClock.minutesCeil(now, lesson.timeFrom)} min" + (lesson.room?.let { " · s. $it" } ?: "")
                    state.isToday -> "Dziś ${lesson.timeFrom}" + (lesson.room?.let { " · s. $it" } ?: "")
                    else -> "${state.dayLabel} ${lesson.timeFrom}" + (lesson.room?.let { " · s. $it" } ?: "")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = sub
                else tile.label = "${lesson.number}. ${lesson.title} · $sub"
                tile.contentDescription = "Najbliższa lekcja: ${tile.label}, $sub"
                tile.state = if (state.focusIsNow) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            }
            WidgetState.Failed -> {
                tile.label = "Elektron-Vulcan"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Błąd odczytu danych"
                tile.state = Tile.STATE_INACTIVE
            }
            WidgetState.NoClass -> {
                tile.label = "Elektron-Vulcan"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Wybierz klasę"
                tile.state = Tile.STATE_INACTIVE
            }
            else -> {
                tile.label = "Elektron-Vulcan"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Brak lekcji"
                tile.state = Tile.STATE_INACTIVE
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ElektronActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(LocalNotificationSink.EXTRA_DEEP_LINK, "timetable")
        }
        // Android 14+: tylko przez PendingIntent (wersja z Intent rzuca wyjątek).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "NextLessonTile"

        /** Poproś system o odświeżenie kafelka (wywoływane razem z odświeżaniem widżetów). */
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, NextLessonTileService::class.java))
            }
        }
    }
}
