package pl.zse.bydgoszcz.elektron.domain.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Co pokazać "teraz" (strona główna, widżety, kafelek, plan) - jedna reguła dla wszystkich:
 *  - w trakcie lekcji: ile zostało do końca + postęp,
 *  - w przerwie (luka między lekcjami do [BREAK_MAX_MINUTES]): ile do następnej + postęp przerwy,
 *  - przed pierwszą lekcją i w okienku (dłuższa luka, np. lekcja innej grupy): czas do lekcji
 *    dopiero od [COUNTDOWN_MINUTES] przed nią.
 * Dawniej każda luka była "przerwą" - w okienku przed zastępstwem przez całą lekcję widniało
 * "52 min do lekcji". Lekcje muszą być po filtrze grup.
 */
object LessonClock {

    const val COUNTDOWN_MINUTES = 30L
    const val BREAK_MAX_MINUTES = 30L

    sealed interface Status
    /** Trwa [lesson]. */
    data class During(val lesson: Lesson, val minutesLeft: Long, val progress: Float) : Status
    /** Przerwa przed [next] (od [from]). */
    data class Break(val next: Lesson, val from: LocalTime, val minutesUntil: Long, val progress: Float) : Status
    /** Przed [next] (dziś albo w kolejnym dniu); [minutesUntil] tylko w ostatnich 30 min. */
    data class Before(val next: Lesson, val minutesUntil: Long?) : Status
    data object Nothing : Status

    fun status(lessons: List<Lesson>, now: LocalDateTime): Status {
        val today = now.toLocalDate()
        val time = now.toLocalTime()
        // Lekcje, z których klasa jest zwolniona ("zwolnieni do domu", "przychodzą później"), nie trwają
        // i nie są następne - jak w widżetach, przypomnieniach i ekranie startowym.
        val held = lessons.filter(SubstitutionDisplay::takesPlace)
        val todayLessons = held.filter { it.date == today }.sortedBy { it.timeFrom }

        todayLessons.firstOrNull { time >= it.timeFrom && time < it.timeTo }?.let { l ->
            return During(l, minutesCeil(time, l.timeTo), progress(l.timeFrom, l.timeTo, time))
        }
        val next = todayLessons.firstOrNull { it.timeFrom >= time }
        if (next != null) {
            val until = minutesCeil(time, next.timeFrom)
            val prev = todayLessons.lastOrNull { it.timeTo <= time }
            if (prev != null && Duration.between(prev.timeTo, next.timeFrom).toMinutes() <= BREAK_MAX_MINUTES) {
                return Break(next, prev.timeTo, until, progress(prev.timeTo, next.timeFrom, time))
            }
            return Before(next, until.takeIf { it <= COUNTDOWN_MINUTES })
        }
        val later = held.filter { it.date > today }.minWithOrNull(compareBy({ it.date }, { it.timeFrom }))
        return if (later != null) Before(later, null) else Nothing
    }

    /** Pozostałe minuty zaokrąglone w górę: przy 30 s do końca - "1 min", nie "0 min". */
    fun minutesCeil(from: LocalTime, to: LocalTime): Long {
        val s = Duration.between(from, to).seconds
        return if (s <= 0) 0 else (s + 59) / 60
    }

    fun progress(from: LocalTime, to: LocalTime, now: LocalTime): Float {
        val total = Duration.between(from, to).seconds.coerceAtLeast(1)
        return Duration.between(from, now).seconds.coerceIn(0, total).toFloat() / total
    }
}
