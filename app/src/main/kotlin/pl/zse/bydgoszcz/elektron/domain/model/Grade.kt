package pl.zse.bydgoszcz.elektron.domain.model

import kotlin.math.roundToInt

/**
 * Pojedyncza ocena cząstkowa (lub podsumowanie przedmiotu) z dziennika eduVULCAN / dodana ręcznie.
 */
data class StudentGrade(
    val id: String,
    val subject: String,
    val value: String,
    val weight: Double = 1.0,
    val category: String? = null,
    val description: String? = null,
    val teacher: String? = null,
    val dateText: String? = null,
    val periodNumber: Int = 1,
    val isActivePeriod: Boolean = true,
    val isSuperseded: Boolean = false,
    val apiSubjectAverage: Double? = null,
    val proposedGrade: String? = null,
    val finalGrade: String? = null,
    val updatedAtEpochSeconds: Long = 0L
) {
    /** Wartość liczbowa w skali 1.0–6.0 (lub null dla np/nb/+/procentów). */
    val numericValue: Double?
        get() = GradeCalculator.parseNumericGrade(value)
}

/**
 * Wirtualna ocena w symulatorze ocen „Co jeśli”.
 * Nie jest zapisywana trwale w bazie danych, służy do natychmiastowego
 * podglądu wpływu nowej oceny na średnią ważoną danego przedmiotu i średnią ogólną.
 */
data class VirtualGrade(
    val id: String = java.util.UUID.randomUUID().toString(),
    val subject: String,
    val value: String,
    val weight: Double = 1.0,
    val description: String = "Symulacja"
) {
    /** Wartość liczbowa w skali 1.0–6.0 (lub null dla niepoprawnych wpisów). */
    val numericValue: Double?
        get() = GradeCalculator.parseNumericGrade(value)
}

/**
 * Podsumowanie ocen z jednego przedmiotu w wybranym okresie klasyfikacyjnym (semestrze).
 */
data class SubjectGradesSummary(
    val subject: String,
    val periodNumber: Int,
    val grades: List<StudentGrade>,
    val average: Double?,
    val proposedGrade: String?,
    val finalGrade: String?
)

object GradeCalculator {

    private val WORD_GRADES = mapOf(
        "celujący" to 6.0, "celujacy" to 6.0, "celująca" to 6.0, "cel" to 6.0,
        "bardzo dobry" to 5.0, "bardzo dobra" to 5.0, "bdb" to 5.0,
        "dobry" to 4.0, "dobra" to 4.0, "db" to 4.0,
        "dostateczny" to 3.0, "dostateczna" to 3.0, "dst" to 3.0,
        "dopuszczający" to 2.0, "dopuszczajacy" to 2.0, "dopuszczająca" to 2.0, "dop" to 2.0,
        "mierny" to 2.0, "mierna" to 2.0,
        "niedostateczny" to 1.0, "niedostateczna" to 1.0, "ndst" to 1.0
    )

    private val PERCENT_SUFFIX_REGEX = Regex("""\s*\(\s*\d{1,3}(?:[.,]\d+)?\s*%\s*\)$""")
    private val SLASH_GRADE_REGEX = Regex("""^([1-6])\s*/\s*([1-6])$""")
    private val NUMERIC_GRADE_REGEX = Regex("""^([1-6])(?:[.,](\d+))?([+-])?$""")

    /**
     * Zamienia zapis oceny (np. "4+", "5-", "3,5", "4/5", "bardzo dobry") na wartość liczbową 1.0..6.0.
     * Zwraca `null` dla ocen procentowych ("75%"), znaków aktywności ("+", "-") oraz "np", "nb", "bz".
     */
    fun parseNumericGrade(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw.trim()
        // Usuń ewentualny dopisek procentowy na końcu np. "4 (78%)"
        cleaned = cleaned.replace(PERCENT_SUFFIX_REGEX, "").trim()
        val lower = cleaned.lowercase()
        if (lower in WORD_GRADES) return WORD_GRADES[lower]
        if (lower.contains('%') || lower == "np" || lower == "nb" || lower == "bz" || lower == "zw") {
            return null
        }

        SLASH_GRADE_REGEX.matchEntire(lower)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return minOf(a, b).toDouble()
        }

        NUMERIC_GRADE_REGEX.matchEntire(lower)?.let { m ->
            var v = m.groupValues[1].toDouble()
            val dec = m.groupValues[2]
            val sign = m.groupValues[3]
            if (dec.isNotEmpty()) {
                v += ("0.$dec").toDoubleOrNull() ?: 0.0
            } else if (sign == "+") {
                v += 0.5
            } else if (sign == "-") {
                v -= 0.25
            }
            return v.coerceIn(1.0, 6.0)
        }

        return null
    }

    /**
     * Grupuje listę ocen po przedmiocie i wylicza średnią ważoną (lub pobiera średnią z API Vulcana).
     */
    fun summarizeBySubject(
        allGrades: List<StudentGrade>,
        periodNumber: Int? = null
    ): List<SubjectGradesSummary> {
        if (allGrades.isEmpty()) return emptyList()

        val targetPeriod = periodNumber
            ?: allGrades.firstOrNull { it.isActivePeriod }?.periodNumber
            ?: allGrades.maxOfOrNull { it.periodNumber }
            ?: 1

        val filtered = allGrades.filter { it.periodNumber == targetPeriod }
        return filtered
            .groupBy { it.subject }
            .map { (subject, items) ->
                val visibleGrades = items.filter { it.value.isNotBlank() }
                val apiAvg = items.firstNotNullOfOrNull { it.apiSubjectAverage?.takeIf { avg -> avg > 0.0 } }
                val proposed = items.firstNotNullOfOrNull { it.proposedGrade?.takeIf { p -> p.isNotBlank() } }
                val finalG = items.firstNotNullOfOrNull { it.finalGrade?.takeIf { f -> f.isNotBlank() } }

                val calculatedAvg = apiAvg ?: run {
                    var weightedSum = 0.0
                    var weightTotal = 0.0
                    for (g in visibleGrades) {
                        if (g.isSuperseded) continue
                        val num = g.numericValue ?: continue
                        val w = if (g.weight > 0.0) g.weight else 1.0
                        weightedSum += num * w
                        weightTotal += w
                    }
                    if (weightTotal > 0.0) {
                        ((weightedSum / weightTotal) * 100.0).roundToInt() / 100.0
                    } else {
                        null
                    }
                }

                SubjectGradesSummary(
                    subject = subject,
                    periodNumber = targetPeriod,
                    grades = visibleGrades,
                    average = calculatedAvg,
                    proposedGrade = proposed,
                    finalGrade = finalG
                )
            }
            .sortedBy { it.subject.lowercase() }
    }

    /**
     * Wylicza ogólną średnią ze wszystkich przedmiotów (bez Zachowania).
     */
    fun overallAverage(summaries: List<SubjectGradesSummary>): Double? {
        val valid = summaries
            .filter { !it.subject.equals("Zachowanie", ignoreCase = true) }
            .mapNotNull { summary ->
                summary.finalGrade?.let(::parseNumericGrade)
                    ?: summary.proposedGrade?.let(::parseNumericGrade)
                    ?: summary.average
            }
        if (valid.isEmpty()) return null
        return ((valid.average()) * 100.0).roundToInt() / 100.0
    }

    /**
     * Oblicza symulowaną średnią ważoną przedmiotu z uwzględnieniem ocen wirtualnych („Co jeśli”).
     */
    fun calculateWeightedAverageWithVirtual(
        existingGrades: List<StudentGrade>,
        virtualGrades: List<VirtualGrade>
    ): Double? {
        var weightedSum = 0.0
        var weightTotal = 0.0
        for (g in existingGrades) {
            if (g.isSuperseded) continue
            val num = g.numericValue ?: continue
            val w = if (g.weight > 0.0) g.weight else 1.0
            weightedSum += num * w
            weightTotal += w
        }
        for (vg in virtualGrades) {
            val num = vg.numericValue ?: continue
            val w = if (vg.weight > 0.0) vg.weight else 1.0
            weightedSum += num * w
            weightTotal += w
        }
        return if (weightTotal > 0.0) {
            ((weightedSum / weightTotal) * 100.0).roundToInt() / 100.0
        } else {
            null
        }
    }
}
