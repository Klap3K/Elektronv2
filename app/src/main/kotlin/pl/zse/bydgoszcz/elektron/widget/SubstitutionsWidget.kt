package pl.zse.bydgoszcz.elektron.widget

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import pl.zse.bydgoszcz.elektron.R

/**
 * Widżet "Zastępstwa": nadchodzące zastępstwa klasy i grup użytkownika.
 * Dotknięcie wiersza otwiera tę lekcję w planie (szczegóły), reszty widżetu - stronę główną.
 * Odświeżany po każdym syncu i pushu.
 */
class SubstitutionsWidget : GlanceAppWidget() {

    // Exact: LocalSize to faktyczny rozmiar widżetu (w Responsive byłby tylko jeden z progów),
    // a od wysokości zależy, ile wierszy się zmieści.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val load: suspend () -> Pair<SubsWidgetState, WidgetPalette> = {
            val state = WidgetDataLoader.loadSubstitutions(context)
            subsWidgetTickAt(state, AppClock.now())?.let {
                WidgetDataLoader.entryPoint(context).widgetUpdater().scheduleTick(it)
            }
            state to WidgetDataLoader.palette(context)
        }
        val initial = loadSnapshot(load)
        provideContent {
            val (state, palette) = rememberLiveWidgetData(initial, load) { old, fresh -> retainSubsWidgetData(old.first, fresh.first) to fresh.second }
            CompositionLocalProvider(LocalWidgetPalette provides palette) { Content(state) }
        }
    }

    @Composable
    private fun Content(state: SubsWidgetState) {
        WidgetContainer(target = "substitutions") {
            when (state) {
                SubsWidgetState.Failed -> WidgetMessage("Nie udało się odczytać danych. Otwórz aplikację i spróbuj ponownie.")
                SubsWidgetState.NoClass -> WidgetMessage("Otwórz Elektron-Vulcan i wybierz klasę.")
                is SubsWidgetState.Ready -> Subs(state)
            }
        }
    }

    @Composable
    private fun Subs(state: SubsWidgetState.Ready) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_logo), contentDescription = null, modifier = GlanceModifier.size(18.dp))
                Spacer(GlanceModifier.width(6.dp))
                Text(if (state.readError) "Błąd odczytu · zapisane dane" else "Zastępstwa", modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = WidgetColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                state.className?.let {
                    Text(it, style = TextStyle(color = WidgetColors.textSecondary, fontSize = 12.sp), maxLines = 1)
                }
            }
            // Stare dane: kiedy ostatnio udało się sprawdzić (bez tego "Brak zastępstw" wyglądało na aktualne).
            state.checked?.let {
                Text(it, style = TextStyle(color = WidgetColors.textSecondary, fontSize = 11.sp), maxLines = 1,
                    modifier = GlanceModifier.padding(top = 2.dp))
            }
            Spacer(GlanceModifier.height(8.dp))
            if (state.items.isEmpty()) {
                Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    Text("Brak zastępstw dla Twojej klasy.",
                        style = TextStyle(color = WidgetColors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center))
                }
            } else {
                // Zwykła kolumna zamiast LazyColumn: na Androidzie 17 (Glance 1.1.0) kliknięcia
                // wierszy listy nie docierały do aplikacji - trafiały w tło widżetu (strona
                // główna). Zwykłe elementy mają własne PendingIntenty.
                val fontScale = LocalContext.current.resources.configuration.fontScale
                val height = LocalSize.current.height.value -
                    (if (state.checked != null) 16f * fontScale.coerceAtLeast(1f) else 0f)
                val rows = WidgetNoteLayout.substitutions(height, state.items, fontScale)
                val shown = state.items.take(rows.shown)
                LaunchedEffect(state.items, rows) {
                    Log.i(TAG, "Rysuję ${shown.size} z ${state.items.size} wierszy (+${rows.more} więcej, " +
                        "wysokość ${height.toInt()} dp): ${shown.joinToString { "${it.target.date}/${it.target.lessonNumber}" }}")
                }
                Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    shown.forEachIndexed { index, sub -> SubRow(sub, rows.noteLines[index]) }
                    if (rows.more > 0) MoreRow(rows.more)
                }
            }
        }
    }

    /** "+N więcej" - otwiera zakładkę Zastępstwa. */
    @Composable
    private fun MoreRow(more: Int) {
        Box(
            GlanceModifier.fillMaxWidth().height(32.dp)
                .clickable(openSectionAction("substitutions"))
                .semantics { contentDescription = "Jeszcze $more. Otwórz zakładkę Zastępstwa" },
            contentAlignment = Alignment.Center
        ) {
            Text("+$more więcej", style = TextStyle(color = WidgetColors.substitution, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }

    @Composable
    private fun SubRow(sub: WidgetSubstitution, noteLines: Int) {
        // Dotknięcie: plan na ten dzień i szczegóły lekcji. Opis dla czytnika ekranu zawiera
        // treść wiersza (opis kontenera zastępuje odczyt tekstów w środku).
        val description = buildString {
            append("Lekcja ${sub.lessonNumber}, ${sub.title}, ${sub.dayLabel}, ${sub.detail}")
            sub.note?.let { append(", $it") }
            sub.userNote?.let { append(", Notatka: $it") }
            append(". Otwórz szczegóły lekcji")
        }
        Column(
            GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)
                .clickable(openLessonAction(LocalContext.current, sub.target))
                .semantics { contentDescription = description }
        ) {
            Row(
                // Wyższy wiersz (7 dp) - obszar dotyku ok. 48 dp.
                GlanceModifier.fillMaxWidth().background(WidgetColors.substitutionContainer).cornerRadius(10.dp)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text("${sub.lessonNumber}", modifier = GlanceModifier.width(22.dp),
                    style = TextStyle(color = WidgetColors.substitution, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Column(GlanceModifier.defaultWeight()) {
                    Text(sub.title, style = TextStyle(color = WidgetColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Text("${sub.dayLabel} · ${sub.detail}",
                        style = TextStyle(color = WidgetColors.textSecondary, fontSize = 11.sp), maxLines = 1)
                    sub.note?.let {
                        Text(it, style = TextStyle(color = WidgetColors.substitution, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                    if (noteLines > 0) sub.userNote?.let {
                        Text("Notatka: $it", style = TextStyle(color = WidgetColors.textPrimary, fontSize = 11.sp), maxLines = noteLines)
                    }
                }
            }
        }
    }
}

private const val TAG = "SubstitutionsWidget"

class SubstitutionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SubstitutionsWidget()
}
