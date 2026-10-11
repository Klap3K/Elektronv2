package pl.zse.bydgoszcz.elektron.domain.sync

import pl.zse.bydgoszcz.elektron.domain.model.IncompleteSchoolDataException
import androidx.room.withTransaction
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome
import pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SubstitutionsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import pl.zse.bydgoszcz.elektron.widget.WidgetUpdater
import pl.zse.bydgoszcz.elektron.work.LessonReminderScheduler
import pl.zse.bydgoszcz.elektron.work.NotificationSink
import pl.zse.bydgoszcz.elektron.work.SubstitutionNotifier
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Co pobrać: źródła (klucze z SyncOutcome) i od jakiej daty plan (tydzień oglądany w Planie).
 * Dwa żądania są "tą samą pracą" tylko przy równych źródłach i dacie.
 */
data class SyncRequest(val sources: Set<String>, val anchorDate: LocalDate = LocalDate.now(), val background: Boolean = false) {
    companion object {
        val ALL_SOURCES = setOf(SyncOutcome.SIDEBAR, SyncOutcome.TIMETABLE, SyncOutcome.SUBSTITUTIONS, SyncOutcome.ANNOUNCEMENTS)
        fun full(anchorDate: LocalDate = LocalDate.now(), background: Boolean = false) = SyncRequest(ALL_SOURCES, anchorDate, background)
        fun of(vararg sources: String, anchorDate: LocalDate = LocalDate.now()) = SyncRequest(sources.toSet(), anchorDate)
    }
}

/**
 * Jedyne miejsce synchronizacji ze stronami szkoły (dawniej sześć osobnych wersji: SyncWorker,
 * wybór klasy i odświeżanie na czterech ekranach - każda inaczej obsługiwała błędy, flagi
 * i przypomnienia, a równoległe przebiegi gasiły sobie flagę "trwa synchronizacja").
 *
 * - Praca idzie we własnym zasięgu koordynatora, jedna naraz (Mutex). Wywołanie z takim samym
 *   [SyncRequest] jak trwająca/czekająca praca dołącza do niej zamiast pobierać drugi raz.
 * - Anulowanie wołającego (zamknięty ekran, zatrzymany worker) nie przerywa pracy, na którą
 *   czekają inni; praca kończy się, gdy odejdzie ostatni oczekujący.
 * - Zmiana klasy / reset danych przerywa trwające prace (ich wołający dostają
 *   [SyncOutcome.INTERRUPTED]) i dopiero potem - pod tą samą blokadą - czyści bazę.
 * - Po każdej pracy, w jednym miejscu: markLoaded, lastSyncAt, lastSyncError, powiadomienia,
 *   przypomnienia i widżety.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val settings: SettingsRepository,
    private val timetableRepo: TimetableRepository,
    private val substitutionsRepo: SubstitutionsRepository,
    private val announcementsRepo: AnnouncementsRepository,
    private val notificationsRepo: NotificationsRepository,
    private val substitutionNotifier: SubstitutionNotifier,
    private val sink: NotificationSink,
    private val widgetUpdater: WidgetUpdater,
    private val reminders: LessonReminderScheduler,
    private val db: AppDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workMutex = Mutex()
    private val lock = Any()
    private val flights = mutableListOf<Flight>()
    private val running = MutableStateFlow(0)
    /** Zegar do reguły nocnej synchronizacji w tle (testy podają stałą godzinę). */
    internal var localTime: () -> java.time.LocalTime = { java.time.LocalTime.now() }
    val operationError = MutableStateFlow<String?>(null)
    fun consumeOperationError() { operationError.value = null }

    /** Praca: [request] (null - zmiana klasy), liczba czekających wołających. */
    private class Flight(val request: SyncRequest?, val pinned: Boolean, val deferred: Deferred<SyncOutcome>) {
        var waiters = 0
    }

    /**
     * Trwa synchronizacja - tylko w pamięci i ustawiana wyłącznie tutaj (licznik prac, więc nikt
     * nie gasi cudzej). Nowy proces zawsze zaczyna od false - bez zerowania przy starcie.
     */
    val isSyncing: Flow<Boolean> = running.map { it > 0 }.distinctUntilChanged()
    fun observeSourceError(source: String): Flow<String?> = db.syncStateDao().observe("source_$source")
        .map { it?.takeIf { state -> state.status == "error" }?.message }
    fun observeSourceLastSuccess(source: String): Flow<Instant?> = combine(
        db.syncStateDao().observe("source_${source}_success"), db.syncStateDao().observe("source_$source")
    ) { success, latest -> (success ?: latest?.takeIf { it.status == "ok" })?.lastSyncEpochSeconds?.let(Instant::ofEpochSecond) }


    /**
     * Pierwsza synchronizacja wybranej klasy jeszcze trwa / jeszcze się nie odbyła: plan nie jest
     * oznaczony jako pobrany (zmiana klasy i reset czyszczą "loaded" i komunikat błędu), a przy
     * tym trwa synchronizacja albo nie ma komunikatu o błędzie. Z trwałych danych - po zabiciu
     * procesu między wyczyszczeniem bazy a pobraniem planu UI nadal pokazuje ładowanie, a nie
     * "brak lekcji". Po nieudanej próbie (np. bez internetu) - false, więc ekran grup pokazuje
     * błąd z "Spróbuj ponownie" zamiast kręcić się bez końca.
     */
    val initialSyncPending: Flow<Boolean> = combine(
        settings.selectedClassId,
        notificationsRepo.observeLoadedResources(),
        notificationsRepo.observeLastSyncError(),
        running
    ) { cid, loaded, error, active ->
        cid != null && SyncOutcome.TIMETABLE !in loaded && (active > 0 || error == null)
    }.distinctUntilChanged()

    /**
     * Synchronizuje [request] (albo dołącza do takiej samej trwającej pracy) i zwraca wynik.
     * Anulowanie wołającego leci dalej; przerwanie przez zmianę klasy = [SyncOutcome.INTERRUPTED].
     */
    suspend fun sync(request: SyncRequest): SyncOutcome {
        val flight = synchronized(lock) {
            val existing = flights.firstOrNull { it.request == request && it.deferred.isActive }
            (existing ?: newFlight(request, pinned = false) { doSync(request, notify = true) })
                .also { it.waiters++ }
        }
        flight.deferred.start()
        try {
            return flight.deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()      // anulowano NAS - dalej
            return SyncOutcome.INTERRUPTED                 // anulowano pracę (zmiana klasy / reset)
        } finally {
            release(flight)
        }
    }

    /** Wybór klasy i pierwsza synchronizacja jej danych (praca nie zależy od żadnego ekranu). */
    fun selectClass(classId: String) = startClassChange(classId, reset = false)

    /**
     * Czyści lokalną bazę (cache ze strony szkoły + stan synchronizacji) i pobiera dane od nowa.
     * Ustawienia (DataStore) zostają. Wyczyszczony stan = pierwsza synchronizacja bez powiadomień.
     */
    fun resetCacheAndResync(classId: String) = startClassChange(classId, reset = true)

    private fun startClassChange(classId: String, reset: Boolean) {
        synchronized(lock) {
            val previous = flights.toList()
            previous.forEach { it.deferred.cancel() }      // czekający dostaną INTERRUPTED
            newFlight(SyncRequest.full(), pinned = true) {
                // Dopiero gdy przerwane prace się zakończą (łącznie ze sprzątaniem) - inaczej
                // clearAllTables mogłoby trafić w środek zapisu.
                previous.forEach { it.deferred.join() }
                classChange(classId, reset)
            }.deferred.start()
        }
    }

    private fun newFlight(request: SyncRequest?, pinned: Boolean, block: suspend () -> SyncOutcome): Flight {
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            workMutex.withLock {
                running.update { it + 1 }
                try {
                    block()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.w(TAG, "Nie udało się zakończyć synchronizacji", e)
                    val message = pl.zse.bydgoszcz.elektron.domain.model.SyncErrors.userMessage(e)
                    if (pinned) operationError.value = "Nie udało się zapisać zmiany lub odczytać danych. Spróbuj ponownie."
                    pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable {
                        notificationsRepo.setLastSyncError(message)
                    }
                    SyncOutcome(failures = (request?.sources ?: SyncRequest.ALL_SOURCES).associateWith { e })
                } finally { running.update { it - 1 } }
            }
        }
        val flight = Flight(request, pinned, deferred)
        flights += flight
        deferred.invokeOnCompletion { synchronized(lock) { flights.remove(flight) } }
        return flight
    }

    private fun release(flight: Flight) = synchronized(lock) {
        flight.waiters--
        // Ostatni oczekujący odszedł (np. zamknięty ekran) - praca nikomu niepotrzebna.
        if (flight.waiters <= 0 && !flight.pinned && flight.deferred.isActive) flight.deferred.cancel()
    }

    private suspend fun classChange(classId: String, reset: Boolean): SyncOutcome {
        settings.setSelectedClassId(classId)
        if (reset) announcementsRepo.resetArchive { db.withTransaction {
            val sql = db.openHelper.writableDatabase
            listOf("lesson_groups", "lessons", "substitutions", "school_classes", "teachers", "rooms",
                "notifications", "sync_state").forEach { sql.execSQL("DELETE FROM `$it`") }
            sql.execSQL("DELETE FROM announcements WHERE isFavorite = 0")
        } }
        // Zapis wyboru musi się udać przed czyszczeniem metadanych starej klasy.
        // Następnie synchronizacja nowej klasy ustala jej stan pobrania.
        notificationsRepo.clearLoaded()
        notificationsRepo.setLastSyncError(null)
        // Pierwsza synchronizacja po zmianie klasy nie wysyła powiadomień - tylko zapisuje stan.
        return doSync(SyncRequest.full(), notify = false)
    }

    /** Jedna praca: źródła równolegle, potem wspólne kroki. Wołana pod [workMutex]. */
    private suspend fun doSync(request: SyncRequest, notify: Boolean): SyncOutcome {
        val classId = settings.selectedClassId.first()
        val now = Instant.now().epochSecond
        fun sourceKey(source: String): String = if (source == SyncOutcome.TIMETABLE)
            "source_${source}_${classId}"
            else "source_$source"
        val loaded = notificationsRepo.observeLoadedResources().first()
        val time = localTime()
        val sources = request.sources.filterTo(mutableSetOf()) { source ->
            val last = db.syncStateDao().get(sourceKey(source))
            // Ogłoszenia: odstęp także od nieudanej próby - trwale nieczytelny kanał RSS był
            // pobierany przy każdym przebiegu (co 15 min, także w nocy). Plan i zastępstwa po
            // błędzie nadal są ponawiane od razu.
            val announcements = source == SyncOutcome.ANNOUNCEMENTS
            // Niepełna strona zastępstw (kilka nieczytelnych wierszy) to pobrane dane z ostrzeżeniem,
            // a nie awaria: zwykły odstęp, zamiast pobierania co 15 min także w nocy.
            val reference = if (announcements) last?.lastSyncEpochSeconds
                else last?.takeIf { it.status == "ok" || IncompleteSchoolDataException.isWarning(it.message) }?.lastSyncEpochSeconds
            // Nigdy nie pobrane - od razu (także ogłoszenia, dopóki nie było nieudanej próby).
            val neverLoaded = source != SyncOutcome.SIDEBAR && source !in loaded && !(announcements && last?.status == "error")
            !request.background || neverLoaded || BackgroundSyncPolicy.isDue(source, reference, now, time)
        }
        val failed = ConcurrentHashMap<String, Throwable>()
        val ok = ConcurrentHashMap.newKeySet<String>()
        fun Result<Unit>.record(key: String) {
            onSuccess { ok += key }
            onFailure { Log.w(TAG, "$key: synchronizacja nieudana", it); failed[key] = it }
        }
        try {
            coroutineScope {
                val sidebarJob = if (SyncOutcome.SIDEBAR in sources) launch {
                    timetableRepo.syncSidebar().record(SyncOutcome.SIDEBAR)
                } else null
                val timetableJob = if (SyncOutcome.TIMETABLE in sources && classId != null) launch {
                    timetableRepo.syncTimetable(classId, request.anchorDate).record(SyncOutcome.TIMETABLE)
                } else null
                // Zastępstwa po zapisie planu: wpisy bez klasy (zajęcia łączone) są przypisywane według
                // zapisanego planu - przy pierwszej synchronizacji (wybór klasy, czyszczenie danych)
                // plan jeszcze się zapisywał i takie zastępstwa znikały do kolejnego pobrania. Lista
                // nauczycieli (sidebar) daje pełne nazwiska do dopasowania - bez niej to samo.
                if (SyncOutcome.SUBSTITUTIONS in sources) launch {
                    sidebarJob?.join()
                    timetableJob?.join()
                    substitutionsRepo.syncAll().record(SyncOutcome.SUBSTITUTIONS)
                }
                if (SyncOutcome.ANNOUNCEMENTS in sources) launch { announcementsRepo.syncAll().record(SyncOutcome.ANNOUNCEMENTS) }
            }
            val outcome = SyncOutcome(ok.toSet(), failed.toMap())
            // Zastępstwa zapisane mimo kilku nieczytelnych wierszy (repozytorium zgłasza to wyjątkiem
            // po zapisie): dla powiadomień, "sprawdzono" i stanu pobrania to udane pobranie.
            // Dawniej taka strona blokowała powiadomienia o zastępstwach (także z pusha).
            val subsWritten = SyncOutcome.SUBSTITUTIONS in outcome.succeeded ||
                outcome.failures[SyncOutcome.SUBSTITUTIONS] is IncompleteSchoolDataException
            val written = if (subsWritten) outcome.succeeded + SyncOutcome.SUBSTITUTIONS else outcome.succeeded
            if (SyncOutcome.TIMETABLE in outcome.succeeded && classId != null) {
                pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable { dropStaleGroupChoices(classId) }
                    .onFailure { Log.w(TAG, "Nie udało się sprawdzić wyborów grup", it) }
            }
            for (source in outcome.succeeded) {
                db.syncStateDao().upsert(pl.zse.bydgoszcz.elektron.data.local.SyncStateEntity(
                    sourceKey(source), now, "ok", null))
                db.syncStateDao().upsert(pl.zse.bydgoszcz.elektron.data.local.SyncStateEntity(
                    "${sourceKey(source)}_success", now, "ok", null))
            }
            if (subsWritten && SyncOutcome.SUBSTITUTIONS !in outcome.succeeded) {
                db.syncStateDao().upsert(pl.zse.bydgoszcz.elektron.data.local.SyncStateEntity(
                    "${sourceKey(SyncOutcome.SUBSTITUTIONS)}_success", now, "ok", null))
            }
            for (source in outcome.failures.keys) {
                val previous = db.syncStateDao().get(sourceKey(source))
                if (previous?.status == "ok" && db.syncStateDao().get("${sourceKey(source)}_success") == null)
                    db.syncStateDao().upsert(previous.copy(key = "${sourceKey(source)}_success"))
                db.syncStateDao().upsert(pl.zse.bydgoszcz.elektron.data.local.SyncStateEntity(
                    sourceKey(source), now, "error", SyncOutcome.errorMessage(mapOf(source to outcome.failures.getValue(source))) ?: pl.zse.bydgoszcz.elektron.domain.model.SyncErrors.userMessage(outcome.failures.getValue(source))))
            }
            for (key in listOf(SyncOutcome.TIMETABLE, SyncOutcome.SUBSTITUTIONS, SyncOutcome.ANNOUNCEMENTS)) {
                if (key in written) notificationsRepo.markLoaded(key)
            }
            // Komunikat tylko gdy praca dotyczyła planu albo zastępstw - odświeżenie samych
            // ogłoszeń nie kasuje komunikatu o niedziałającym planie.
            if (SyncOutcome.TIMETABLE in sources || SyncOutcome.SUBSTITUTIONS in sources) {
                val unresolved = listOf(SyncOutcome.TIMETABLE, SyncOutcome.SUBSTITUTIONS).mapNotNull { source ->
                    db.syncStateDao().get(sourceKey(source))?.takeIf { it.status == "error" }?.let { saved ->
                        saved.message ?: SyncOutcome.errorMessage(mapOf(source to java.io.IOException("Poprzednia próba nie powiodła się.")))
                    }
                }.distinct()
                notificationsRepo.setLastSyncError(unresolved.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: outcome.userError())
            }
            if (SyncOutcome.freshDataLoaded(written)) notificationsRepo.setLastSyncAt(Instant.now())
            if (subsWritten || SyncOutcome.ANNOUNCEMENTS in outcome.succeeded) {
                notifyNew(classId, notify, substitutionsLoaded = subsWritten)
            }
            return outcome
        } finally {
            withContext(NonCancellable) {
                // Przypomnienia: czekamy na ustawienie alarmu (proces workera może zaraz zniknąć).
                pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable { reminders.reschedule() }
                    .onFailure { Log.w(TAG, "Nie udało się przeliczyć przypomnień", it) }
                // Przebieg bez pobierania (nic nie było do sprawdzenia, np. w nocy) nie zmienia
                // danych - widżety mają własny harmonogram, więc nie przerysowujemy ich co 15 min.
                if (sources.isNotEmpty()) pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable { widgetUpdater.updateNow() }
                    .onFailure { Log.w(TAG, "Nie udało się odświeżyć widżetów", it) }
            }
        }
    }

    /** Wybór grupy, której nie ma już w nowym planie, wraca do "wszystkie grupy" (patrz [LessonGroups.staleSelections]). */
    private suspend fun dropStaleGroupChoices(classId: String) {
        val monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        val lessons = timetableRepo.getLessonsOnce(classId, monday, monday.plusWeeks(5))
        if (lessons.isEmpty()) return
        for (subject in LessonGroups.staleSelections(lessons, settings.groupSelections(classId).first())) {
            Log.i(TAG, "Grupa $subject zniknęła z planu - pokazuję wszystkie grupy")
            settings.setGroupSelection(classId, subject, null)
        }
    }

    /**
     * Powiadomienia o nowych zastępstwach i ogłoszeniach + zapis zbioru "widzianych".
     * Bez powiadomień, dopóki nie ma wiarygodnego punktu odniesienia: pierwsza synchronizacja
     * po instalacji / resecie (isInitialSyncDone, gotowość zbioru "widzianych" v2) i pierwsza
     * po zmianie klasy ([allowed] = false) tylko zapisują stan.
     */
    private suspend fun notifyNew(classId: String?, allowed: Boolean, substitutionsLoaded: Boolean) {
        // Blokada dzielona z pushami FCM (SubstitutionNotifier.notifyPushed) - bez duplikatów.
        notificationsRepo.withNotifyLock {
            // Wysyłka i zapis "widzianych" jako jeden krok: anulowanie pomiędzy (limit czasu pusha,
            // zmiana klasy) powtarzało przy następnym syncu dźwięk już pokazanych powiadomień.
            withContext(NonCancellable) {
                val initialDone = allowed && notificationsRepo.isInitialSyncDone() && notificationsRepo.isSeenStoreReady()
                val seenSubs = notificationsRepo.getSeenSubstitutionIds()
                val seenFingerprints = notificationsRepo.getSeenSubstitutionFingerprints()
                val seenAnns = notificationsRepo.getSeenAnnouncementIds()
                val currentSubs = substitutionsRepo.getAllFrom(LocalDate.now().minusDays(1))
                val currentAnns = announcementsRepo.getAll()
                if (initialDone) {
                    // Nowe zastępstwa i te, w których szkoła zmieniła same uwagi (np. "za ostatnią lekcję") -
                    // poprawka zastępuje poprzednie powiadomienie tej lekcji (ten sam znacznik).
                    val fresh = currentSubs.filter { it.id !in seenSubs } +
                        pl.zse.bydgoszcz.elektron.domain.model.SubstitutionNotice.changedNotes(currentSubs, seenSubs, seenFingerprints)
                    if (classId != null) substitutionNotifier.notifyFresh(fresh)
                    val freshAnns = currentAnns.filter { it.id !in seenAnns }
                    if (settings.notificationsAnnouncements.first() && freshAnns.isNotEmpty()) {
                        freshAnns.take(5).forEach { sink.postAnnouncement(it) }
                    }
                }
                notificationsRepo.setSeenSubstitutionIds(currentSubs.map { it.id }.toSet())
                notificationsRepo.setSeenSubstitutionFingerprints(
                    currentSubs.map(pl.zse.bydgoszcz.elektron.domain.model.SubstitutionNotice::fingerprint).toSet())
                notificationsRepo.setSeenAnnouncementIds(currentAnns.map { it.id }.toSet())
                // Punkt odniesienia gotowy dopiero, gdy zastępstwa naprawdę się pobrały - inaczej
                // następna udana synchronizacja powiadomiłaby o wszystkich zastępstwach naraz.
                if (substitutionsLoaded) {
                    if (!notificationsRepo.isInitialSyncDone()) notificationsRepo.setInitialSyncDone()
                    if (!notificationsRepo.isSeenStoreReady()) notificationsRepo.markSeenStoreReady()
                }
            }
        }
    }

    companion object { private const val TAG = "SyncCoordinator" }
}
