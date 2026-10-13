package pl.zse.bydgoszcz.elektron.domain.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Wynik analizy bezpiecznego progu frekwencji.
 */
data class AttendanceThresholdResult(
    val totalHours: Int,
    val presentHours: Int,
    val absentHours: Int,
    val currentPercentage: Double,
    val isBelowThreshold: Boolean,
    val maxMissableHours: Int,
    val hoursNeededToRecover: Int
)

/**
 * Kalkulator bezpiecznego progu frekwencji (np. 50% dla zagrożenia nieklasyfikowaniem).
 *
 * Matematyka:
 * - Przy P/T >= R:
 *     P / (T + x) >= R  <=>  x <= P/R - T
 *     Maksymalna liczba godzin do opuszczenia = floor(P / R - T).
 * - Przy P/T < R:
 *     (P + y) / (T + y) >= R  <=>  y >= (R * T - P) / (1 - R)
 *     Liczba godzin do odrobienia = ceil((R * T - P) / (1 - R)).
 */
object AttendanceThresholdCalculator {

    const val DEFAULT_THRESHOLD = 0.50

    fun calculate(
        presentHours: Int,
        absentHours: Int,
        requiredThreshold: Double = DEFAULT_THRESHOLD
    ): AttendanceThresholdResult {
        val p = presentHours.coerceAtLeast(0)
        val a = absentHours.coerceAtLeast(0)
        val total = p + a
        val threshold = requiredThreshold.coerceIn(0.0, 1.0)

        if (total == 0) {
            return AttendanceThresholdResult(
                totalHours = 0,
                presentHours = 0,
                absentHours = 0,
                currentPercentage = 100.0,
                isBelowThreshold = false,
                maxMissableHours = 0,
                hoursNeededToRecover = 0
            )
        }

        val percentage = (p.toDouble() / total.toDouble()) * 100.0
        val ratio = p.toDouble() / total.toDouble()
        val isBelow = ratio < threshold

        val maxMissable = if (isBelow || threshold <= 0.0) {
            if (threshold <= 0.0) Int.MAX_VALUE else 0
        } else {
            val max = floor(p.toDouble() / threshold - total.toDouble()).toInt()
            max.coerceAtLeast(0)
        }

        val recovery = if (!isBelow || threshold >= 1.0) {
            if (isBelow && threshold >= 1.0) {
                // Gdy wymagany próg to 100% (1.0), a uczeń ma nieobecności, 100% jest matematycznie nieosiągalne
                Int.MAX_VALUE
            } else {
                0
            }
        } else {
            val needed = ceil((threshold * total.toDouble() - p.toDouble()) / (1.0 - threshold)).toInt()
            needed.coerceAtLeast(0)
        }

        val roundedPercentage = ((percentage * 100.0).roundToLong()) / 100.0

        return AttendanceThresholdResult(
            totalHours = total,
            presentHours = p,
            absentHours = a,
            currentPercentage = roundedPercentage,
            isBelowThreshold = isBelow,
            maxMissableHours = maxMissable,
            hoursNeededToRecover = recovery
        )
    }
}
