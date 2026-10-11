package pl.zse.bydgoszcz.elektron.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.zse.bydgoszcz.elektron.domain.model.NotificationItem
import java.time.Instant

interface NotificationsRepository {
    fun observeAll(): Flow<List<NotificationItem>>
    fun observeUnreadCount(): Flow<Int>
    suspend fun add(item: NotificationItem)
    suspend fun markRead(id: String)
    suspend fun markAllRead()
    /** Usuwa wpisy historii starsze niż [days] dni (historia rosła dotąd bez limitu). */
    suspend fun pruneHistory(days: Long = 30)

    suspend fun getSeenSubstitutionIds(): Set<String>
    suspend fun setSeenSubstitutionIds(ids: Set<String>)
    suspend fun getSeenAnnouncementIds(): Set<String>
    suspend fun setSeenAnnouncementIds(ids: Set<String>)

    /**
     * Odciski treści widzianych zastępstw ([pl.zse.bydgoszcz.elektron.domain.model.SubstitutionNotice.fingerprint]).
     * ID zastępstwa nie obejmuje uwag (zgodność z watcherem push), więc zmiana samych uwag
     * jest wykrywana po odcisku.
     */
    suspend fun getSeenSubstitutionFingerprints(): Set<String> = emptySet()
    suspend fun setSeenSubstitutionFingerprints(fingerprints: Set<String>) {}

    /**
     * true po pierwszym przebiegu SyncWorkera w formacie v2 zbioru "widzianych" ID.
     * Dopóki false, worker tylko zapisuje stan i nie powiadamia — migracja ze starego
     * (zepsutego) formatu nie może skończyć się lawiną powiadomień.
     */
    suspend fun isSeenStoreReady(): Boolean
    suspend fun markSeenStoreReady()

    suspend fun isInitialSyncDone(): Boolean
    suspend fun setInitialSyncDone()

    suspend fun getLastSyncAt(): Instant?
    suspend fun setLastSyncAt(t: Instant)
    fun observeLastSyncAt(): Flow<Instant?>

    /** Ostatni błąd synchronizacji (null gdy OK). */
    fun observeLastSyncError(): Flow<String?>
    suspend fun setLastSyncError(msg: String?)

    /** Zbiór zasobów, które zostały poprawnie zsynchronizowane po ostatnim clearLoaded(). */
    fun observeLoadedResources(): Flow<Set<String>>
    suspend fun markLoaded(resource: String)
    suspend fun clearLoaded()

    /**
     * Serializuje sekcję "sprawdź czy już widziane → ewentualnie powiadom → zapisz jako
     * widziane". SyncWorker (co 15 min) i odbiornik FCM push mogą teraz wykryć to samo
     * zastępstwo niezależnie i prawie jednocześnie — bez tej blokady oba mogłyby odczytać
     * ten sam, jeszcze nieaktualny zbiór "seen" i pokazać DWA powiadomienia o tym samym
     * zastępstwie.
     */
    suspend fun <T> withNotifyLock(block: suspend () -> T): T
}
