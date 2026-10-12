package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceTest {

    @Test
    fun percentageCalculation_correct() {
        val subject = SubjectAttendance(
            subject = "Matematyka",
            presentHours = 30,
            absentHours = 5,
            excusedHours = 5,
            lateHours = 0,
            schoolObligationHours = 0
        )
        // presentCount = 30, totalHours = 40 => 30/40 = 75.0%
        assertEquals(75.0f, subject.percentage, 0.01f)
        assertFalse(subject.isCritical)
        assertFalse(subject.isWarning)
    }

    @Test
    fun maxMissableHours_exactAt50Percent() {
        val subject = SubjectAttendance(
            subject = "Informatyka",
            presentHours = 30,
            absentHours = 10,
            excusedHours = 0,
            lateHours = 0,
            schoolObligationHours = 0
        )
        // O=30, N=10, T=0.5: (30 * 0.5 - 10 * 0.5) / 0.5 = 20
        val maxMiss = subject.maxMissableHours(0.50f)
        assertEquals(20, maxMiss)

        // Po opuszczeniu 20 godzin: 30 obecnych / 60 łącznie = 50.0%
        val simulated = subject.simulate(simulatedAbsentHours = 20)
        assertEquals(50.0f, simulated, 0.01f)
    }

    @Test
    fun hoursNeededToRecover_whenBelow50Percent() {
        val subject = SubjectAttendance(
            subject = "Fizyka",
            presentHours = 10,
            absentHours = 30,
            excusedHours = 0,
            lateHours = 0,
            schoolObligationHours = 0
        )
        // O=10, N=30, T=0.5: (30 * 0.5 - 10 * 0.5) / 0.5 = 20
        assertTrue(subject.isCritical)
        val needed = subject.hoursNeededToRecover(0.50f)
        assertEquals(20, needed)

        // Po odrobieniu 20 obecności: 30 obecnych / 60 łącznie = 50.0%
        val simulated = subject.simulate(simulatedPresentHours = needed)
        assertEquals(50.0f, simulated, 0.01f)
    }

    @Test
    fun overallAttendance_aggregation() {
        val s1 = SubjectAttendance("Polski", presentHours = 20, absentHours = 0, excusedHours = 0, lateHours = 0, schoolObligationHours = 0)
        val s2 = SubjectAttendance("Matematyka", presentHours = 10, absentHours = 10, excusedHours = 0, lateHours = 0, schoolObligationHours = 0)
        val overall = OverallAttendance.fromSubjects(listOf(s1, s2))

        // Total present: 30, Total absent: 10 => 30/40 = 75%
        assertEquals(75.0f, overall.percentage, 0.01f)
        assertEquals(0, overall.criticalSubjectsCount)
    }
}
