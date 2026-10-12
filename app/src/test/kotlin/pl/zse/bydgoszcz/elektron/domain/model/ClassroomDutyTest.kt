package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ClassroomDutyTest {

    @Test
    fun dutyCalculation_weekendIsNotDutyToday() {
        // Sobota: 2026-10-17
        val saturday = LocalDate.of(2026, 10, 17)
        val duty = ClassroomDuty.calculate(saturday, studentNumber = 1, classSize = 30)

        assertFalse("W sobotę uczeń nie powinien mieć dyżuru dzisiaj", duty.isDutyToday)
    }

    @Test
    fun dutyCalculation_matchingStudentNumber() {
        // Środa: 2026-10-14
        val wednesday = LocalDate.of(2026, 10, 14)
        val duty1 = ClassroomDuty.calculate(wednesday, studentNumber = null, classSize = 30)
        assertNotNull(duty1.currentDutyNumber1)
        assertNotNull(duty1.currentDutyNumber2)

        // Teraz sprawdzamy ucznia, który ma ten numer:
        val dutyForCurrentStudent = ClassroomDuty.calculate(
            wednesday,
            studentNumber = duty1.currentDutyNumber1,
            classSize = 30
        )
        assertTrue("Uczeń z numerem aktualnego dyżurnego ma dyżur dzisiaj", dutyForCurrentStudent.isDutyToday)
        assertTrue("Uczeń z numerem aktualnego dyżurnego ma dyżur w tym tygodniu", dutyForCurrentStudent.isDutyThisWeek)
        assertEquals(0, dutyForCurrentStudent.daysUntilDuty)
    }

    @Test
    fun dutyCalculation_withoutStudentNumber() {
        val date = LocalDate.of(2026, 10, 14)
        val duty = ClassroomDuty.calculate(date, studentNumber = null, classSize = 30)

        assertFalse(duty.isDutyToday)
        assertFalse(duty.isDutyThisWeek)
        assertEquals(null, duty.daysUntilDuty)
    }
}
