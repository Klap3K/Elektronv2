package pl.zse.bydgoszcz.elektron.domain.model

import java.time.LocalDateTime

enum class ReminderMode(val key: String) {
    OFF("off"),
    /** Tylko przed pierwszą lekcją dnia. */
    FIRST("first"),
    /** Przed każdą lekcją. */
    EVERY("every");

    companion object {
        fun fromKey(key: String?): ReminderMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

data class ReminderSettings(val mode: ReminderMode = ReminderMode.OFF, val minutesBefore: Int = 10)

data class LessonReminder(val lesson: Lesson, val at: LocalDateTime) {
    val lessonStart: LocalDateTime get() = LocalDateTime.of(lesson.date, lesson.timeFrom)
}

/** Wyznaczanie najbliższego przypomnienia - czysta logika (testy w LessonRemindersTest). */
object LessonReminders {

    val MINUTE_OPTIONS = listOf(5, 10, 15, 30)

    /** Jak długo po początku lekcji spóźnione przypomnienie jest jeszcze pokazywane. */
    private const val DELIVERY_GRACE_MINUTES = 5L

    /**
     * Najbliższe przypomnienie po [now]. Lekcje powinny być już po filtrze grup. Lekcje, z których
     * klasa jest zwolniona ("Uczniowie przychodzą później"), są pomijane - przypomnienie "przed
     * pierwszą lekcją" dotyczy pierwszej lekcji, która się odbywa.
     * Przy "przed każdą lekcją" przypomnienie nie wypada w trakcie poprzedniej lekcji -
     * przesuwa się na początek przerwy (np. 10 min przed, a przerwa ma 5 min).
     */
    fun next(lessons: List<Lesson>, now: LocalDateTime, settings: ReminderSettings, handled: String? = null): LessonReminder? {
        return candidates(lessons, settings).filter {
            (it.at > now && it.lessonStart > now) ||
                // Spóźniony alarm (niedokładne alarmy, Doze), który jeszcze nie przyszedł: zostaje
                // w kolejce, dopóki [due] by go pokazało. Dawniej przeliczenie po synchronizacji
                // zastępowało go następnym przypomnieniem i uczeń nie dostawał żadnego.
                (it.at <= now && now < it.lessonStart.plusMinutes(DELIVERY_GRACE_MINUTES) && key(it) != handled)
        }.minByOrNull { it.at }
    }

    /** Identyfikator przypomnienia (numer lekcji i jej początek) - zapamiętywany po obsłużeniu alarmu. */
    fun key(number: Int, start: LocalDateTime): String = "$number@$start"
    fun key(reminder: LessonReminder): String = key(reminder.lesson.number, reminder.lessonStart)

    /** Ponowna weryfikacja alarmu: bieżący plan i ustawienia mają pierwszeństwo przed zapisanym alarmem. */
    fun due(lessons: List<Lesson>, number: Int, start: LocalDateTime, now: LocalDateTime, settings: ReminderSettings): Lesson? =
        candidates(lessons, settings).firstOrNull {
            it.lesson.number == number && it.lessonStart == start && it.at <= now && now < start.plusMinutes(DELIVERY_GRACE_MINUTES)
        }?.lesson

    private fun candidates(lessons: List<Lesson>, settings: ReminderSettings): List<LessonReminder> {
        if (settings.mode == ReminderMode.OFF) return emptyList()
        val minutes = settings.minutesBefore.toLong().coerceIn(1, 120)
        return lessons.filter(SubstitutionDisplay::takesPlace).groupBy { it.date }.flatMap { (date, dayLessons) ->
            val sorted = dayLessons.sortedBy { it.timeFrom }
            val targets = if (settings.mode == ReminderMode.FIRST) sorted.take(1) else sorted
            targets.mapIndexed { i, l ->
                var at = LocalDateTime.of(date, l.timeFrom).minusMinutes(minutes)
                if (i > 0) {
                    val prevEnd = LocalDateTime.of(date, sorted[i - 1].timeTo)
                    if (at < prevEnd) at = prevEnd
                }
                LessonReminder(l, at)
            }
        }
    }

    /**
     * Treść przypomnienia: (co, szczegóły). "Co" trafia do tytułu ("Za 10 min: matematyka"),
     * szczegóły to numer i godziny lekcji, sala i nauczyciel albo dane zastępstwa.
     */
    fun describe(lesson: Lesson, styles: Map<String, SubjectStyle>): Pair<String, String> {
        val hours = "${lesson.number}. lekcja, ${lesson.timeFrom}–${lesson.timeTo}"
        val sub = lesson.substitution
        if (sub != null) {
            return "zastępstwo – ${SubstitutionDisplay.headline(sub)}" to
                listOfNotNull(hours, SubstitutionDisplay.place(sub), SubstitutionDisplay.notes(sub)).joinToString(" · ")
        }
        val what = lesson.groups.mapNotNull { SubjectStyles.displayName(it.subject, styles) }
            .distinct().joinToString(" / ").ifBlank { lesson.note ?: "lekcja" }
        val rooms = lesson.groups.mapNotNull { it.room }.distinct().joinToString(", ").ifBlank { null }
        val teacher = lesson.groups.firstOrNull()
            ?.let { it.teacherFullName ?: it.teacherCode ?: JointGroups.describe(it.classRef, lesson.className) }
        return what to listOfNotNull(hours, rooms?.let { "s. $it" }, teacher).joinToString(" · ")
    }

    /** Tytuł powiadomienia liczony w chwili wyświetlenia (alarm bywa opóźniony). */
    fun title(what: String, lessonStart: LocalDateTime, now: LocalDateTime): String {
        val seconds = java.time.Duration.between(now, lessonStart).seconds
        return if (seconds <= 30) "Teraz: $what"
        else "Za ${(seconds + 59) / 60} min: $what"
    }
}
