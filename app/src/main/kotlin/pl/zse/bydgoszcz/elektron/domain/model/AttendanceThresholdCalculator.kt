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
 * Zrównoważony bilans frekwencji z uwzględnieniem progu docelowego (np. 75%) oraz krytycznego (50%).
 */
data class AttendanceBalance(
    val currentPercentage: Double,
    val targetThresholdPercent: Int,
    val isCritical: Boolean,
    val isBelowTarget: Boolean,
    val maxMissableToTarget: Int,
    val recoveryNeededToTarget: Int,
    val maxMissableTo50: Int,
    val recoveryNeededTo50: Int,
    val shortSummary: String,
    val fullSummary: String
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
    const val DEFAULT_TARGET_THRESHOLD_PERCENT = 75

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

    /**
     * Wylicza kompleksowy bilans frekwencji z porównaniem do progu docelowego [targetThresholdPercent] (np. 75%)
     * oraz ustawowego progu ostrzegawczego 50%.
     */
    fun calculateBalance(
        presentHours: Int,
        absentHours: Int,
        targetThresholdPercent: Int = DEFAULT_TARGET_THRESHOLD_PERCENT
    ): AttendanceBalance {
        val targetPct = targetThresholdPercent.coerceIn(50, 95)
        val targetRatio = targetPct.toDouble() / 100.0
        val critRatio = DEFAULT_THRESHOLD

        val p = presentHours.coerceAtLeast(0)
        val a = absentHours.coerceAtLeast(0)
        val total = p + a

        if (total == 0) {
            return AttendanceBalance(
                currentPercentage = 100.0,
                targetThresholdPercent = targetPct,
                isCritical = false,
                isBelowTarget = false,
                maxMissableToTarget = 0,
                recoveryNeededToTarget = 0,
                maxMissableTo50 = 0,
                recoveryNeededTo50 = 0,
                shortSummary = "Brak zajęć",
                fullSummary = "Brak odnotowanych godzin lekcyjnych."
            )
        }

        val resCrit = calculate(p, a, critRatio)
        val resTarget = calculate(p, a, targetRatio)
        val currentPct = resCrit.currentPercentage
        val isCrit = resCrit.isBelowThreshold
        val isBelowTgt = resTarget.isBelowThreshold

        val short = when {
            isCrit -> "Zagrożenie: +${resCrit.hoursNeededToRecover} h do 50%"
            isBelowTgt -> "Do $targetPct%: +${resTarget.hoursNeededToRecover} h (zapas: ${resCrit.maxMissableHours} h)"
            else -> "Zapas: ${resTarget.maxMissableHours} h (do $targetPct%)"
        }

        val full = when {
            isCrit -> "Zagrożenie nieklasyfikowaniem! Musisz być obecny na ${resCrit.hoursNeededToRecover} godz. z rzędu, by osiągnąć próg 50%."
            isBelowTgt -> "Poniżej celu $targetPct%: brakuje ${resTarget.hoursNeededToRecover} godz. obecności. Bezpieczny zapas do progu 50%: ${resCrit.maxMissableHours} godz."
            else -> "Możesz opuścić jeszcze ${resTarget.maxMissableHours} godz. przed spadkiem poniżej celu $targetPct% (oraz ${resCrit.maxMissableHours} godz. do progu 50%)."
        }

        return AttendanceBalance(
            currentPercentage = currentPct,
            targetThresholdPercent = targetPct,
            isCritical = isCrit,
            isBelowTarget = isBelowTgt,
            maxMissableToTarget = resTarget.maxMissableHours,
            recoveryNeededToTarget = resTarget.hoursNeededToRecover,
            maxMissableTo50 = resCrit.maxMissableHours,
            recoveryNeededTo50 = resCrit.hoursNeededToRecover,
            shortSummary = short,
            fullSummary = full
        )
    }
}
