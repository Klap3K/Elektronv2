package pl.zse.bydgoszcz.elektron.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Informacja o dyżurze klasowym ucznia.
 */
data class ClassroomDuty(
    val studentNumber: Int?,
    val classSize: Int,
    val currentDutyNumber1: Int,
    val currentDutyNumber2: Int,
    val isDutyToday: Boolean,
    val isDutyThisWeek: Boolean,
    val daysUntilDuty: Int?,
    val duties: List<String> = DEFAULT_DUTIES
) {
    companion object {
        val DEFAULT_DUTIES = listOf(
            "Przygotowanie i starcie tablicy przed lekcją",
            "Przewietrzenie sali podczas przerw",
            "Zgłoszenie nieobecności nauczycielowi w razie potrzeby",
            "Dbanie o porządek w klasie i sprawdzenie czystości po zakończeniu zajęć"
        )

        /**
         * Oblicza stan dyżuru dla podanej daty [date], numeru ucznia [studentNumber] i liczebności klasy [classSize].
         */
        fun calculate(date: LocalDate, studentNumber: Int?, classSize: Int = 30): ClassroomDuty {
            val safeClassSize = classSize.coerceAtLeast(1)
            // Wyznacz początek roku szkolnego dla daty
            val schoolYearStartYear = if (date.monthValue < 9) date.year - 1 else date.year
            val schoolStartMonday = LocalDate.of(schoolYearStartYear, 9, 1)
                .with(DayOfWeek.MONDAY)

            val currentMonday = date.with(DayOfWeek.MONDAY)
            val weekIndex = ChronoUnit.WEEKS.between(schoolStartMonday, currentMonday).coerceAtLeast(0)

            // Dwoje uczniów na tydzień
            val duty1 = ((weekIndex * 2) % safeClassSize).toInt() + 1
            val duty2 = ((weekIndex * 2 + 1) % safeClassSize).toInt() + 1

            val isSchoolDay = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
            val isDutyThisWeek = studentNumber != null && (studentNumber == duty1 || studentNumber == duty2)
            val isDutyToday = isDutyThisWeek && isSchoolDay

            val daysUntilDuty = if (studentNumber == null) {
                null
            } else if (isDutyToday) {
                0
            } else if (isDutyThisWeek) {
                if (date.dayOfWeek == DayOfWeek.SATURDAY) 2 else 1
            } else {
                // Oblicz ile tygodni do kolejki ucznia
                // Pozycja ucznia w cyklu par:
                val studentIndexInCycle = (studentNumber - 1).coerceAtLeast(0)
                val targetCycleWeek = studentIndexInCycle / 2
                val currentCycleWeek = (weekIndex % ((safeClassSize + 1) / 2)).toInt()
                val weeksDiff = if (targetCycleWeek >= currentCycleWeek) {
                    targetCycleWeek - currentCycleWeek
                } else {
                    ((safeClassSize + 1) / 2) - currentCycleWeek + targetCycleWeek
                }
                val daysToNextMonday = (DayOfWeek.MONDAY.value - date.dayOfWeek.value + 7) % 7
                val totalDays = daysToNextMonday + (weeksDiff - 1).coerceAtLeast(0) * 7
                totalDays.coerceAtLeast(1)
            }

            return ClassroomDuty(
                studentNumber = studentNumber,
                classSize = safeClassSize,
                currentDutyNumber1 = duty1,
                currentDutyNumber2 = duty2,
                isDutyToday = isDutyToday,
                isDutyThisWeek = isDutyThisWeek,
                daysUntilDuty = daysUntilDuty,
                duties = DEFAULT_DUTIES
            )
        }
    }
}
