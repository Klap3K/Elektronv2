package pl.zse.bydgoszcz.elektron.domain.model

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Statystyka frekwencji dla pojedynczego przedmiotu.
 *
 * Logika obliczania:
 * - Godziny obecne (O) = presentHours + lateHours + schoolObligationHours
 * - Godziny nieobecne (N) = absentHours + excusedHours
 * - Łącznie godzin (W) = O + N
 * - Frekwencja = (O / W) * 100%
 */
data class SubjectAttendance(
    val subject: String,
    val presentHours: Int,
    val absentHours: Int,
    val excusedHours: Int,
    val lateHours: Int,
    val schoolObligationHours: Int,
    val lastUpdatedEpochSeconds: Long = System.currentTimeMillis() / 1000
) {
    /** Godziny zaliczone jako obecność (obecności + spóźnienia + zwolnienia/obowiązek szkolny). */
    val presentCount: Int
        get() = presentHours + lateHours + schoolObligationHours

    /** Wszystkie nieobecności (nieusprawiedliwione + usprawiedliwione). */
    val absentCount: Int
        get() = absentHours + excusedHours

    /** Całkowita liczba godzin przedmiotu. */
    val totalHours: Int
        get() = presentCount + absentCount

    /** Procent frekwencji (0.0 .. 100.0). Jeśli 0 godzin, przyjmujemy 100%. */
    val percentage: Float
        get() = if (totalHours == 0) 100f else (presentCount.toFloat() / totalHours.toFloat()) * 100f

    /** Czy frekwencja jest poniżej progu 50% (zagrożenie nieklasyfikowaniem). */
    val isCritical: Boolean
        get() = totalHours > 0 && percentage < 50f

    /** Czy frekwencja jest w strefie ostrzegawczej (50% .. 65%). */
    val isWarning: Boolean
        get() = totalHours > 0 && percentage in 50f..<65f

    /**
     * Maksymalna liczba godzin, które można jeszcze opuścić przed spadkiem poniżej progu [thresholdFraction] (np. 0.50f dla 50%).
     *
     * Wzór: x = floor((O * (1 - T) - N * T) / T)
     */
    fun maxMissableHours(thresholdFraction: Float = 0.50f): Int {
        if (totalHours == 0) return 0
        val t = thresholdFraction.toDouble().coerceIn(0.01, 0.99)
        val o = presentCount.toDouble()
        val n = absentCount.toDouble()
        val numerator = o * (1.0 - t) - n * t
        val maxMiss = floor(numerator / t).toInt()
        return maxMiss.coerceAtLeast(0)
    }

    /**
     * Liczba obecności z rzędu potrzebna do odrobienia frekwencji do progu [thresholdFraction], gdy uczeń jest poniżej progu.
     *
     * Wzór: y = ceil((N * T - O * (1 - T)) / (1 - T))
     */
    fun hoursNeededToRecover(thresholdFraction: Float = 0.50f): Int {
        if (totalHours == 0) return 0
        val t = thresholdFraction.toDouble().coerceIn(0.01, 0.99)
        val o = presentCount.toDouble()
        val n = absentCount.toDouble()
        if (o / (o + n) >= t) return 0
        val numerator = n * t - o * (1.0 - t)
        val needed = ceil(numerator / (1.0 - t)).toInt()
        return needed.coerceAtLeast(0)
    }

    /**
     * Symuluje nową frekwencję przy dodatkowych opuszczonych [simulatedAbsentHours] lub odrobionych [simulatedPresentHours].
     */
    fun simulate(simulatedAbsentHours: Int = 0, simulatedPresentHours: Int = 0): Float {
        val newPresent = (presentCount + simulatedPresentHours).coerceAtLeast(0)
        val newAbsent = (absentCount + simulatedAbsentHours).coerceAtLeast(0)
        val newTotal = newPresent + newAbsent
        return if (newTotal == 0) 100f else (newPresent.toFloat() / newTotal.toFloat()) * 100f
    }
}

/**
 * Podsumowanie całościowej frekwencji ucznia ze wszystkich przedmiotów.
 */
data class OverallAttendance(
    val subjects: List<SubjectAttendance>,
    val presentHours: Int,
    val absentHours: Int,
    val excusedHours: Int,
    val lateHours: Int,
    val schoolObligationHours: Int
) {
    val presentCount: Int get() = presentHours + lateHours + schoolObligationHours
    val absentCount: Int get() = absentHours + excusedHours
    val totalHours: Int get() = presentCount + absentCount

    val percentage: Float
        get() = if (totalHours == 0) 100f else (presentCount.toFloat() / totalHours.toFloat()) * 100f

    val criticalSubjectsCount: Int
        get() = subjects.count { it.isCritical }

    val warningSubjectsCount: Int
        get() = subjects.count { it.isWarning }

    fun maxMissableHours(thresholdFraction: Float = 0.50f): Int {
        if (totalHours == 0) return 0
        val t = thresholdFraction.toDouble().coerceIn(0.01, 0.99)
        val o = presentCount.toDouble()
        val n = absentCount.toDouble()
        val numerator = o * (1.0 - t) - n * t
        return floor(numerator / t).toInt().coerceAtLeast(0)
    }

    fun hoursNeededToRecover(thresholdFraction: Float = 0.50f): Int {
        if (totalHours == 0) return 0
        val t = thresholdFraction.toDouble().coerceIn(0.01, 0.99)
        val o = presentCount.toDouble()
        val n = absentCount.toDouble()
        if (o / (o + n) >= t) return 0
        val numerator = n * t - o * (1.0 - t)
        return ceil(numerator / (1.0 - t)).toInt().coerceAtLeast(0)
    }

    fun simulate(simulatedAbsentHours: Int = 0, simulatedPresentHours: Int = 0): Float {
        val newPresent = (presentCount + simulatedPresentHours).coerceAtLeast(0)
        val newAbsent = (absentCount + simulatedAbsentHours).coerceAtLeast(0)
        val newTotal = newPresent + newAbsent
        return if (newTotal == 0) 100f else (newPresent.toFloat() / newTotal.toFloat()) * 100f
    }

    companion object {
        fun fromSubjects(subjects: List<SubjectAttendance>): OverallAttendance {
            val present = subjects.sumOf { it.presentHours }
            val absent = subjects.sumOf { it.absentHours }
            val excused = subjects.sumOf { it.excusedHours }
            val late = subjects.sumOf { it.lateHours }
            val obligation = subjects.sumOf { it.schoolObligationHours }
            return OverallAttendance(
                subjects = subjects.sortedBy { it.percentage },
                presentHours = present,
                absentHours = absent,
                excusedHours = excused,
                lateHours = late,
                schoolObligationHours = obligation
            )
        }
    }
}
