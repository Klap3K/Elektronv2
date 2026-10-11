package pl.zse.bydgoszcz.elektron.domain.model

/**
 * Jak pokazywać zastępstwo — jedno miejsce dla planu, strony głównej, zakładki Zastępstwa
 * i widżetów:
 *  - nagłówek: informacja zamieszczona przez szkołę ("Zajęcia Świetlicowe", "Zajęcia w auli",
 *    "Uczniowie przychodzą później"), także gdy jest wpisany zastępca; bez takiej informacji -
 *    nauczyciel, który zastępuje,
 *  - druga linia: sala ("s. 211") pod nauczycielem albo nauczyciel pod informacją,
 *  - uwagi ("za ostatnią lekcję", "historia") — zawsze widoczne.
 */
object SubstitutionDisplay {

    /** Krótki token bez spacji = numer/nazwa sali ("211", "pc2", "W-6", "S1"). */
    private val ROOM = Regex("^[\\p{L}\\d.\\-/]{1,6}$")

    fun isRoom(text: String): Boolean = ROOM.matches(text.trim())

    /** Informacja szkoły (nie numer sali) - ma pierwszeństwo przed nazwiskiem zastępcy. */
    fun info(sub: Substitution): String? = sub.roomOrInfo.trim().takeIf { it.isNotEmpty() && !isRoom(it) }

    private fun teacher(sub: Substitution): String? = sub.substituteTeacher?.trim()?.takeIf { it.isNotEmpty() }

    fun headline(sub: Substitution): String =
        info(sub) ?: teacher(sub) ?: sub.roomOrInfo.trim().takeIf { it.isNotEmpty() } ?: "Zastępstwo"

    /** Druga linia: sala pod nauczycielem albo nauczyciel pod informacją; null, gdy nie ma czego dodać. */
    fun place(sub: Substitution): String? {
        val teacher = teacher(sub) ?: return null
        if (info(sub) != null) return teacher
        val room = sub.roomOrInfo.trim().takeIf { it.isNotEmpty() } ?: return null
        return "s. $room"
    }

    fun notes(sub: Substitution): String? = sub.notes?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Wpisy, po których lekcja się dla ucznia NIE odbywa (zwolnienie klasy/grupy): bez zastępcy
     * i z informacją typu "Uczniowie zwolnieni do domu", "Uczniowie przychodzą później",
     * "lekcja odwołana". "Zajęcia świetlicowe" to NIE zwolnienie - uczniowie są w szkole.
     */
    private val FREES = Regex(
        "zwolnien|przychodz\\p{L}* p[oó]\\p{L}*niej|odwo[lł]an|do domu|nie odbywa|wolne od zaj",
        RegexOption.IGNORE_CASE
    )

    fun freesLesson(sub: Substitution): Boolean =
        sub.substituteTeacher.isNullOrBlank() && FREES.containsMatchIn(sub.roomOrInfo + " " + (sub.notes ?: ""))

    /** Lekcja odbywa się (nie ma zwolnienia) - do przypomnień, "po lekcjach", startu aplikacji. */
    fun takesPlace(lesson: Lesson): Boolean = lesson.substitution?.let { !freesLesson(it) } ?: true
}
