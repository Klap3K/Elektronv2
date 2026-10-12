package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SchoolEventTest {

    @Test
    fun matchesLessonBySubject() {
        val event = SchoolEvent(
            id = "1",
            title = "Sprawdzian: Matematyka",
            description = null,
            type = SchoolEventType.EXAM,
            subject = "Matematyka",
            date = LocalDate.of(2026, 10, 15),
            timeFrom = null,
            timeTo = null,
            location = null,
            rawSummary = "Sprawdzian: Matematyka"
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
            groups = listOf(
                LessonGroup("matematyka", "JK", null, "Jan Kowalski", "204", null, null, null)
            ),
            note = null,
            substitution = null
        )

        val nonMatchingLesson = matchingLesson.copy(
            groups = listOf(
                LessonGroup("język angielski", "AB", null, "Anna B", "101", null, null, null)
            )
        )

        assertTrue(event.matchesLesson(matchingLesson))
        assertFalse(event.matchesLesson(nonMatchingLesson))
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
            SchoolEvent("1", "T", null, it, null, LocalDate.now(), null, null, null, "T").badgeLabel()
        })
        assertEquals("Kartkówka", SchoolEventType.QUIZ.let {
            SchoolEvent("2", "T", null, it, null, LocalDate.now(), null, null, null, "T").badgeLabel()
        })
        assertEquals("Zad. domowe", SchoolEventType.HOMEWORK.let {
            SchoolEvent("3", "T", null, it, null, LocalDate.now(), null, null, null, "T").badgeLabel()
        })
    }
}
