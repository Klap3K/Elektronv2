package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Prawdziwe wpisy ze strony zastępstw ZSE (28-29.09.2026). */
class SubstitutionDisplayTest {
    private fun sub(info: String, teacher: String?, notes: String? = null) =
        Substitution("x", LocalDate.of(2026, 9, 29), 3, "2H", null, info, teacher, notes, "J.Siepniewska-Stańczyk")

    @Test
    fun rooms() {
        listOf("211", "pc2", "W-6", "105", "PC3").forEach { assertTrue(it, SubstitutionDisplay.isRoom(it)) }
        listOf("Zajęcia Świetlicowe", "Uczniowie przychodzą później").forEach { assertFalse(it, SubstitutionDisplay.isRoom(it)) }
    }

    @Test
    fun teacherIsHeadlineRoomBelow() {
        val s = sub("211", "M. Zelek", "za ostatnią lekcję")
        assertEquals("M. Zelek", SubstitutionDisplay.headline(s))
        assertEquals("s. 211", SubstitutionDisplay.place(s))
        assertEquals("za ostatnią lekcję", SubstitutionDisplay.notes(s))
    }

    @Test
    fun homeScreenPutsSchoolInfoFirstElsewhereTeacherFirst() {
        val s = sub("Zajęcia w auli", "M. Zelek", "za ostatnią lekcję")
        // Strona główna: informacja szkoły na pierwszym planie, nauczyciel pod nią.
        assertEquals("Zajęcia w auli", SubstitutionDisplay.headline(s, infoFirst = true))
        assertEquals("M. Zelek", SubstitutionDisplay.place(s, infoFirst = true))
        // Plan, zakładka Zastępstwa, widżety: najpierw nauczyciel.
        assertEquals("M. Zelek", SubstitutionDisplay.headline(s))
        assertEquals("Zajęcia w auli", SubstitutionDisplay.place(s))
        // Sama sala: bez różnicy.
        assertEquals("M. Zelek", SubstitutionDisplay.headline(sub("211", "M. Zelek"), infoFirst = true))
        assertEquals("s. 211", SubstitutionDisplay.place(sub("211", "M. Zelek"), infoFirst = true))
        assertEquals("Zajęcia w auli", SubstitutionDisplay.info(s))
        assertNull(SubstitutionDisplay.info(sub("211", "M. Zelek")))
    }

    @Test
    fun withoutSubstituteInfoIsHeadline() {
        val s = sub("Uczniowie przychodzą później", null)
        assertEquals("Uczniowie przychodzą później", SubstitutionDisplay.headline(s))
        assertNull(SubstitutionDisplay.place(s))
        assertNull(SubstitutionDisplay.notes(sub("211", "M. Zelek", "  ")))
    }

    @Test
    fun exemptionsAreDetected() {
        listOf("Uczniowie zwolnieni do domu", "Uczniowie przychodzą później", "Uczniowie przychodza pozniej",
            "Lekcja odwołana", "zajęcia nie odbywają się").forEach {
            assertTrue(it, SubstitutionDisplay.freesLesson(sub(it, null)))
        }
        // Świetlica i zwykłe zastępstwo - lekcja (zajęcia) się odbywa.
        assertFalse(SubstitutionDisplay.freesLesson(sub("Zajęcia Świetlicowe", null)))
        assertFalse(SubstitutionDisplay.freesLesson(sub("211", "M. Zelek")))
        // Zastępca wpisany - to nie zwolnienie, nawet przy dziwnej uwadze.
        assertFalse(SubstitutionDisplay.freesLesson(sub("211", "M. Zelek", "uczniowie zwolnieni z 1 lekcji")))
    }
}
