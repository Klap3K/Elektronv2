package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class LessonClockTest {

    private val day = LocalDate.of(2026, 10, 1)
    private fun lesson(n: Int, from: String, to: String, date: LocalDate = day) =
        Lesson("$date-$n", "o3", "1D", date, DayOfWeek.CZWARTEK, n, LocalTime.parse(from), LocalTime.parse(to), emptyList(), null, null)
    private fun at(t: String) = LocalDateTime.of(day, LocalTime.parse(t))

    // 1. 08:00-08:45, 2. 08:55-09:40, (3. innej grupy - po filtrze brak), 4. 10:45-11:30 (zastępstwo)
    private val plan = listOf(lesson(1, "08:00", "08:45"), lesson(2, "08:55", "09:40"), lesson(4, "10:45", "11:30"),
        lesson(1, "08:00", "08:45", day.plusDays(1)))

    @Test fun duringLessonShowsTimeLeft() {
        val s = LessonClock.status(plan, at("08:20")) as LessonClock.During
        assertEquals(1, s.lesson.number)
        assertEquals(25, s.minutesLeft)
        assertTrue(s.progress in 0.44f..0.45f)
    }

    @Test fun shortBreakShowsCountdown() {
        val s = LessonClock.status(plan, at("08:50")) as LessonClock.Break
        assertEquals(2, s.next.number)
        assertEquals(5, s.minutesUntil)
    }

    @Test fun freePeriodHidesCountdownUntil30Minutes() {
        // Okienko 09:40-10:45 (65 min): to nie przerwa - dawniej "52 min do zastępstwa".
        val early = LessonClock.status(plan, at("09:53")) as LessonClock.Before
        assertEquals(4, early.next.number)
        assertNull(early.minutesUntil)
        val late = LessonClock.status(plan, at("10:20")) as LessonClock.Before
        assertEquals(25L, late.minutesUntil)
    }

    @Test fun firstLessonCountdownOnly30MinutesBefore() {
        assertNull((LessonClock.status(plan, at("07:10")) as LessonClock.Before).minutesUntil)
        assertEquals(30L, (LessonClock.status(plan, at("07:30")) as LessonClock.Before).minutesUntil)
    }

    @Test fun afterLessonsNextDayWithoutCountdown() {
        val s = LessonClock.status(plan, at("12:00")) as LessonClock.Before
        assertEquals(day.plusDays(1), s.next.date)
        assertNull(s.minutesUntil)
        assertEquals(LessonClock.Nothing, LessonClock.status(plan.take(3), at("12:00")))
    }

    @Test fun minutesRoundUp() {
        assertEquals(1, LessonClock.minutesCeil(LocalTime.parse("08:44:30"), LocalTime.parse("08:45")))
        assertEquals(0, LessonClock.minutesCeil(LocalTime.parse("08:45"), LocalTime.parse("08:45")))
    }

    @Test fun freedLessonsAreNeitherCurrentNorNext() {
        fun freed(n: Int, info: String) = Substitution("f$n", day, n, "1D", null, info, null, null, "X")
        // 2. lekcja zwolniona: w trakcie jej godzin nic nie "trwa", następna jest 4. lekcja.
        val withFreed = listOf(lesson(1, "08:00", "08:45"),
            lesson(2, "08:55", "09:40").copy(substitution = freed(2, "Uczniowie zwolnieni do domu")),
            lesson(4, "10:45", "11:30"))
        val s = LessonClock.status(withFreed, at("09:00")) as LessonClock.Before
        assertEquals(4, s.next.number)
        // Rano: klasa przychodzi później - pierwszą lekcją dnia jest 2.
        val late = listOf(lesson(1, "08:00", "08:45").copy(substitution = freed(1, "Uczniowie przychodzą później")),
            lesson(2, "08:55", "09:40"))
        assertEquals(2, (LessonClock.status(late, at("07:50")) as LessonClock.Before).next.number)
    }
}
