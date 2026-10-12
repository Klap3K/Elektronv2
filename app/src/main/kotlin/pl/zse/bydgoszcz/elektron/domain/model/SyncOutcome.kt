package pl.zse.bydgoszcz.elektron.domain.model

/**
 * Wynik jednej synchronizacji (SyncCoordinator): które źródła się pobrały, które zawiodły.
 * [interrupted] - praca przerwana przez zmianę klasy / reset danych (to nie porażka ani
 * anulowanie wołającego; dane i tak pobierze synchronizacja nowej klasy).
 *
 * Komunikat po synchronizacji: liczą się plan i zastępstwa (dane, które muszą być aktualne);
 * ogłoszenia i lista klas - nie. Dawniej udany sync czegokolwiek kasował komunikat, więc
 * niedziałająca strona zastępstw była niewidoczna.
 */
data class SyncOutcome(
    val succeeded: Set<String> = emptySet(),
    val failures: Map<String, Throwable> = emptyMap(),
    val interrupted: Boolean = false
) {
    /**
     * Komunikat dla użytkownika: błąd planu/zastępstw, a gdy nie pobrało się nic - przyczyna
     * pierwszej porażki (np. brak internetu). null - wszystko ważne się pobrało.
     */
    fun userError(): String? = errorMessage(failures)
        ?: if (succeeded.isEmpty() && failures.isNotEmpty()) SyncErrors.userMessage(failures.values.first()) else null

    /**
     * Ponowienie pracy w tle tylko przy błędach. Przebieg bez żadnego źródła do sprawdzenia
     * (w nocy wszystko świeże) to sukces - dawniej "nic nie pobrano" = Result.retry(),
     * czyli kilka dodatkowych wybudzeń co cykl.
     */
    val shouldRetry: Boolean get() = !interrupted && succeeded.isEmpty() &&
        // Niepełna strona zastępstw: dane są zapisane, ponowienie pobrałoby tę samą stronę.
        failures.values.any { it !is IncompleteSchoolDataException }

    companion object {
        const val TIMETABLE = "timetable"
        const val SUBSTITUTIONS = "subs"
        const val ANNOUNCEMENTS = "anns"
        const val SIDEBAR = "sidebar"
        const val CALENDAR = "calendar"

        val INTERRUPTED = SyncOutcome(interrupted = true)

        /**
         * Czy przebieg odświeżył dane, które muszą być aktualne (plan albo zastępstwa) - tylko wtedy
         * zapisujemy "zsynchronizowano teraz". Dawniej wystarczyła lista klas albo RSS, więc przy
         * niedziałającej stronie planu i zastępstw znikał baner o nieaktualnych danych.
         */
        fun freshDataLoaded(succeeded: Set<String>): Boolean =
            TIMETABLE in succeeded || SUBSTITUTIONS in succeeded

        /** null - plan i zastępstwa bez błędów. */
        fun errorMessage(failures: Map<String, Throwable>): String? {
            val important = failures[SUBSTITUTIONS] ?: failures[TIMETABLE] ?: return null
            val what = when {
                failures.containsKey(SUBSTITUTIONS) && failures.containsKey(TIMETABLE) -> "planu i zastępstw"
                failures.containsKey(SUBSTITUTIONS) -> "zastępstw"
                else -> "planu lekcji"
            }
            return when {
                important is SchoolPageChangedException -> SchoolPageChangedException.USER_MESSAGE
                // Same niepełne zastępstwa: odświeżenie się udało - jedno zdanie zamiast "Nie udało się… Nie udało się…".
                important is IncompleteSchoolDataException && TIMETABLE !in failures -> IncompleteSchoolDataException.USER_MESSAGE
                else -> "Nie udało się odświeżyć $what. ${SyncErrors.userMessage(important)}"
            }
        }
    }
}
