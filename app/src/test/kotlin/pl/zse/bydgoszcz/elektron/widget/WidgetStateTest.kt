package pl.zse.bydgoszcz.elektron.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.zse.bydgoszcz.elektron.domain.model.DayOfWeek
import pl.zse.bydgoszcz.elektron.domain.model.Lesson
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroup
import pl.zse.bydgoszcz.elektron.domain.model.LessonTarget
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Stan widżetów (i kafelka) w konkretnych chwilach tygodnia — bez zależności od zegara. */
class WidgetStateTest {

    private val friday = LocalDate.of(2026, 10, 2)          // piątek
    private val monday = LocalDate.of(2026, 10, 5)

    private fun lesson(date: LocalDate, n: Int, from: String, to: String, subject: String = "mat") =
        Lesson("$date-$n", "o3", "1D", date, DayOfWeek.PIATEK, n, LocalTime.parse(from), LocalTime.parse(to),
            listOf(LessonGroup(subject, "Ch", null, null, "105", null, null, null)), null, null)

    private val week = listOf(
        lesson(friday, 3, "09:50", "10:35"),
        lesson(friday, 4, "10:45", "11:30"),
        lesson(friday, 5, "11:40", "12:25"),
        lesson(monday, 1, "08:00", "08:45")
    )

    private fun at(date: LocalDate, time: String) = WidgetDataLoader.buildState(week, LocalDateTime.of(date, LocalTime.parse(time)))

    @Test
    fun duringLesson() {
        val st = at(friday, "11:00") as WidgetState.Ready
        assertEquals(4, st.focus.number)
        assertTrue(st.focusIsNow)
        assertNull(st.breakFrom)
        assertEquals(LocalDateTime.of(friday, LocalTime.of(11, 30)), st.nextChangeAt)
        assertEquals(LocalDateTime.of(friday, LocalTime.of(11, 5)), st.refreshAt)   // co 5 min w trakcie lekcji
    }

    @Test
    fun duringBreak() {
        val st = at(friday, "11:35") as WidgetState.Ready
        assertEquals(5, st.focus.number)
        assertFalse(st.focusIsNow)
        assertEquals(LocalTime.of(11, 30), st.breakFrom)
        assertEquals(LocalDateTime.of(friday, LocalTime.of(11, 40)), st.refreshAt)  // dzwonek wcześniej niż +5 min
    }

    @Test
    fun widgetWakesOnlyWhenTheDisplayChanges() {
        // Przed pierwszą lekcją: następne odświeżenie dopiero na początku odliczania (30 min przed),
        // potem co 5 min.
        assertEquals(LocalDateTime.of(friday, LocalTime.of(9, 20)), (at(friday, "09:00") as WidgetState.Ready).refreshAt)
        assertEquals(LocalDateTime.of(friday, LocalTime.of(9, 30)), (at(friday, "09:25") as WidgetState.Ready).refreshAt)
        // Wieczorem (widżet pokazuje następny dzień) - jedno odświeżenie o północy, nie co 5 min.
        assertEquals(LocalDateTime.of(friday.plusDays(1), LocalTime.of(0, 1)), (at(friday, "23:30") as WidgetState.Ready).refreshAt)
        // Okienko (długa luka) przed lekcją - bez wybudzeń do początku odliczania.
        val gap = WidgetDataLoader.buildState(listOf(lesson(friday, 1, "08:00", "08:45"), lesson(friday, 4, "10:45", "11:30")),
            LocalDateTime.of(friday, LocalTime.of(9, 0))) as WidgetState.Ready
        assertNull(gap.breakFrom)
        assertEquals(LocalDateTime.of(friday, LocalTime.of(10, 15)), gap.refreshAt)
    }

    @Test
    fun beforeFirstLessonIsNotBreak() {
        val st = at(friday, "09:00") as WidgetState.Ready
        assertEquals(3, st.focus.number)
        assertNull("przed pierwszą lekcją to nie przerwa", st.breakFrom)
    }

    @Test
    fun afterSchoolShowsNextSchoolDay() {
        val st = at(friday, "15:00") as WidgetState.Ready
        assertFalse(st.isToday)
        assertEquals(1, st.focus.number)
        assertEquals("Poniedziałek", st.dayLabel)
        assertEquals(LocalDateTime.of(friday.plusDays(1), LocalTime.of(0, 1)), st.nextChangeAt)
    }

    @Test
    fun sundayEveningShowsTomorrow() {
        val st = at(monday.minusDays(1), "20:00") as WidgetState.Ready
        assertEquals("Jutro", st.dayLabel)
    }

    @Test
    fun noLessonsInRange() {
        assertTrue(WidgetDataLoader.buildState(emptyList(), LocalDateTime.of(friday, LocalTime.NOON)) is WidgetState.NoLessons)
    }

    @Test
    fun substitutionShowsTeacherRoomAndNotes() {
        // Zgłoszenie beta testera: przy zastępstwie na pierwszym planie nauczyciel, nie sala,
        // a uwagi ze strony ("za ostatnią lekcję") mają być widoczne.
        val sub = pl.zse.bydgoszcz.elektron.domain.model.Substitution(
            "s", friday, 3, "1D", null, "211", "M. Zelek", "za ostatnią lekcję", "J.Siepniewska-Stańczyk")
        val lessons = listOf(lesson(friday, 3, "09:50", "10:35").copy(substitution = sub))
        val st = WidgetDataLoader.buildState(lessons, LocalDateTime.of(friday, LocalTime.of(9, 0))) as WidgetState.Ready
        assertEquals("M. Zelek", st.focus.title)
        assertEquals("211", st.focus.room)
        assertEquals("za ostatnią lekcję", st.focus.note)
    }

    @Test
    fun customSubjectNameIsUsed() {
        // Własna nazwa z Ustawień -> Przedmioty trafia też do widżetów (dla każdej grupy przedmiotu).
        val lessons = listOf(lesson(friday, 3, "09:50", "10:35", subject = "wf-j1"))
        val styles = mapOf("wf" to pl.zse.bydgoszcz.elektron.domain.model.SubjectStyle(name = "WF"))
        val st = WidgetDataLoader.buildState(lessons, LocalDateTime.of(friday, LocalTime.of(9, 0)), styles) as WidgetState.Ready
        assertEquals("WF", st.focus.title)
    }

    @Test
    fun widgetLookHidesTeacherAndRoom() {
        val lessons = listOf(lesson(friday, 3, "09:50", "10:35"))
        val look = pl.zse.bydgoszcz.elektron.domain.model.WidgetLook(showTeacher = false, showRoom = false)
        val st = WidgetDataLoader.buildState(lessons, LocalDateTime.of(friday, LocalTime.of(9, 0)), emptyMap(), look) as WidgetState.Ready
        assertEquals(null, st.focus.detail)
        assertEquals(null, st.focus.room)
    }

    @Test
    fun customSubjectColorReachesWidget() {
        // Kolor z Ustawień -> Przedmioty trafia do widżetów; zastępstwo zostaje w kolorze zastępstw.
        val lessons = listOf(lesson(friday, 3, "09:50", "10:35", subject = "wf-j1"))
        val styles = mapOf("wf" to pl.zse.bydgoszcz.elektron.domain.model.SubjectStyle(color = 0xFF7CB342))
        val st = WidgetDataLoader.buildState(lessons, LocalDateTime.of(friday, LocalTime.of(9, 0)), styles) as WidgetState.Ready
        assertEquals(0xFF7CB342, st.focus.color)
        assertEquals(null, (WidgetDataLoader.buildState(listOf(lesson(friday, 3, "09:50", "10:35")),
            LocalDateTime.of(friday, LocalTime.of(9, 0)), styles) as WidgetState.Ready).focus.color)
    }

    @Test
    fun freePeriodIsNotBreak() {
        // Lekcja 4. innej grupy (po filtrze brak): okienko 10:35-11:40 to nie przerwa -
        // bez paska przerwy i bez "Lekcja za 52 min".
        val lessons = week.filter { it.number != 4 }
        val st = WidgetDataLoader.buildState(lessons, LocalDateTime.of(friday, LocalTime.of(10, 48))) as WidgetState.Ready
        assertEquals(5, st.focus.number)
        assertNull(st.breakFrom)
    }

    @Test
    fun lessonWithoutGroupsTeacherOrRoomDoesNotBreakWidget() {
        val bare = listOf(
            Lesson("a", "o3", "", friday, DayOfWeek.PIATEK, 1, LocalTime.of(8, 0), LocalTime.of(8, 45),
                emptyList(), null, null),
            Lesson("b", "o3", "", friday, DayOfWeek.PIATEK, 2, LocalTime.of(8, 55), LocalTime.of(9, 40),
                emptyList(), "Wycieczka", null),
            Lesson("c", "o3", "", friday, DayOfWeek.PIATEK, 3, LocalTime.of(9, 50), LocalTime.of(10, 35),
                listOf(LessonGroup(null, null, null, null, null, null, null, null)), null, null)
        )
        val st = WidgetDataLoader.buildState(bare, LocalDateTime.of(friday, LocalTime.of(7, 0))) as WidgetState.Ready
        assertEquals(listOf("Lekcja", "Wycieczka", "Lekcja"), st.lessons.map { it.title })
        assertEquals(listOf<String?>(null, null, null), st.lessons.map { it.room })
        // Pusta baza / brak planu - komunikat zamiast awarii.
        assertTrue(WidgetDataLoader.buildState(emptyList(), LocalDateTime.of(friday, LocalTime.of(7, 0))) is WidgetState.NoLessons)
    }

    // Cel dotknięcia: data POKAZYWANEGO dnia i numer lekcji.

    @Test
    fun targetDuringLessonIsCurrentLesson() {
        val st = at(friday, "11:00")
        assertEquals(LessonTarget(friday, 4), WidgetTargets.focus(st))
    }

    @Test
    fun targetDuringBreakIsNextLesson() {
        val st = at(friday, "11:35")
        assertEquals(LessonTarget(friday, 5), WidgetTargets.focus(st))
    }

    @Test
    fun targetBeforeLessonsToday() {
        assertEquals(LessonTarget(friday, 3), WidgetTargets.focus(at(friday, "07:00")))
    }

    @Test
    fun targetOfNextDayLessonHasThatDaysDate() {
        // Piątek po lekcjach: widżet pokazuje poniedziałek - cel to poniedziałek, nie dziś.
        val st = at(friday, "15:00")
        assertEquals(LessonTarget(monday, 1), WidgetTargets.focus(st))
        assertEquals(LessonTarget(monday, 1), WidgetTargets.lesson(st, 0))
    }

    @Test
    fun rowTargetsForDayPlan() {
        val st = at(friday, "07:00")
        assertEquals(listOf(3, 4, 5), (0..2).map { WidgetTargets.lesson(st, it)?.lessonNumber })
        assertTrue((0..2).all { WidgetTargets.lesson(st, it)?.date == friday })
        assertNull(WidgetTargets.lesson(st, 3))
        assertNull(WidgetTargets.lesson(st, -1))
    }

    @Test
    fun noTargetWithoutLessons() {
        val empty = WidgetDataLoader.buildState(emptyList(), LocalDateTime.of(friday, LocalTime.of(7, 0)))
        assertNull(WidgetTargets.focus(empty))
        assertNull(WidgetTargets.lesson(empty, 0))
        assertNull(WidgetTargets.focus(WidgetState.NoClass))
    }
    @Test fun failedLiveReadKeepsLastPlanAndMarksItAsSaved() {
        val old = at(friday, "11:00") as WidgetState.Ready
        val failed = retainWidgetData(old, WidgetState.Failed) as WidgetState.Ready
        assertEquals(old.lessons, failed.lessons)
        assertTrue(failed.readError)
        assertEquals(old, retainWidgetData(failed, old))
        assertEquals(WidgetState.Failed, retainWidgetData(WidgetState.NoClass, WidgetState.Failed))
        val subs = SubsWidgetState.Ready("1D", emptyList())
        assertTrue((retainSubsWidgetData(subs, SubsWidgetState.Failed) as SubsWidgetState.Ready).readError)
    }

}
