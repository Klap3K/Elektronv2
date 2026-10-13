package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CountdownSettingsTest {

    @Test
    fun daysRemaining_calculatesCorrectly() {
        val today = LocalDate.of(2026, 6, 1)
        val target = LocalDate.of(2026, 6, 26)
        val countdown = CountdownSettings(enabled = true, title = "Wakacje", targetDate = target)

        assertEquals(25L, countdown.daysRemainingFrom(today))
    }

    @Test
    fun daysRemaining_pastOrTodayReturnsZero() {
        val today = LocalDate.of(2026, 6, 26)
        val target = LocalDate.of(2026, 6, 26)
        val countdown = CountdownSettings(enabled = true, title = "Wakacje", targetDate = target)

        assertEquals(0L, countdown.daysRemainingFrom(today))
        assertEquals(0L, countdown.daysRemainingFrom(today.plusDays(1)))
    }

    @Test
    fun schoolDaysRemaining_excludesWeekends() {
        // Poniedziałek 15 czerwca 2026 do Piątku 19 czerwca 2026
        // Dni od jutra (wtorek 16, środa 17, czwartek 18, piątek 19) = 4 dni robocze
        val monday = LocalDate.of(2026, 6, 15)
        val friday = LocalDate.of(2026, 6, 19)
        val countdown1 = CountdownSettings(enabled = true, targetDate = friday)
        assertEquals(4L, countdown1.schoolDaysRemainingFrom(monday))

        // Piątek 19 czerwca 2026 do Poniedziałku 22 czerwca 2026
        // Sobota i niedziela pomijane, tylko poniedziałek = 1 dzień roboczy
        val nextMonday = LocalDate.of(2026, 6, 22)
        val countdown2 = CountdownSettings(enabled = true, targetDate = nextMonday)
        assertEquals(1L, countdown2.schoolDaysRemainingFrom(friday))
    }

    @Test
    fun presets_accuratelyTargetNextYearWhenPassedInCurrentYear() {
        // Test błędu wykrytego w audycie: 10 lutego (po 2 lutego ferii) musi zwrócić 2027 rok!
        val todayAfterWinterBreak = LocalDate.of(2026, 2, 10)
        val presets = CountdownSettings.getPresets(todayAfterWinterBreak).toMap()
        assertEquals(LocalDate.of(2027, 2, 2), presets["Ferie zimowe"])

        // 25 stycznia (po 23 stycznia końca I semestru) musi zwrócić 2027 rok!
        val todayAfterSemester = LocalDate.of(2026, 1, 25)
        val presets2 = CountdownSettings.getPresets(todayAfterSemester).toMap()
        assertEquals(LocalDate.of(2027, 1, 23), presets2["Koniec I semestru"])
        assertEquals(LocalDate.of(2027, 1, 16), presets2["100-dniówka"])

        // Przed wydarzeniem: musi zwrócić ten sam rok
        val todayBeforeWinterBreak = LocalDate.of(2026, 1, 10)
        val presets3 = CountdownSettings.getPresets(todayBeforeWinterBreak).toMap()
        assertEquals(LocalDate.of(2026, 2, 2), presets3["Ferie zimowe"])
    }

    @Test
    fun formatSchoolDays_declinesCorrectlyInPolish() {
        assertEquals("1 dzień szkolny", CountdownSettings.formatSchoolDays(1))
        assertEquals("2 dni szkolne", CountdownSettings.formatSchoolDays(2))
        assertEquals("4 dni szkolne", CountdownSettings.formatSchoolDays(4))
        assertEquals("5 dni szkolnych", CountdownSettings.formatSchoolDays(5))
        assertEquals("12 dni szkolnych", CountdownSettings.formatSchoolDays(12))
        assertEquals("22 dni szkolne", CountdownSettings.formatSchoolDays(22))
        assertEquals("25 dni szkolnych", CountdownSettings.formatSchoolDays(25))
    }
}
