package pl.zse.bydgoszcz.elektron.data.repository

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import pl.zse.bydgoszcz.elektron.data.local.AnnouncementDao
import pl.zse.bydgoszcz.elektron.data.local.AnnouncementEntity
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.mapper.AnnouncementMapper
import pl.zse.bydgoszcz.elektron.data.mapper.ArticleContent
import pl.zse.bydgoszcz.elektron.data.remote.sources.AnnouncementsSource
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.AnnouncementSource
import pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnouncementsRepositoryImpl @Inject constructor(
    private val source: AnnouncementsSource,
    private val dao: AnnouncementDao,
    private val db: AppDatabase,
    private val favoritesStore: FavoriteAnnouncementsStore,
    /** Zbiór "widzianych" ogłoszeń - wpisy z archiwum trafiają do niego od razu (null w części testów). */
    private val notifications: NotificationsRepository? = null
) : AnnouncementsRepository {
    internal constructor(source: AnnouncementsSource, dao: AnnouncementDao, db: AppDatabase) :
        this(source, dao, db, FavoriteAnnouncementsStore(null))
    private val favoritesMutex = kotlinx.coroutines.sync.Mutex()
    private var favoritesReady = false
    private suspend fun restoreFavorites() = favoritesMutex.withLock {
        if (!favoritesReady) {
            val saved = favoritesStore.read()
            val existing = dao.getFavorites()
            val merged = (saved.orEmpty() + existing).associateBy { it.id }.values.toList()
            // Write migration before changing the original cache; a failed write loses nothing.
            favoritesStore.write(merged)
            db.withTransaction {
                val known = dao.getByIds(merged.map { it.id }).associateBy { it.id }
                dao.upsertAll(merged.map { favorite -> known[favorite.id]?.copy(isFavorite = true,
                    fullHtml = known[favorite.id]?.fullHtml ?: favorite.fullHtml) ?: favorite })
            }
            favoritesReady = true
        }
    }
    private suspend fun restoreFavoritesForRead() {
        // A full disk must not crash readers or hide articles still present in Room.
        runCatchingCancellable { restoreFavorites() }
            .onFailure { Log.w(TAG, "Nie udało się odtworzyć trwałego zapisu ulubionych; zachowuję bazę", it) }
    }
    private suspend fun persistFavorites() = favoritesMutex.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { favoritesStore.write(dao.getFavorites()) }
    }


    override suspend fun syncAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            restoreFavorites()
            val (newsResult, latestResult) = coroutineScope {
                val news = async { runCatchingCancellable { source.fetchNewsFeed() } }
                val latest = async { runCatchingCancellable { source.fetchLatestFeed() } }
                news.await() to latest.await()
            }
            // Oba kanały z błędem (np. brak internetu) - to porażka synchronizacji, nie "pusto".
            // Dawniej sync kończył się sukcesem bez danych i aplikacja uznawała ogłoszenia za
            // aktualne. Zapisane ogłoszenia zostają nietknięte.
            if (newsResult.isFailure && latestResult.isFailure) {
                throw newsResult.exceptionOrNull() ?: java.io.IOException("Ogłoszenia: brak połączenia")
            }
            val news = newsResult.getOrElse { Log.w(TAG, "RSS_NEWS padł", it); emptyList() }
            val latest = latestResult.getOrElse { Log.w(TAG, "RSS_LATEST padł", it); emptyList() }
            val all = news + latest
            if (all.isEmpty()) {
                Log.w(TAG, "Oba kanały puste — nie nadpisuję")
                return@runCatchingCancellable
            }
            val freshEntities = all.mapNotNull(AnnouncementMapper::toEntity).associateBy { it.id }.values.toList()
            // Kanał z wpisami, z których żadnego nie da się odczytać (np. zmieniony format daty),
            // to błąd - dawniej "sukces" bez nowych ogłoszeń.
            if (freshEntities.isEmpty()) throw IllegalStateException("Ogłoszenia: żaden wpis RSS nie dał się odczytać")
            // REPLACE nadpisywał pobraną treść artykułu (fullHtml) przy każdym syncu —
            // scalamy z tym, co już jest w Room, zanim wstawimy.
            db.withTransaction {
                val existingById = dao.getByIds(freshEntities.map { it.id }).associateBy { it.id }
                val entities = freshEntities.map { fresh ->
                    val existing = existingById[fresh.id]
                    if (existing == null) fresh
                    else fresh.copy(fullHtml = existing.fullHtml ?: fresh.fullHtml, isFavorite = existing.isFavorite)
                }
                dao.upsertAll(entities.filter { existingById[it.id] != it })
                dao.deleteDevEntries()
            }
            // 2 lata (dawniej 180 dni) — inaczej sync kasowałby starsze ogłoszenia
            // wczytane z archiwum przez "Pokaż więcej".
            dao.deleteOlderThan(Instant.now().minusSeconds(60L * 60 * 24 * 730).epochSecond)
            persistFavorites()
        }
    }

    override suspend fun deleteSimulated(): Int = withContext(Dispatchers.IO) { dao.deleteDevEntries() }

    override fun observeAll(): Flow<List<Announcement>> =
        dao.observeAll().onStart { restoreFavoritesForRead() }.map { list -> list.map(AnnouncementMapper::toDomain) }.flowOn(Dispatchers.Default)

    override fun observeLatest(limit: Int): Flow<List<Announcement>> =
        dao.observeLatest(limit).onStart { restoreFavoritesForRead() }.map { list -> list.map(AnnouncementMapper::toDomain) }.flowOn(Dispatchers.Default)

    override suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }


    override fun observeById(id: String): Flow<Announcement?> =
        dao.observeById(id).onStart { restoreFavoritesForRead() }.map { it?.let(AnnouncementMapper::toDomain) }.flowOn(Dispatchers.Default)

    override suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        restoreFavorites()
        favoritesMutex.withLock {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                db.withTransaction {
                    dao.toggleFavorite(id)
                    // A failed durable write must roll back the visible bookmark change.
                    favoritesStore.write(dao.getFavorites())
                }
            }
        }
    }

    override suspend fun clearFavorites(): Int = withContext(Dispatchers.IO) {
        restoreFavorites()
        favoritesMutex.withLock {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                db.withTransaction {
                    val count = dao.clearFavorites()
                    favoritesStore.write(emptyList())
                    count
                }
            }
        }
    }

    private val articleMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun loadFullArticle(id: String): Result<Unit> = loadArticle(id, force = false)
    override suspend fun refreshFullArticle(id: String): Result<Unit> = loadArticle(id, force = true)
    private suspend fun loadArticle(id: String, force: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            restoreFavorites()
            articleMutex.withLock {
                val current = dao.getById(id) ?: throw java.io.IOException("Brak zapisanego ogłoszenia")
                val saved = !current.fullHtml.isNullOrBlank()
                // Treść zapisana starszą wersją aplikacji (np. ucięta) - pobieramy od nowa, ale bez
                // internetu zostaje zapisana wersja i nie ma komunikatu o błędzie.
                val outdated = saved && !ArticleContent.isCurrent(current.fullHtml)
                if (!force && saved && !outdated) return@withLock
                val html = try {
                    source.fetchArticleHtml(current.url)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (outdated && !force) return@withLock else throw e
                }
                if (html.isNullOrBlank()) {
                    if (outdated && !force) return@withLock
                    throw pl.zse.bydgoszcz.elektron.domain.model.ArticleContentException()
                }
                dao.updateArticle(id, ArticleContent.mark(html))
                if (dao.getById(id)?.isFavorite == true) persistFavorites()
            }
        }
    }

    override suspend fun getAll(): List<Announcement> = withContext(Dispatchers.IO) {
        dao.observeAll().first().map(AnnouncementMapper::toDomain)
    }

    // Push FCM może przyjść o ogłoszeniu, które już jest w bazie — scalamy z istniejącym
    // wpisem (np. pobraną treścią artykułu), tak jak w syncAll().
    override suspend fun upsertOne(ann: Announcement) = withContext(Dispatchers.IO) {
        restoreFavorites()
        db.withTransaction {
            val existing = dao.getById(ann.id)
            dao.upsertAll(listOf(AnnouncementEntity(
                id = ann.id,
                title = ann.title,
                url = ann.url,
                publishedAtEpochSeconds = ann.publishedAt.epochSecond,
                excerpt = ann.excerpt,
                coverImageUrl = ann.coverImageUrl,
                fullHtml = existing?.fullHtml ?: ann.fullHtml,
                isRead = false, // kolumna nieużywana (stan "przeczytane" usunięty w 0.3.1)
                source = ann.source.name,
                isFavorite = existing?.isFavorite ?: ann.isFavorite
            )))
        }
        persistFavorites()
    }

    // Następna strona archiwum do pobrania (w pamięci procesu; po restarcie zaczynamy od pierwszej).
    private var nextArchivePage: Int? = null
    private val archiveMutex = kotlinx.coroutines.sync.Mutex()
    override val archiveGeneration = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override suspend fun resetArchive(clear: suspend () -> Unit) = archiveMutex.withLock {
        clear()
        nextArchivePage = null
        archiveGeneration.value++
        Unit
    }

    override suspend fun loadOlder(): Result<AnnouncementsRepository.OlderResult> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            archiveMutex.withLock {
                // Liczba wpisów w bazie nie oznacza liczby kompletnych stron (np. po resecie
                // zostają rozproszone ulubione). Przechodzimy od początku, pomijając znane URL-e.
                var page = nextArchivePage ?: 1
                var added = 0
                var pagesChecked = 0
                while (added == 0 && pagesChecked < MAX_PAGES_PER_CALL) {
                    val items = source.fetchArchivePage(page)
                    pagesChecked++
                    if (items.isEmpty()) {
                        nextArchivePage = page
                        return@withLock AnnouncementsRepository.OlderResult(0, exhausted = true)
                    }
                    val known = dao.existingUrls(items.map { it.url }).toSet()
                    val fresh = items.filter { it.url !in known }
                    if (fresh.isNotEmpty()) {
                        // Daty tylko dla nowych wpisów, równolegle (najwyżej 5 zapytań).
                        val dates = coroutineScope {
                            fresh.map { item -> async { runCatchingCancellable { source.fetchArticleDate(item.url) }.getOrNull() } }
                                .awaitAll()
                        }
                        val entities = fresh.mapIndexed { i, item ->
                            AnnouncementEntity(
                                id = item.url,
                                title = item.title,
                                url = item.url,
                                // Bez daty: tuż przed najstarszym znanym, żeby zachować kolejność listy.
                                publishedAtEpochSeconds = (dates[i] ?: fallbackDate()).epochSecond,
                                excerpt = item.excerpt,
                                coverImageUrl = item.imageUrl,
                                fullHtml = null,
                                isRead = false,
                                source = AnnouncementSource.RSS_NEWS.name
                            )
                        }
                        val insert: suspend () -> List<AnnouncementEntity> = {
                            db.withTransaction {
                                // RSS/push may have saved the same article while dates were downloading.
                                // Preserve its bookmark and offline content rather than replacing it.
                                val existing = dao.existingUrls(entities.map { it.url }).toSet()
                                val missing = entities.filter { it.url !in existing }.distinctBy { it.id }
                                dao.upsertAll(missing)
                                missing
                            }
                        }
                        // Stare wpisy z archiwum od razu jako "widziane", pod tą samą blokadą co
                        // powiadomienia: dawniej następna synchronizacja (nawet samych zastępstw)
                        // uznawała je za nowe i powiadamiała o artykułach sprzed miesięcy.
                        val seen = notifications
                        val inserted = if (seen == null) insert() else seen.withNotifyLock {
                            val rows = insert()
                            if (rows.isNotEmpty()) seen.setSeenAnnouncementIds(seen.getSeenAnnouncementIds() + rows.map { it.id })
                            rows
                        }
                        added = inserted.size
                    }
                    page++
                }
                nextArchivePage = page
                AnnouncementsRepository.OlderResult(added, exhausted = false)
            }
        }
    }

    private suspend fun fallbackDate(): Instant =
        dao.oldestEpochSeconds()?.let { Instant.ofEpochSecond(it - 60) } ?: Instant.now()

    companion object {
        private const val TAG = "AnnouncementsRepositoryImpl"
        private const val ARCHIVE_PAGE_SIZE = 5
        private const val MAX_PAGES_PER_CALL = 6
    }
}
