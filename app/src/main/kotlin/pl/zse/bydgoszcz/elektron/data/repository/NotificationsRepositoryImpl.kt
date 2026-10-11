package pl.zse.bydgoszcz.elektron.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.zse.bydgoszcz.elektron.data.local.NotificationDao
import pl.zse.bydgoszcz.elektron.data.local.NotificationItemEntity
import pl.zse.bydgoszcz.elektron.data.local.SyncStateDao
import pl.zse.bydgoszcz.elektron.data.local.SyncStateEntity
import pl.zse.bydgoszcz.elektron.domain.model.NotificationItem
import pl.zse.bydgoszcz.elektron.domain.model.NotificationType
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationsRepositoryImpl @Inject constructor(
    private val dao: NotificationDao,
    private val syncStateDao: SyncStateDao
) : NotificationsRepository {

    override fun observeAll(): Flow<List<NotificationItem>> =
        dao.observeAll().map { list -> list.map(::toDomain) }

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    override suspend fun add(item: NotificationItem) = withContext(Dispatchers.IO) {
        dao.upsert(toEntity(item))
    }

    override suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        dao.markRead(id, Instant.now().epochSecond)
    }

    override suspend fun markAllRead() = withContext(Dispatchers.IO) {
        dao.markAllRead(Instant.now().epochSecond)
    }

    override suspend fun pruneHistory(days: Long) = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(Instant.now().minusSeconds(days * 24 * 60 * 60).epochSecond)
    }

    // Bug: dawniej separatorem był przecinek, a ID ogłoszeń to URL-e ze strony szkoły,
    // które same zawierają przecinki (np. "...-w1,10,117294.html"). Po odczycie ID rozpadało
    // się na kawałki, żadne ogłoszenie nie było "widziane" i każdy sync powiadamiał od nowa.
    // Nowa linia nie występuje ani w URL-ach, ani w ID zastępstw. Klucze _v2 = nowy format.
    override suspend fun getSeenSubstitutionIds(): Set<String> = withContext(Dispatchers.IO) {
        decodeIds(syncStateDao.get(KEY_SEEN_SUBS)?.message)
    }
    override suspend fun setSeenSubstitutionIds(ids: Set<String>) = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_SEEN_SUBS, Instant.now().epochSecond, "ok", encodeIds(ids)))
    }
    override suspend fun getSeenAnnouncementIds(): Set<String> = withContext(Dispatchers.IO) {
        decodeIds(syncStateDao.get(KEY_SEEN_ANNS)?.message)
    }
    override suspend fun setSeenAnnouncementIds(ids: Set<String>) = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_SEEN_ANNS, Instant.now().epochSecond, "ok", encodeIds(ids)))
    }
    override suspend fun getSeenSubstitutionFingerprints(): Set<String> = withContext(Dispatchers.IO) {
        decodeIds(syncStateDao.get(KEY_SEEN_SUB_FINGERPRINTS)?.message)
    }
    override suspend fun setSeenSubstitutionFingerprints(fingerprints: Set<String>) = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_SEEN_SUB_FINGERPRINTS, Instant.now().epochSecond, "ok", encodeIds(fingerprints)))
    }
    override suspend fun isSeenStoreReady(): Boolean = withContext(Dispatchers.IO) {
        syncStateDao.get(KEY_SEEN_STORE_READY) != null
    }
    override suspend fun markSeenStoreReady() = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_SEEN_STORE_READY, Instant.now().epochSecond, "ok", null))
    }

    private fun encodeIds(ids: Set<String>): String = ids.joinToString(ID_SEPARATOR)
    private fun decodeIds(raw: String?): Set<String> =
        raw?.split(ID_SEPARATOR)?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    override suspend fun isInitialSyncDone(): Boolean = withContext(Dispatchers.IO) {
        syncStateDao.get(KEY_INIT_DONE) != null
    }
    override suspend fun setInitialSyncDone() = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_INIT_DONE, Instant.now().epochSecond, "ok", null))
    }


    override suspend fun getLastSyncAt(): Instant? = withContext(Dispatchers.IO) {
        syncStateDao.get(KEY_LAST_SYNC)?.lastSyncEpochSeconds?.let(Instant::ofEpochSecond)
    }
    override suspend fun setLastSyncAt(t: Instant) = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_LAST_SYNC, t.epochSecond, "ok", null))
    }
    // distinctUntilChanged: Room emituje przy KAŻDYM zapisie do sync_state (kilkanaście na sync),
    // a strona główna przeliczała wtedy cały stan mimo tej samej wartości.
    override fun observeLastSyncAt(): Flow<Instant?> =
        syncStateDao.observe(KEY_LAST_SYNC).map { it?.lastSyncEpochSeconds?.let(Instant::ofEpochSecond) }.distinctUntilChanged()


    override fun observeLastSyncError(): Flow<String?> =
        syncStateDao.observe(KEY_LAST_ERROR).map { e ->
            // Po aktualizacji z rc7 zapisany tam komunikat do pierwszej synchronizacji - w nowej, łagodnej postaci.
            pl.zse.bydgoszcz.elektron.domain.model.IncompleteSchoolDataException.normalize(e?.message?.takeIf { m -> m.isNotBlank() })
        }.distinctUntilChanged()
    override suspend fun setLastSyncError(msg: String?) = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_LAST_ERROR, Instant.now().epochSecond, "ok", msg ?: ""))
    }

    override fun observeLoadedResources(): Flow<Set<String>> =
        syncStateDao.observe(KEY_LOADED).map { e ->
            e?.message?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }.distinctUntilChanged()
    // Bug audytu #5: markLoaded jest wołane równolegle z 3 niezależnych korutyn
    // (Setup/Ustawienia/Start syncują timetable+subs+anns naraz). Read-modify-write na
    // CSV bez blokady gubił flagi, bo ostatni zapis nadpisywał poprzedni. Mutex serializuje
    // te trzy zapisy, więc żaden zasób już nie znika z "loaded_resources".
    private val loadedMutex = Mutex()
    override suspend fun markLoaded(resource: String) = withContext(Dispatchers.IO) {
        loadedMutex.withLock {
            val current = syncStateDao.get(KEY_LOADED)?.message?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val new = current + resource
            syncStateDao.upsert(SyncStateEntity(KEY_LOADED, Instant.now().epochSecond, "ok", new.joinToString(",")))
        }
    }
    override suspend fun clearLoaded() = withContext(Dispatchers.IO) {
        loadedMutex.withLock {
            syncStateDao.upsert(SyncStateEntity(KEY_LOADED, Instant.now().epochSecond, "ok", ""))
        }
    }

    // Nowy bug (po dodaniu pushu FCM): SyncWorker (co 15 min) i odbiornik FCM mogą
    // wykryć to samo zastępstwo/ogłoszenie niezależnie, prawie jednocześnie. Bez blokady
    // oba czytają ten sam, jeszcze nieaktualny zbiór "seen" i oba pokazują powiadomienie —
    // realny duplikat, nie tylko teoretyczny wyścig. Jedna globalna blokada na całą
    // sekcję "sprawdź → powiadom → zapisz seen" w obu miejscach to rozwiązuje.
    private val notifyMutex = Mutex()
    override suspend fun <T> withNotifyLock(block: suspend () -> T): T = notifyMutex.withLock { block() }

    private fun toDomain(e: NotificationItemEntity) = NotificationItem(
        id = e.id,
        type = runCatching { NotificationType.valueOf(e.type) }.getOrDefault(NotificationType.OTHER),
        title = e.title,
        body = e.body,
        deepLink = e.deepLink,
        createdAt = Instant.ofEpochSecond(e.createdAtEpochSeconds),
        readAt = e.readAtEpochSeconds?.let(Instant::ofEpochSecond)
    )
    private fun toEntity(d: NotificationItem) = NotificationItemEntity(
        id = d.id, type = d.type.name, title = d.title, body = d.body, deepLink = d.deepLink,
        createdAtEpochSeconds = d.createdAt.epochSecond,
        readAtEpochSeconds = d.readAt?.epochSecond
    )

    companion object {
        private const val KEY_SEEN_SUBS = "seen_substitution_ids_v2"
        private const val KEY_SEEN_ANNS = "seen_announcement_ids_v2"
        private const val KEY_SEEN_SUB_FINGERPRINTS = "seen_substitution_fingerprints"
        private const val KEY_SEEN_STORE_READY = "seen_store_v2_ready"
        private const val ID_SEPARATOR = "\n"
        private const val KEY_INIT_DONE = "initial_sync_done"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LOADED = "loaded_resources"
    }
}
