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

    @Test
    fun calculateBalance_whenAboveTarget() {
        // 18 obecności, 2 nieobecności -> 20h łącznie (90%)
        // Cel: 75%. 18 / 0.75 - 20 = 24 - 20 = 4 godziny do celu 75%
        // Do 50%: 18 / 0.5 - 20 = 36 - 20 = 16 godzin do 50%
        val bal = AttendanceThresholdCalculator.calculateBalance(
            presentHours = 18,
            absentHours = 2,
            targetThresholdPercent = 75
        )
        assertFalse(bal.isCritical)
        assertFalse(bal.isBelowTarget)
        assertEquals(4, bal.maxMissableToTarget)
        assertEquals(0, bal.recoveryNeededToTarget)
        assertEquals(16, bal.maxMissableTo50)
        assertEquals(0, bal.recoveryNeededTo50)
        assertTrue(bal.shortSummary.contains("Zapas: 4 h (do 75%)"))
        assertTrue(bal.fullSummary.contains("Możesz opuścić jeszcze 4 godz."))
    }

    @Test
    fun calculateBalance_whenBelowTargetButAbove50Percent() {
        // 12 obecności, 8 nieobecności -> 20h łącznie (60%)
        // Cel: 75%. (0.75 * 20 - 12) / 0.25 = 3 / 0.25 = 12 godzin do odrobienia do 75%
        // Do 50%: 12 / 0.5 - 20 = 24 - 20 = 4 godziny zapasu do 50%
        val bal = AttendanceThresholdCalculator.calculateBalance(
            presentHours = 12,
            absentHours = 8,
            targetThresholdPercent = 75
        )
        assertFalse(bal.isCritical)
        assertTrue(bal.isBelowTarget)
        assertEquals(0, bal.maxMissableToTarget)
        assertEquals(12, bal.recoveryNeededToTarget)
        assertEquals(4, bal.maxMissableTo50)
        assertEquals(0, bal.recoveryNeededTo50)
        assertTrue(bal.shortSummary.contains("Do 75%: +12 h (zapas: 4 h)"))
        assertTrue(bal.fullSummary.contains("Poniżej celu 75%"))
    }

    @Test
    fun calculateBalance_whenCriticalBelow50Percent() {
        // 8 obecności, 12 nieobecności -> 20h łącznie (40% < 50%)
        // Do 50%: (0.5 * 20 - 8) / 0.5 = 4 godziny do 50%
        val bal = AttendanceThresholdCalculator.calculateBalance(
            presentHours = 8,
            absentHours = 12,
            targetThresholdPercent = 75
        )
        assertTrue(bal.isCritical)
        assertTrue(bal.isBelowTarget)
        assertEquals(0, bal.maxMissableTo50)
        assertEquals(4, bal.recoveryNeededTo50)
        assertTrue(bal.shortSummary.contains("Zagrożenie: +4 h do 50%"))
        assertTrue(bal.fullSummary.contains("Zagrożenie nieklasyfikowaniem!"))
    }

    @Test
    fun calculateBalance_whenZeroHours() {
        val bal = AttendanceThresholdCalculator.calculateBalance(
            presentHours = 0,
            absentHours = 0,
            targetThresholdPercent = 75
        )
        assertFalse(bal.isCritical)
        assertFalse(bal.isBelowTarget)
        assertEquals("Brak zajęć", bal.shortSummary)
    }
}
