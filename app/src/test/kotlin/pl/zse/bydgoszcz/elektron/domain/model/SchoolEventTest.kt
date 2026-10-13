package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SchoolEventTest {

    @Test
    fun matchesLessonBySubjectAndZseAbbreviations() {
        val polishClassTest = SchoolEvent(
            id = "ev_1",
            title = "Praca klasowa: Język polski",
            description = "Romantyzm – lektury",
            type = SchoolEventType.EXAM,
            subject = "Język polski",
            date = LocalDate.of(2026, 10, 15),
            timeFrom = null,
            timeTo = null,
            location = null,
            rawSummary = "Praca klasowa: Język polski"
        )

        val zsePolishLesson = Lesson(
            id = "lesson-pl",
            classId = "o26",
            className = "3TI",
            date = LocalDate.of(2026, 10, 15),
            dayOfWeek = DayOfWeek.CZWARTEK,
            number = 2,
            timeFrom = LocalTime.of(8, 50),
            timeTo = LocalTime.of(9, 35),
            groups = listOf(
                LessonGroup("j.polski", "JK", null, "Jan Kowalski", "204", null, null, null)
            ),
            note = null,
            substitution = null
        )

        val zseEnglishLesson = zsePolishLesson.copy(
            groups = listOf(
                LessonGroup("ang-1/2", "AB", null, "Anna B", "101", null, null, null)
            )
        )

        assertTrue(polishClassTest.matchesLesson(zsePolishLesson))
        assertFalse(polishClassTest.matchesLesson(zseEnglishLesson))
        assertEquals("Praca klasowa", polishClassTest.badgeLabel())

        // Sprawdzenie typowych skrótów planu Optivum ZSE Bydgoszcz vs pełnych nazw w eduVULCAN
        assertTrue(SchoolEvent.subjectsMatch("Język angielski", "j.angielski-1/2"))
        assertTrue(SchoolEvent.subjectsMatch("Język angielski", "ang-2/2"))
        assertTrue(SchoolEvent.subjectsMatch("Język niemiecki", "niem-1/2"))
        assertTrue(SchoolEvent.subjectsMatch("Wychowanie fizyczne", "wf-j1"))
        assertTrue(SchoolEvent.subjectsMatch("Matematyka", "r_matematyka"))
        assertTrue(SchoolEvent.subjectsMatch("Historia i teraźniejszość", "hit"))
        assertTrue(SchoolEvent.subjectsMatch("Pracownia programowania obiektowego", "prac.prog.-1/2"))
        assertTrue(SchoolEvent.subjectsMatch("Sieci komputerowe", "sieci komp.-1/2"))
        assertFalse(SchoolEvent.subjectsMatch("Matematyka", "Fizyka"))
    }

    @Test
    fun matchesLessonByTime() {
        val event = SchoolEvent(
            id = "2",
            title = "Test z działu 1",
            description = null,
            type = SchoolEventType.EXAM,
            subject = null, // brak przedmiotu, dopasowanie po godzinie
            date = LocalDate.of(2026, 10, 15),
            timeFrom = LocalTime.of(8, 50),
            timeTo = LocalTime.of(9, 35),
            location = null,
            rawSummary = "Test z działu 1"
        )

        val matchingLesson = Lesson(
            id = "lesson-1",
            classId = "o26",
            className = "3TI",
            date = LocalDate.of(2026, 10, 15),
            dayOfWeek = DayOfWeek.CZWARTEK,
            number = 2,
            timeFrom = LocalTime.of(8, 50),
            timeTo = LocalTime.of(9, 35),
            groups = emptyList(),
            note = null,
            substitution = null
        )

        val differentTimeLesson = matchingLesson.copy(
            timeFrom = LocalTime.of(9, 45),
            timeTo = LocalTime.of(10, 30)
        )

        assertTrue(event.matchesLesson(matchingLesson))
        assertFalse(event.matchesLesson(differentTimeLesson))
    }

    @Test
    fun badgeLabelsAreAccurate() {
        assertEquals("Sprawdzian", SchoolEventType.EXAM.let {
            SchoolEvent("1", "Sprawdzian: Fizyka", null, it, "Fizyka", LocalDate.now(), null, null, null, "Sprawdzian").badgeLabel()
        })
        assertEquals("Praca klasowa", SchoolEventType.EXAM.let {
            SchoolEvent("1b", "Praca klasowa: Matematyka", null, it, "Matematyka", LocalDate.now(), null, null, null, "Praca klasowa").badgeLabel()
        })
        assertEquals("Kartkówka", SchoolEventType.QUIZ.let {
            SchoolEvent("2", "T", null, it, null, LocalDate.now(), null, null, null, "T").badgeLabel()
        })
        assertEquals("Zad. domowe", SchoolEventType.HOMEWORK.let {
            SchoolEvent("3", "T", null, it, null, LocalDate.now(), null, null, null, "T").badgeLabel()
        })
    }
}
