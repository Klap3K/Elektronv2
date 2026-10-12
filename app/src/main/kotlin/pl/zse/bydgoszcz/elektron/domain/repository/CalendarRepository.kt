package pl.zse.bydgoszcz.elektron.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.domain.model.VulcanRegistrationInput
import java.time.LocalDate

interface CalendarRepository {
    /** URL subskrypcji iCal (.ics) z Dziennika VULCAN. */
    val calendarUrl: Flow<String?>
    suspend fun setCalendarUrl(url: String?)

    /** Znacznik czasu ostatniej udanej synchronizacji (epoch seconds). */
    val lastSyncEpochSeconds: Flow<Long?>

    /** Błąd ostatniej próby synchronizacji, jeśli wystąpił. */
    val lastSyncError: Flow<String?>

    /** Obserwuje wydarzenia dla konkretnego dnia. */
    fun observeEventsForDate(date: LocalDate): Flow<List<SchoolEvent>>

    /** Obserwuje wydarzenia dla zakresu dat (np. dla tygodnia). */
    fun observeEventsForRange(from: LocalDate, to: LocalDate): Flow<List<SchoolEvent>>

    /** Obserwuje nadchodzące wydarzenia (od dzisiaj, posortowane, z limitem). */
    fun observeUpcomingEvents(limit: Int = 10): Flow<List<SchoolEvent>>

    /** Zarejestrowane konto w systemie eduVULCAN / VULCAN. */
    val vulcanAccount: Flow<VulcanAccount?>

    /** Zapisuje aktywną sesję eduVULCAN (po zalogowaniu hasłem) i wykonuje synchronizację sprawdzianów oraz frekwencji. */
    suspend fun connectEduVulcan(session: EduVulcanSession): Result<VulcanAccount>

    /** Loguje się hasłem do eduVULCAN w tle i pobiera sprawdziany oraz frekwencję. */
    suspend fun loginEduVulcanWithPassword(username: String, password: String): Result<VulcanAccount>

    /** Rejestruje konto VULCAN za pomocą starszego Tokenu, Symbolu i PIN-u. */
    suspend fun registerVulcan(input: VulcanRegistrationInput): Result<VulcanAccount>

    /** Odłącza konto eduVULCAN / VULCAN i usuwa powiązane dane uwierzytelniające. */
    suspend fun clearVulcanAccount()

    /** Pobiera sprawdziany i frekwencję z połączonego konta eduVULCAN lub skonfigurowanego URL iCal, parsuje i zapisuje do bazy. */
    suspend fun sync(): Result<Int>

    /** Czyści zapisane wydarzenia i resetuje konfigurację. */
    suspend fun clear()
}
