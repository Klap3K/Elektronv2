package pl.zse.bydgoszcz.elektron.presentation.timetable

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import pl.zse.bydgoszcz.elektron.domain.model.LessonNote
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Int * Dp (minuty * wysokość minuty) - operator rozszerzający, wymaga importu.
import androidx.compose.ui.unit.times
import pl.zse.bydgoszcz.elektron.domain.model.Lesson
import pl.zse.bydgoszcz.elektron.domain.model.SubstitutionDisplay
import pl.zse.bydgoszcz.elektron.presentation.common.LocalPersonalization
import pl.zse.bydgoszcz.elektron.presentation.common.rememberNow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max

/** Wysokość minuty lekcji w siatce: 45 min = ok. 52 dp (3 linie nazwy przedmiotu). */
private val PER_MINUTE: Dp = 1.15.dp
private val TIME_COLUMN: Dp = 42.dp
private val DAY_SHORT = mapOf(
    DayOfWeek.MONDAY to "pon", DayOfWeek.TUESDAY to "wt", DayOfWeek.WEDNESDAY to "śr",
    DayOfWeek.THURSDAY to "czw", DayOfWeek.FRIDAY to "pt"
)
private val MONTH_SHORT = listOf("STY", "LUT", "MAR", "KWI", "MAJ", "CZE", "LIP", "SIE", "WRZ", "PAŹ", "LIS", "GRU")

/**
 * Plan tygodnia w układzie siatki (jak w eduVulcan): po lewej godziny, 5 kolumn dni, lekcje
 * rozmieszczone według godzin (okienka i przerwy widać jako puste miejsca). W kafelku tylko
 * nazwa przedmiotu - szczegóły po dotknięciu ([onLessonClick]). Przewijana w pionie.
 */
@Composable
internal fun WeekGrid(
    week: List<TimetableViewModel.DayColumn>,
    modifier: Modifier = Modifier,
    notes: Map<String, LessonNote>,
    onNote: (Lesson) -> Unit,
    onLessonClick: (Lesson) -> Unit,
    focusedDay: LocalDate? = null,
    openingId: Int? = null
) {
    val days = remember(week) { week.filter { it.dayOfWeek in DAY_SHORT.keys }.sortedBy { it.date } }
    val now = rememberNow().value
    val today = now.toLocalDate()
    val selectedDay = WeekOpening.selectedDay(days.map { it.date }, focusedDay, today)
    val all = remember(days) { days.flatMap { it.lessons } }
    // Zakres godzin siatki: od pełnej godziny przed pierwszą lekcją do pełnej godziny po ostatniej.
    val startMin = (all.minOfOrNull { it.timeFrom.hour } ?: 8) * 60
    val endMin = ((all.maxOfOrNull { it.timeTo.toSecondOfDay() / 60 } ?: (15 * 60)) + 59) / 60 * 60
    val totalMin = max(endMin - startMin, 60)
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // The anchor selected by the daily opening rule also controls the week view.
    // After classes, tomorrow opens at its first lesson, not at today's late hour.
    LaunchedEffect(days.firstOrNull()?.date, selectedDay, openingId, startMin, endMin) {
        val minute = WeekOpening.startMinute(selectedDay, all, now) ?: startMin
        val y = with(density) { ((minute - startMin) * PER_MINUTE - 90.dp).toPx() }
        withTimeoutOrNull(1_000) { snapshotFlow { scroll.maxValue }.first { it > 0 } }
        scroll.scrollTo(y.toInt().coerceIn(0, scroll.maxValue))
    }

    Column(modifier) {
        WeekHeader(days, selectedDay)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Margines pod siatką - ostatnią lekcję da się przewinąć nad pasek nawigacji.
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(bottom = 24.dp)) {
            TimeColumn(startMin, endMin)
            days.forEach { day ->
                DayGridColumn(
                    day = day, startMin = startMin, totalMin = totalMin,
                    nowMinute = if (day.date == today) now.toLocalTime().toSecondOfDay() / 60 else null,
                    nowTime = now.toLocalTime(),
                    onLessonClick = onLessonClick,
                    notes = notes, onNote = onNote,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WeekHeader(days: List<TimetableViewModel.DayColumn>, selectedDay: LocalDate?) {
    val months = remember(days) { days.map { it.date.monthValue }.distinct() }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            months.joinToString("/\n") { MONTH_SHORT[it - 1] },
            modifier = Modifier.width(TIME_COLUMN),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        days.forEach { day ->
            val isToday = day.date == selectedDay
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(DAY_SHORT[day.dayOfWeek].orEmpty(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Box(
                    Modifier.padding(top = 2.dp).size(32.dp).clip(CircleShape)
                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${day.date.dayOfMonth}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeColumn(startMin: Int, endMin: Int) {
    Box(Modifier.width(TIME_COLUMN).height((endMin - startMin) * PER_MINUTE)) {
        for (hourMin in startMin until endMin step 60) {
            Text(
                "%02d:00".format(hourMin / 60),
                modifier = Modifier.offset(y = ((hourMin - startMin) * PER_MINUTE + 2.dp)).fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DayGridColumn(
    day: TimetableViewModel.DayColumn,
    startMin: Int,
    totalMin: Int,
    nowMinute: Int?,
    nowTime: LocalTime,
    onLessonClick: (Lesson) -> Unit,
    notes: Map<String, LessonNote>, onNote: (Lesson) -> Unit,
    modifier: Modifier
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.primary
    // Dzisiejsza kolumna - delikatne tło w kolorze akcentu.
    val todayTint = if (nowMinute != null) nowColor.copy(alpha = 0.06f) else Color.Transparent
    Box(
        modifier.height(totalMin * PER_MINUTE).background(todayTint).drawBehind {
            // Pionowa linia kolumny i poziome linie pełnych godzin.
            drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), 1f)
            var m = 0
            while (m <= totalMin) {
                val y = (m * PER_MINUTE).toPx()
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1f)
                m += 60
            }
        }
    ) {
        day.lessons.forEach { lesson ->
            val from = (lesson.timeFrom.toSecondOfDay() / 60 - startMin).coerceAtLeast(0)
            // Błędne godziny na stronie szkoły (koniec przed początkiem) dawałyby ujemną wysokość
            // kafelka - wyjątek w Compose i zamknięcie aplikacji. Najmniej 15 min.
            val length = ((lesson.timeTo.toSecondOfDay() - lesson.timeFrom.toSecondOfDay()) / 60).coerceAtLeast(15)
            LessonTile(
                lesson = lesson,
                lengthMin = length,
                ongoing = nowMinute != null && nowTime >= lesson.timeFrom && nowTime < lesson.timeTo,
                modifier = Modifier.offset(y = from * PER_MINUTE).height(length * PER_MINUTE).fillMaxWidth(),
                hasNote = notes.containsKey(LessonNote.key(lesson)),
                onLongClick = if (LessonNote.canEdit(lesson, AppClock.now()) || notes.containsKey(LessonNote.key(lesson))) ({ onNote(lesson) }) else null,
                onClick = { onLessonClick(lesson) }
            )
        }
        // Linia aktualnej godziny (dzisiejsza kolumna).
        if (nowMinute != null && nowMinute in startMin..(startMin + totalMin)) {
            Box(
                Modifier.offset(y = (nowMinute - startMin) * PER_MINUTE - 1.dp).fillMaxWidth().height(2.dp)
                    .background(nowColor)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LessonTile(lesson: Lesson, lengthMin: Int, ongoing: Boolean, modifier: Modifier, hasNote: Boolean, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    // Ile linii nazwy mieści kafelek (14 sp na linię, 6 dp marginesów) - wielokropek działa
    // tylko z limitem linii, a nie z ograniczoną wysokością.
    val fontScale = LocalDensity.current.fontScale   // większa czcionka systemowa = wyższe linie
    val lines = ((lengthMin * PER_MINUTE.value - 6f) / (14f * fontScale)).toInt().coerceAtLeast(1)
    val personal = LocalPersonalization.current
    val sub = lesson.substitution
    val names = lesson.groups.mapNotNull { personal.subjectName(it.subject) }.distinct()
    val name = names.firstOrNull() ?: lesson.note ?: "Lekcja"
    val more = (names.size - 1).takeIf { it > 0 }
    val scheme = MaterialTheme.colorScheme
    val subjectColor: Color? = personal.subjectColor(lesson.groups.firstOrNull()?.subject)
    // Bez rozpakowywania Triple<Color>: Color ma własne component1..4 (kanały koloru).
    // Kolor akcentu (Ustawienia -> Wygląd): delikatny odcień tła i pasek (dawniej szary).
    val bg: Color = when {
        sub != null -> scheme.tertiaryContainer
        ongoing -> scheme.primaryContainer
        else -> lerp(scheme.surfaceContainerHigh, scheme.primary, 0.10f)
    }
    val fg: Color = when {
        sub != null -> scheme.onTertiaryContainer
        ongoing -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    val bar: Color = when {
        sub != null -> scheme.tertiary
        else -> subjectColor ?: scheme.primary
    }
    val description = buildString {
        append("${lesson.number}. lekcja, ${lesson.timeFrom}–${lesson.timeTo}, ")
        append(names.joinToString(" / ").ifBlank { name })
        if (hasNote) append(", zapisana notatka")
        if (sub != null) append(", zastępstwo: ${SubstitutionDisplay.headline(sub)}")
    }
    Row(
        modifier.padding(horizontal = 2.dp, vertical = 1.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Dodaj lub edytuj notatkę").semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar))
        Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp)) {
            // Zastępstwo: zamiast samej strzałki - co się dzieje (informacja szkoły albo zastępca) i sala.
            val tileText = if (sub != null) listOfNotNull(SubstitutionDisplay.headline(sub), SubstitutionDisplay.place(sub)).joinToString(" · ")
                else name
            Text(
                listOfNotNull(if (hasNote) "✎" else null, tileText).joinToString(" "),
                color = fg,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    // Dzielenie wyrazów ("Infor-matyka") zamiast ucinania w połowie słowa.
                    hyphens = Hyphens.Auto,
                    lineBreak = LineBreak.Paragraph
                )
            )
            if (more != null) {
                Text("+$more", color = fg, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}
