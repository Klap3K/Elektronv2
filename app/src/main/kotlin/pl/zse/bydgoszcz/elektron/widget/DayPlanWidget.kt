package pl.zse.bydgoszcz.elektron.widget

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import java.time.LocalTime
import pl.zse.bydgoszcz.elektron.domain.model.LessonClock
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.compose.runtime.LaunchedEffect
import android.util.Log
import pl.zse.bydgoszcz.elektron.domain.model.LessonTarget
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import pl.zse.bydgoszcz.elektron.R

/**
 * Widżet "Plan dnia": lekcje dzisiaj (albo najbliższego dnia z lekcjami), bieżąca lub
 * najbliższa podświetlona, minione przygaszone, zastępstwa na pomarańczowo.
 */
class DayPlanWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load: suspend () -> Pair<WidgetState, WidgetPalette> = {
            val state = WidgetDataLoader.load(context)
            widgetTickAt(state, AppClock.now())?.let {
                WidgetDataLoader.entryPoint(context).widgetUpdater().scheduleTick(it)
            }
            state to WidgetDataLoader.palette(context)
        }
        val initial = loadSnapshot(load)
        provideContent {
            val (state, palette) = rememberLiveWidgetData(initial, load) { old, fresh -> retainWidgetData(old.first, fresh.first) to fresh.second }
            CompositionLocalProvider(LocalWidgetPalette provides palette) { Content(state) }
        }
    }

    @Composable
    private fun Content(state: WidgetState) {
        WidgetContainer(target = "timetable") {
            when (state) {
                WidgetState.Failed -> WidgetMessage("Nie udało się odczytać danych. Otwórz aplikację i spróbuj ponownie.")
                WidgetState.NoClass -> WidgetMessage("Otwórz eLektron i wybierz klasę.")
                is WidgetState.NoLessons -> WidgetMessage("Brak lekcji w najbliższych dniach.")
                is WidgetState.Ready -> Plan(state)
            }
        }
    }

    @Composable
    private fun Plan(state: WidgetState.Ready) {
        Column(GlanceModifier.fillMaxSize()) {
            // Nagłówek z nazwą dnia: zakładka Plan.
            Row(
                GlanceModifier.fillMaxWidth().clickable(openSectionAction("timetable"))
                    .semantics { contentDescription = "Plan, ${state.dayLabel}. Otwórz zakładkę Plan" },
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Image(ImageProvider(R.drawable.ic_logo), contentDescription = null, modifier = GlanceModifier.size(18.dp))
                Spacer(GlanceModifier.width(6.dp))
                Text(if (state.readError) "Błąd odczytu · zapisany plan" else "Plan · ${state.dayLabel}", modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = WidgetColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                state.className?.let {
                    Text(it, style = TextStyle(color = WidgetColors.textSecondary, fontSize = 12.sp), maxLines = 1)
                }
            }
            // Dziś: lista zaczyna się od bieżącej lekcji, minione są schowane (licznik w nagłówku) -
            // mały widżet od razu pokazuje to, co ważne. Dotknięcie licznika pokazuje wcześniejsze
            // lekcje u góry listy. Launcher nie pozwala ustawić przewinięcia listy w widżecie, więc
            // minione lekcje nie mogą czekać "nad" bieżącą - stąd przełącznik zamiast przewijania.
            // Rozwinięcie dotyczy tej jednej lekcji: po przejściu do następnej lista znowu zaczyna
            // się od bieżącej.
            val startIndex = WidgetNoteLayout.dayPlanStart(state)
            val pastCount = startIndex
            val expandKey = "${state.date}|$startIndex"
            val expanded = pastCount > 0 && currentState(DayPlanEarlier.EXPANDED) == expandKey
            val visible = if (expanded) state.lessons else state.lessons.drop(startIndex)
            if (pastCount > 0) {
                Text(
                    if (expanded) "Ukryj ${pastCount} ${lessonsWord(pastCount)} za Tobą"
                    else "$pastCount ${lessonsWord(pastCount)} za Tobą · Pokaż",
                    style = TextStyle(color = WidgetColors.textSecondary, fontSize = 11.sp),
                    modifier = GlanceModifier.padding(top = 2.dp, bottom = 2.dp)
                        .clickable(actionRunCallback<DayPlanEarlier>(actionParametersOf(DayPlanEarlier.KEY to expandKey)))
                        .semantics { contentDescription = if (expanded) "Ukryj wcześniejsze lekcje" else "Pokaż wcześniejsze lekcje" }
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            val offset = state.lessons.size - visible.size
            LaunchedEffect(state.date, visible.map { it.number }) {
                Log.i(TAG, "Rysuję ${visible.size} z ${state.lessons.size} lekcji: " +
                    visible.indices.joinToString { i -> WidgetTargets.lesson(state, offset + i)?.let { "${it.date}/${it.lessonNumber}" } ?: "-" })
            }
            // Identyfikator wiersza zależy od treści, dnia i kolorów, nie tylko od numeru lekcji:
            // launcher trzyma gotowe wiersze listy pod ich identyfikatorem, więc po zmianie
            // przezroczystości, motywu, dnia (np. symulacja czasu) albo trwającej lekcji wiersze
            // o tych samych numerach zostawały w starej postaci. Tryb ciemny systemu też: w motywie
            // Auto kolory wybiera launcher, a gotowe wiersze listy zostawały jasne.
            val palette = LocalWidgetPalette.current
            val night = LocalContext.current.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val now = AppClock.time().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)   // także "zostało X min"
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(visible, itemId = { lesson ->
                    dayPlanRowId(lesson.number, java.util.Objects.hash(state.date, lesson, palette,
                        lesson.number == state.focus.number, state.isToday && lesson.timeTo <= now,
                        state.isToday && state.focusIsNow, now, night))
                }) { lesson ->
                    LessonRow(
                        lesson = lesson,
                        target = WidgetTargets.lesson(state, offset + visible.indexOf(lesson)),
                        dayLabel = state.dayLabel,
                        highlighted = lesson.number == state.focus.number,
                        // Trwająca lekcja: "zostało X min" (jak w widżecie Następna lekcja i w planie).
                        remaining = if (state.isToday && state.focusIsNow && lesson.number == state.focus.number)
                            "zostało ${LessonClock.minutesCeil(AppClock.time(), lesson.timeTo)} min" else null,
                        past = state.isToday && lesson.timeTo <= AppClock.time(),
                        visibleLessonCount = visible.size
                    )
                }
            }
        }
    }

    @Composable
    private fun LessonRow(
        lesson: WidgetLesson,
        target: LessonTarget?,
        dayLabel: String,
        highlighted: Boolean,
        past: Boolean,
        remaining: String? = null,
        visibleLessonCount: Int = 1
    ) {
        val numberColor = when {
            past -> WidgetColors.textFaded
            lesson.isSubstitution -> WidgetColors.substitution
            else -> lesson.color?.let { ColorProvider(Color(it)) } ?: WidgetColors.accent
        }
        val titleColor = when {
            past -> WidgetColors.textFaded
            lesson.isSubstitution -> WidgetColors.substitution
            highlighted -> WidgetColors.onAccentContainer
            else -> WidgetColors.textPrimary
        }
        // Tło ZAWSZE ustawione (przezroczyste bez podświetlenia). Widżety z listą ponownie używają
        // widoków wierszy — tło ustawiane tylko warunkowo zostawało na innych lekcjach
        // ("losowe" niebieskie tła).
        val rowBg = when {
            !highlighted -> WidgetColors.transparent
            lesson.isSubstitution -> WidgetColors.substitutionContainer
            else -> WidgetColors.accentContainer
        }
        val rowModifier = GlanceModifier.fillMaxWidth().background(rowBg).cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp)
        // Dotknięcie lekcji: Plan na ten dzień i szczegóły tej lekcji.
        val description = listOfNotNull("$dayLabel, lekcja ${lesson.number}", lesson.title, lesson.timeRange,
            lesson.room?.let { "sala $it" }, lesson.note, lesson.examSummary?.let { "Sprawdzian: $it" }, lesson.userNote?.let { "Notatka: $it" }).joinToString(", ") + ". Otwórz szczegóły lekcji"
        val action = target?.let { openLessonAction(LocalContext.current, it) } ?: openAppAction()
        Column(
            GlanceModifier.fillMaxWidth().padding(bottom = 2.dp).clickable(action)
                .semantics { contentDescription = description }
        ) {
            Row(rowModifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text("${lesson.number}", modifier = GlanceModifier.width(22.dp),
                    style = TextStyle(color = numberColor, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Column(GlanceModifier.defaultWeight()) {
                    Text(lesson.title, style = TextStyle(color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    val meta = listOfNotNull(remaining ?: lesson.timeRange, lesson.room?.let { "s. $it" }).joinToString(" · ")
                    Text(meta, style = TextStyle(color = if (past) WidgetColors.textFaded else WidgetColors.textSecondary, fontSize = 11.sp), maxLines = 1)
                    lesson.examSummary?.let {
                        val icon = if (it.contains("Kartk", ignoreCase = true)) "📄 " else "📝 "
                        Text("$icon$it", style = TextStyle(color = WidgetColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                    lesson.note?.let {
                        Text(it, style = TextStyle(color = WidgetColors.substitution, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                    val scale = LocalContext.current.resources.configuration.fontScale
                    val lines = WidgetNoteLayout.dayPlanLines(LocalSize.current.height.value, visibleLessonCount, lesson.note != null || lesson.examSummary != null, scale)
                    if (lines > 0) lesson.userNote?.let {
                        Text("Notatka: $it", style = TextStyle(color = WidgetColors.textPrimary, fontSize = 11.sp), maxLines = lines)
                    }
                }
            }
        }
    }
}

/** Polska odmiana: 1 lekcja, 2-4 lekcje, 5+ lekcji (12-14 lekcji). */
internal fun lessonsWord(n: Int): String = when {
    n == 1 -> "lekcja"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "lekcje"
    else -> "lekcji"
}

private const val TAG = "DayPlanWidget"

/** Pokaż / ukryj minione dziś lekcje w widżecie Plan dnia (stan zapisany przy tym widżecie). */
class DayPlanEarlier : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val key = parameters[KEY] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            if (prefs[EXPANDED] == key) prefs.remove(EXPANDED) else prefs[EXPANDED] = key
        }
        DayPlanWidget().update(context, glanceId)
    }

    companion object {
        val KEY = ActionParameters.Key<String>("day_plan_earlier")
        val EXPANDED = stringPreferencesKey("day_plan_expanded")
    }
}

class DayPlanWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DayPlanWidget()
}
