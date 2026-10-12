package pl.zse.bydgoszcz.elektron.presentation.announcements

import pl.zse.bydgoszcz.elektron.domain.sync.SyncRequest
import pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome
import pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import pl.zse.bydgoszcz.elektron.domain.model.SyncErrors
import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    private val repo: AnnouncementsRepository,
    private val coordinator: SyncCoordinator
) : ViewModel() {

    private companion object { const val PAGE_SIZE = 10 }

    data class State(
        val items: List<Announcement> = emptyList(),
        val hasMore: Boolean = false,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val loadMoreError: Boolean = false,
        /** Trwa pierwsza synchronizacja — pusta lista to "ładowanie", nie "brak ogłoszeń". */
        val isInitialLoading: Boolean = false,
        val query: String = "",
        val favoritesOnly: Boolean = false,
        val favoriteCount: Int = 0,
        /** Pierwsze dane już są (do tego czasu ekran jest niewidoczny, bez mignięć). */
        val ready: Boolean = false
    ) {
        val isSearching: Boolean get() = query.isNotBlank()
    }

    /** Ogłoszenie z tekstem znormalizowanym raz (a nie przy każdym wciśnięciu klawisza). */
    private data class Indexed(val ann: Announcement, val text: String)
    private data class Selection(val items: List<Indexed>, val query: String, val favorites: Boolean, val favoriteCount: Int)

    private data class Flags(val loadingMore: Boolean, val error: Boolean, val exhausted: Boolean, val initialPending: Boolean)

    private val visibleCount = MutableStateFlow(PAGE_SIZE)
    private val query = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val selectedId = MutableStateFlow<String?>(null)
    val article = selectedId.flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeById(id).onStart { emit(null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val articleLoading = MutableStateFlow(false)
    val articleError = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    private var articleJob: kotlinx.coroutines.Job? = null
    private var articleRequest = 0L

    fun consumeMessage(expected: String) { if (message.value == expected) message.value = null }
    fun setFavoritesOnly(value: Boolean) {
        if (favoritesOnly.value == value) return
        // Najpierw liczba pozycji, potem filtr: stan liczony w tle mógł połączyć nowy filtr ze starą
        // liczbą (np. 20 po "Pokaż więcej" w ulubionych) i na chwilę pokazać za długą listę.
        visibleCount.value = PAGE_SIZE
        favoritesOnly.value = value
    }
    val clearingFavorites = MutableStateFlow(false)
    fun clearFavorites() {
        if (clearingFavorites.value) return
        clearingFavorites.value = true
        viewModelScope.launch {
            try {
                runCatchingCancellable { repo.clearFavorites() }
                    .onSuccess { count -> message.value = if (count == 0) "Brak ogłoszeń do usunięcia z ulubionych."
                        else "Usunięto wszystkie zakładki ($count). Ogłoszenia i pobrana treść pozostały." }
                    .onFailure { message.value = "Nie udało się usunąć ulubionych. ${SyncErrors.userMessage(it)}" }
            } finally { clearingFavorites.value = false }
        }
    }
    fun closeArticle() { articleRequest++; articleJob?.cancel(); selectedId.value = null; articleLoading.value = false; articleError.value = null }
    fun openArticle(id: String, force: Boolean = false) {
        val request = ++articleRequest
        articleJob?.cancel()
        selectedId.value = id
        articleError.value = null
        articleLoading.value = true
        articleJob = viewModelScope.launch {
            try {
                runCatchingCancellable { if (force) repo.refreshFullArticle(id) else repo.loadFullArticle(id) }.getOrElse { Result.failure(it) }.onFailure { if (request == articleRequest) articleError.value = SyncErrors.userMessage(it) }
            } finally { if (request == articleRequest) articleLoading.value = false }
        }
    }
    fun toggleFavorite(id: String) {
        if (clearingFavorites.value) return
        viewModelScope.launch {
            runCatchingCancellable {
                repo.toggleFavorite(id)
                repo.observeById(id).first()?.isFavorite == true
            }.onSuccess { favorite ->
                    if (favorite) {
                        repo.loadFullArticle(id).onFailure {
                            if (repo.observeById(id).first()?.isFavorite == true) message.value = "Dodano do ulubionych, ale nie pobrano treści do czytania offline. Otwórz ogłoszenie, aby spróbować ponownie."
                        }
                    }
                }
                .onFailure { message.value = "Nie udało się zmienić ulubionych. ${SyncErrors.userMessage(it)}" }
        }
    }

    private val indexed = repo.observeAll().map { list ->
        list.map { Indexed(it, SearchText.normalize(it.title + " " + (it.excerpt ?: ""))) }
    }
    private val refreshing = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)
    private val loadMoreError = MutableStateFlow(false)
    /** Archiwum strony szkoły się skończyło — nie ma już czego dociągać. */
    private val archiveExhausted = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            repo.archiveGeneration.collect {
                archiveExhausted.value = false
                loadMoreError.value = false
                visibleCount.value = PAGE_SIZE
            }
        }
    }

    val state: StateFlow<State> = combine(
        combine(indexed, query, favoritesOnly) { all, q, favorites ->
            val saved = all.filter { it.ann.isFavorite }
            Selection(if (favorites) saved else all, q, favorites, saved.size)
        },
        visibleCount,
        refreshing,
        combine(loadingMore, loadMoreError, archiveExhausted, coordinator.initialSyncPending) { l, e, x, p ->
            Flags(l, e, x, p)
        }
    ) { (all, q, favorites, favoriteCount), count, refresh, flags ->
        val loading = !favorites && flags.loadingMore
        val error = !favorites && flags.error
        val exhausted = flags.exhausted
        val words = SearchText.normalize(q).split(' ').filter { it.isNotBlank() }
        val searching = words.isNotEmpty()
        val items = if (searching) {
            // Wszystkie słowa muszą wystąpić (bez względu na wielkość liter i polskie znaki).
            all.filter { ix -> words.all { w -> ix.text.contains(w) } }.map { it.ann }
        } else all.take(count).map { it.ann }
        State(
            items = items,
            // "Pokaż więcej" jest dostępne, dopóki są wpisy w bazie ALBO archiwum strony
            // (dawniej przycisk znikał po wyczerpaniu kilkudziesięciu wpisów z RSS).
            // Przy wyszukiwaniu: dopóki archiwum się nie skończyło (szukaj w starszych).
            hasMore = if (favorites) !searching && all.size > count else if (searching) !exhausted else all.size > count || !exhausted,
            favoritesOnly = favorites,
            favoriteCount = favoriteCount,
            query = q,
            isRefreshing = refresh,
            isLoadingMore = loading,
            loadMoreError = error,
            isInitialLoading = !favorites && flags.initialPending,
            ready = true
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /** Bieżący tekst wyszukiwania (bez czekania na przefiltrowaną listę). */
    val queryText: String get() = query.value
    fun setQuery(q: String) { query.value = q }

    fun loadMore() {
        // Ulubione są lokalne: nie czekają na pobieranie archiwum rozpoczęte w innej zakładce.
        if (favoritesOnly.value) {
            visibleCount.value += PAGE_SIZE
            return
        }
        if (loadingMore.value) return
        loadingMore.value = true
        val shown = state.value.items.size
        val requestedQuery = query.value
        val searching = requestedQuery.isNotBlank()
        viewModelScope.launch {
            try {
                val requestedGeneration = repo.archiveGeneration.first()
                val inDb = repo.count()
                // Przy wyszukiwaniu przeszukana jest już cała baza — od razu sięgamy do archiwum.
                if (!searching && inDb > shown) {
                    if (repo.archiveGeneration.first() == requestedGeneration && !favoritesOnly.value && query.value == requestedQuery) visibleCount.value = shown + PAGE_SIZE
                    return@launch
                }
                // Baza wyczerpana — starsze wpisy z archiwum strony szkoły.
                loadMoreError.value = false
                repo.loadOlder()
                    .onSuccess { r ->
                        if (repo.archiveGeneration.first() != requestedGeneration) return@onSuccess
                        if (r.exhausted && r.added == 0) archiveExhausted.value = true
                        // Przy wyszukiwaniu [shown] to liczba wyników - bez maxOf lista główna po wyczyszczeniu
                        // wyszukiwania skracała się (np. z 40 do 17 wpisów).
                        if (!favoritesOnly.value && query.value == requestedQuery)
                            visibleCount.value = maxOf(visibleCount.value, shown + maxOf(r.added, PAGE_SIZE))
                    }
                    .onFailure { if (repo.archiveGeneration.first() == requestedGeneration) loadMoreError.value = true }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { loadMoreError.value = true }
            finally {
                loadingMore.value = false
            }
        }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                // Przez koordynator (markLoaded, powiadomienia, widżety - tam). Komunikatu o planie
                // i zastępstwach odświeżenie samych ogłoszeń nie rusza.
                repo.resetArchive()
                val outcome = coordinator.sync(SyncRequest.of(SyncOutcome.ANNOUNCEMENTS))
                outcome.failures[SyncOutcome.ANNOUNCEMENTS]?.let {
                    message.value = "Nie udało się odświeżyć ogłoszeń. ${SyncErrors.userMessage(it)}"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message.value = "Nie udało się odświeżyć ogłoszeń. ${SyncErrors.userMessage(e)}" }
            finally {
                // Dawniej bez try/finally — wyjątek zostawiał wieczny spinner odświeżania.
                refreshing.value = false
            }
        }
    }

}
