package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceThresholdCalculatorTest {

    @Test
    fun calculate_returns100PercentWhenZeroHours() {
        val result = AttendanceThresholdCalculator.calculate(
            presentHours = 0,
            absentHours = 0,
            requiredThreshold = 0.50
        )
        assertEquals(0, result.totalHours)
        assertEquals(100.0, result.currentPercentage, 0.001)
        assertFalse(result.isBelowThreshold)
        assertEquals(0, result.maxMissableHours)
        assertEquals(0, result.hoursNeededToRecover)
    }

    @Test
    fun calculate_calculatesMaxMissableHoursCorrectlyWhenSafe() {
        // 16 obecności, 4 nieobecności -> 20h łącznie (80%)
        // 16 / 0.5 - 20 = 32 - 20 = 12 godzin do opuszczenia
        val result = AttendanceThresholdCalculator.calculate(
            presentHours = 16,
            absentHours = 4,
            requiredThreshold = 0.50
        )
        assertEquals(20, result.totalHours)
        assertEquals(80.0, result.currentPercentage, 0.001)
        assertFalse(result.isBelowThreshold)
        assertEquals(12, result.maxMissableHours)
        assertEquals(0, result.hoursNeededToRecover)
    }

    @Test
    fun calculate_calculatesRecoveryHoursCorrectlyWhenBelowThreshold() {
        // 8 obecności, 12 nieobecności -> 20h łącznie (40% < 50%)
        // (0.5 * 20 - 8) / 0.5 = 2 / 0.5 = 4 godziny do odrobienia
        val result = AttendanceThresholdCalculator.calculate(
            presentHours = 8,
            absentHours = 12,
            requiredThreshold = 0.50
        )
        assertEquals(20, result.totalHours)
        assertEquals(40.0, result.currentPercentage, 0.001)
        assertTrue(result.isBelowThreshold)
        assertEquals(0, result.maxMissableHours)
        assertEquals(4, result.hoursNeededToRecover)
    }

    @Test
    fun calculate_boundaryCases() {
        // Gdy próg to 100%, a mamy nieobecności, osiągnięcie 100% jest niemożliwe
        val impossible100 = AttendanceThresholdCalculator.calculate(
            presentHours = 10,
            absentHours = 2,
            requiredThreshold = 1.0
        )
        assertTrue(impossible100.isBelowThreshold)
        assertEquals(Int.MAX_VALUE, impossible100.hoursNeededToRecover)

        // Gdy próg to 0%, uczeń nigdy nie jest zagrożony
        val zeroThreshold = AttendanceThresholdCalculator.calculate(
            presentHours = 0,
            absentHours = 10,
            requiredThreshold = 0.0
        )
        assertFalse(zeroThreshold.isBelowThreshold)
        assertEquals(Int.MAX_VALUE, zeroThreshold.maxMissableHours)
        assertEquals(0, zeroThreshold.hoursNeededToRecover)
    }
}
