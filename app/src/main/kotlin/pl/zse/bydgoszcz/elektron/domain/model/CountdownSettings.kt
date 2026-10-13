package pl.zse.bydgoszcz.elektron.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import pl.zse.bydgoszcz.elektron.domain.util.AppClock

data class CountdownSettings(
    val enabled: Boolean = false,
    val title: String = "Wakacje",
    val targetDate: LocalDate = defaultTargetDate()
) {
    /** Liczba wszystkich dni pozostałych do wydarzenia (0 jeśli dzisiaj lub w przeszłości). */
    val daysRemaining: Long
        get() = daysRemainingFrom(AppClock.today())

    fun daysRemainingFrom(today: LocalDate): Long {
        return if (targetDate.isAfter(today)) {
            ChronoUnit.DAYS.between(today, targetDate)
        } else {
            0L
        }
    }

    /** Liczba dni szkolnych/roboczych (poniedziałek - piątek) pozostałych do daty docelowej. */
    val schoolDaysRemaining: Long
        get() = schoolDaysRemainingFrom(AppClock.today())

    fun schoolDaysRemainingFrom(today: LocalDate): Long {
        if (!targetDate.isAfter(today)) return 0L
        var count = 0L
        var cur = today.plusDays(1)
        while (!cur.isAfter(targetDate)) {
            if (cur.dayOfWeek != DayOfWeek.SATURDAY && cur.dayOfWeek != DayOfWeek.SUNDAY) {
                count++
            }
            cur = cur.plusDays(1)
        }
        return count
    }

    companion object {
        fun defaultTargetDate(today: LocalDate = AppClock.today()): LocalDate {
            val endOfSchool = LocalDate.of(today.year, 6, 26)
            return if (today.isAfter(endOfSchool)) {
                LocalDate.of(today.year + 1, 6, 26)
            } else {
                endOfSchool
            }
        }

        /** Wyznacza najbliższe wystąpienie danej daty w roku bieżącym lub następnym. */
        private fun nextOccurrence(month: Int, day: Int, today: LocalDate): LocalDate {
            val thisYear = LocalDate.of(today.year, month, day)
            return if (today.isAfter(thisYear)) LocalDate.of(today.year + 1, month, day) else thisYear
        }

        fun getPresets(today: LocalDate = AppClock.today()): List<Pair<String, LocalDate>> {
            return listOf(
                "Wakacje" to nextOccurrence(6, 26, today),
                "Ferie zimowe" to nextOccurrence(2, 2, today),
                "Koniec I semestru" to nextOccurrence(1, 23, today),
                "Egzaminy zawodowe" to nextOccurrence(6, 2, today),
                "100-dniówka" to nextOccurrence(1, 16, today)
            )
        }

        fun formatDays(count: Long): String {
            val mod10 = count % 10
            val mod100 = count % 100
            val word = when {
                count == 1L -> "dzień"
                mod10 in 2..4 && mod100 !in 12..14 -> "dni"
                else -> "dni"
            }
            return "$count $word"
        }

        fun formatSchoolDays(count: Long): String {
            val mod10 = count % 10
            val mod100 = count % 100
            val word = when {
                count == 1L -> "dzień szkolny"
                mod10 in 2..4 && mod100 !in 12..14 -> "dni szkolne"
                else -> "dni szkolnych"
            }
            return "$count $word"
        }
    }
}
