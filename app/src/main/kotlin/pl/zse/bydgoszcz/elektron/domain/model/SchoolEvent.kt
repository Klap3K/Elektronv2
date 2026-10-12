package pl.zse.bydgoszcz.elektron.domain.model

import java.time.LocalDate
import java.time.LocalTime

enum class SchoolEventType {
    EXAM,       // Sprawdzian / Praca klasowa / Test
    QUIZ,       // Kartkówka
    HOMEWORK,   // Zadanie domowe / Praca domowa
    OTHER;      // Inne wydarzenie szkolne

    val isAssessment: Boolean
        get() = this == EXAM || this == QUIZ
}

data class SchoolEvent(
    val id: String,
    val title: String,
    val description: String?,
    val type: SchoolEventType,
    val subject: String?,
    val date: LocalDate,
    val timeFrom: LocalTime?,
    val timeTo: LocalTime?,
    val location: String?,
    val rawSummary: String
) {
    /** Krótka etykieta do plakietki w planie lekcji. */
    fun badgeLabel(): String = when (type) {
        SchoolEventType.EXAM -> "Sprawdzian"
        SchoolEventType.QUIZ -> "Kartkówka"
        SchoolEventType.HOMEWORK -> "Zad. domowe"
        SchoolEventType.OTHER -> "Wydarzenie"
    }

    /** Czy wydarzenie pasuje do podanej lekcji (po przedmiocie lub godzinie). */
    fun matchesLesson(lesson: Lesson): Boolean {
        if (lesson.date != date) return false

        // Sprawdzenie po przedmiocie, jeśli jest znany
        if (subject != null) {
            val normalizedSub = normalizeSubject(subject)
            val lessonSubjects = lesson.groups.mapNotNull { it.subject }.map { normalizeSubject(it) }
            if (lessonSubjects.any { it.contains(normalizedSub) || normalizedSub.contains(it) }) {
                return true
            }
        }

        // Sprawdzenie po czasie lekcji, jeśli godziny są określone
        if (timeFrom != null && timeTo != null) {
            return !(timeTo <= lesson.timeFrom || timeFrom >= lesson.timeTo)
        }

        return false
    }

    companion object {
        fun normalizeSubject(name: String): String =
            name.lowercase()
                .replace(Regex("[^a-ząćęłńóśźż0-9]"), "")
                .trim()
    }
}
