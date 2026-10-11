package pl.zse.bydgoszcz.elektron.presentation.timetable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.zse.bydgoszcz.elektron.domain.model.JointGroups
import pl.zse.bydgoszcz.elektron.domain.model.Lesson
import pl.zse.bydgoszcz.elektron.domain.model.LessonNote
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.model.SubjectStyles
import pl.zse.bydgoszcz.elektron.domain.model.SubstitutionDisplay
import pl.zse.bydgoszcz.elektron.presentation.common.LocalPersonalization
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Szczegóły lekcji po dotknięciu (widok tygodnia i dnia): tylko dane, które aplikacja ma ze
 * strony szkoły - dzień, godziny, przedmiot, grupa, sala, nauczyciel, zastępstwo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LessonDetailsSheet(lesson: Lesson, userNote: LessonNote? = null, onEditNote: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val personal = LocalPersonalization.current
    val pl = Locale("pl", "PL")
    val sub = lesson.substitution
    val title = lesson.groups.mapNotNull { personal.subjectName(it.subject) }.distinct().joinToString(" / ")
        .ifBlank { lesson.note ?: "Lekcja" }
    val dayName = lesson.date.dayOfWeek.getDisplayName(TextStyle.FULL, pl)

    // Od razu cała treść (bez stanu "do połowy"): przy zastępstwie okno jest dłuższe i dawniej
    // trzeba było je ręcznie rozwijać. Gdy treść się nie mieści (mały ekran, duża czcionka,
    // poziomo), okno sięga do góry ekranu, a treść przewija się w środku.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding()
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            Field("Dzień", "$dayName, ${lesson.date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
            Field("Lekcja", "${lesson.number}. lekcja · ${lesson.timeFrom}–${lesson.timeTo}")

            lesson.groups.forEachIndexed { i, g ->
                if (lesson.groups.size > 1) {
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(personal.subjectName(g.subject) ?: "Grupa ${i + 1}",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                val key = SubjectStyles.key(g.subject)
                // Własna nazwa przedmiotu - pokazujemy też nazwę ze strony szkoły.
                if (key != null && personal.subjectName(g.subject) != key) Field("Na stronie szkoły", key)
                LessonGroups.parse(g.subject)?.label?.let { Field("Grupa", LessonGroups.displayLabel(it)) }
                g.room?.let { Field("Sala", it) }
                (g.teacherFullName ?: g.teacherCode ?: JointGroups.describe(g.classRef, lesson.className))
                    ?.let { Field("Nauczyciel", it) }
            }

            if (sub != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    if (SubstitutionDisplay.freesLesson(sub)) "Lekcja się nie odbywa" else "Zastępstwo",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Field("Zmiana", SubstitutionDisplay.headline(sub), MaterialTheme.colorScheme.tertiary)
                SubstitutionDisplay.place(sub)?.let { Field("Gdzie", it) }
                Field("Za nauczyciela", sub.originalTeacher)
                SubstitutionDisplay.notes(sub)?.let { Field("Uwagi", it) }
            }
            userNote?.let {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Field("Twoja notatka", it.text)
                if (it.subject != LessonNote.subject(lesson))
                    Field("Zapisana dla", it.subject, MaterialTheme.colorScheme.tertiary)
            }
            onEditNote?.let { action ->
                androidx.compose.material3.TextButton(onClick = action) { Text(if (userNote == null) "Dodaj notatkę" else "Edytuj notatkę") }
            }
            lesson.note?.takeIf { sub == null && lesson.groups.isNotEmpty() }?.let { Field("Uwaga", it) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
    }
}
