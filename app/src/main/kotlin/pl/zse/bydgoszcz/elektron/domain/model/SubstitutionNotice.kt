package pl.zse.bydgoszcz.elektron.domain.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Treść powiadomienia o zastępstwie - te same reguły co w aplikacji (SubstitutionDisplay):
 * nagłówek to zastępca albo informacja ze strony ("Uczniowie przychodzą później"), nieobecny
 * nauczyciel po "Za:". Dawniej "Zastępuje: <nieobecny>" sugerowało odwrotną rolę, a zwolnienie
 * było "Nowym zastępstwem" bez dnia w tytule.
 */
object SubstitutionNotice {
    data class Content(val title: String, val text: String, val expanded: String)

    private val PL = Locale("pl", "PL")
    private val DATE = DateTimeFormatter.ofPattern("dd.MM")

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Dziś"
        today.plusDays(1) -> "Jutro"
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, PL).replaceFirstChar { it.titlecase(PL) } +
            " " + date.format(DATE)
    }

    /** [subject] - nazwa przedmiotu do wyświetlenia (po personalizacji), jeśli znana. */
    fun content(sub: Substitution, subject: String?, today: LocalDate): Content {
        val day = dayLabel(sub.date, today)
        val what = listOfNotNull(SubstitutionDisplay.headline(sub), SubstitutionDisplay.place(sub)).joinToString(" · ")
        val forWhom = "Za: " + listOfNotNull(subject?.takeIf { it.isNotBlank() }, sub.originalTeacher.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        val notes = SubstitutionDisplay.notes(sub)
        val expanded = buildString {
            append("Kiedy: $day, ${sub.lessonNumber}. lekcja")
            sub.groupNumber?.let { append(" (grupa $it)") }
            append("\nZa nauczyciela: ${sub.originalTeacher}")
            subject?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            if (sub.substituteTeacher.isNullOrBlank()) {
                sub.roomOrInfo.trim().takeIf { it.isNotEmpty() }?.let { append("\nInformacja: $it") }
            } else {
                SubstitutionDisplay.info(sub)?.let { append("\nInformacja: $it") }
                append("\nZastępca: ${sub.substituteTeacher.trim()}")
                sub.roomOrInfo.trim().takeIf { it.isNotEmpty() && SubstitutionDisplay.info(sub) == null }
                    ?.let { append("\nGdzie: s. $it") }
            }
            notes?.let { append("\nUwagi: $it") }
        }
        return Content(
            title = "$day, ${sub.lessonNumber}. lekcja: $what",
            text = listOfNotNull(forWhom, notes).joinToString(" · "),
            expanded = expanded
        )
    }

    /**
     * Stały znacznik powiadomienia dla lekcji (dzień, numer, klasa, grupa). ID zastępstwa zmienia
     * się po poprawce sali lub zastępcy - z tym znacznikiem nowa wersja zastępuje starą w panelu.
     */
    fun tag(sub: Substitution): String =
        "sub:${sub.date.toEpochDay()}|${sub.lessonNumber}|${sub.classShortName.trim().uppercase(PL)}|${sub.groupNumber ?: 0}"

    /**
     * ID zastępstwa + skrót uwag. Pozostałe pola (sala, zastępca, nauczyciel) są już w ID, które
     * zostaje bez zmian - liczy je tak samo watcher pushy.
     */
    fun fingerprint(sub: Substitution): String =
        "${sub.id}$FINGERPRINT_SEPARATOR${(sub.notes ?: "").trim().hashCode().toUInt().toString(16)}"

    /**
     * Zastępstwa już widziane, w których zmieniły się same uwagi. Bez zapisanego odcisku dla
     * danego ID (pierwsza synchronizacja po aktualizacji, zastępstwo z pusha) - nic: najpierw
     * zapisujemy odcisk, inaczej aktualizacja aplikacji powiadomiłaby o wszystkich naraz.
     */
    fun changedNotes(current: List<Substitution>, seenIds: Set<String>, seenFingerprints: Set<String>): List<Substitution> {
        val withFingerprint = seenFingerprints.mapTo(HashSet()) { it.substringBefore(FINGERPRINT_SEPARATOR) }
        return current.filter { it.id in seenIds && it.id in withFingerprint && fingerprint(it) !in seenFingerprints }
    }

    private const val FINGERPRINT_SEPARATOR = '\u0001'
}
