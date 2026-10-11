package pl.zse.bydgoszcz.elektron.domain.sync

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.local.AnnouncementEntity
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.remote.dto.ArchiveItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.ClassListItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonCellDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonGroupDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.RssItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.TimetableDto
import pl.zse.bydgoszcz.elektron.data.remote.sources.AnnouncementsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.TimetableSource
import pl.zse.bydgoszcz.elektron.data.repository.inMemoryDb
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.Substitution
import pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome
import pl.zse.bydgoszcz.elektron.testutil.FakeSettings
import pl.zse.bydgoszcz.elektron.testutil.TestCoordinator
import pl.zse.bydgoszcz.elektron.testutil.clearAndAwait
import pl.zse.bydgoszcz.elektron.work.NotificationSink
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCoordinatorTest {

    /** Plan: [blockedClass] czeka na [gate] (raz, jeśli [blockOnce]); zapisuje kolejność zdarzeń. */
    private inner class Timetable : TimetableSource {
        val calls = AtomicInteger()
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val started = CompletableDeferred<Unit>()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var blockedClass: String? = null
        @Volatile var blockOnce = false
        @Volatile var error: Exception? = null

        override suspend fun fetchSidebar() = listOf(
            ClassListItemDto("o3", ClassListItemDto.Kind.CLASS, "1D 1D PBŚ", "1D", "https://plan.zse.bydgoszcz.pl/plany/o3.html"),
            ClassListItemDto("o4", ClassListItemDto.Kind.CLASS, "1E 1E PBŚ", "1E", "https://plan.zse.bydgoszcz.pl/plany/o4.html")
        )

        override suspend fun fetchTimetable(classId: String): TimetableDto {
            calls.incrementAndGet()
            events += "start:$classId"
            val g = gate
            if (g != null && (blockedClass == null || blockedClass == classId)) {
                if (blockOnce) gate = null
                started.complete(Unit)
                try {
                    g.await()
                } catch (e: CancellationException) {
                    // Czy baza była jeszcze nietknięta, gdy przerwano tę pracę (reset czeka na koniec)?
                    events += "cancelled:$classId:znacznik=${withContext(NonCancellable) { db.announcementDao().count() }}"
                    throw e
                }
            }
            error?.let { throw it }
            val name = if (classId == "o4") "1E 1E PBŚ" else "1D 1D PBŚ"
            return TimetableDto(classId, name, null, null, (1..5).map { day ->
                LessonCellDto(2, "08:55", "09:40", day, listOf(LessonGroupDto("mat", "Ch", null, "105", null, null, null)), null)
            })
        }
    }

    private class Subs : SubstitutionsSource {
        @Volatile var items: List<SubstitutionDto> = emptyList()
        @Volatile var error: Exception? = null
        /** Dni, z których "parser" nie odczytał wszystkich wierszy. */
        @Volatile var incomplete: Set<String> = emptySet()
        override suspend fun fetchSubstitutions(): List<SubstitutionDto> { error?.let { throw it }; return items }
        override suspend fun fetchPage(): pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsPage {
            val list = fetchSubstitutions()
            return pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsPage(list.map { it.dateRaw }.toSet() + incomplete, list, incomplete)
        }
    }

    private class Anns : AnnouncementsSource {
        @Volatile var error: Exception? = null
        override suspend fun fetchNewsFeed(): List<RssItemDto> { error?.let { throw it }; return emptyList() }
        override suspend fun fetchLatestFeed(): List<RssItemDto> { error?.let { throw it }; return emptyList() }
        override suspend fun fetchArticleHtml(url: String): String? = null
        override suspend fun fetchArchivePage(page: Int) = emptyList<ArchiveItemDto>()
        override suspend fun fetchArticleDate(url: String): Instant? = null
    }

    private class RecordingSink : NotificationSink {
        val substitutions: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun postSubstitution(sub: Substitution, originalSubject: String?) { substitutions += sub.id }
        override suspend fun postAnnouncement(ann: Announcement) {}
        override suspend fun postGeneric(title: String, body: String, deepLink: String?) {}
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val settings = FakeSettings(classId = "o3")
    private val timetable = Timetable()
    private val subs = Subs()
    private val anns = Anns()
    private val sink = RecordingSink()
    private lateinit var t: TestCoordinator
    private val coordinator get() = t.coordinator
    private val nextMonday = LocalDate.now().with(DayOfWeek.MONDAY).plusWeeks(1)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private fun sub(cls: String, teacher: String) =
        SubstitutionDto(nextMonday.format(fmt), teacher, 2, cls, null, "316", "X.Zastępca", null)

    private fun newCoordinator() = TestCoordinator(context, db, settings, timetable, subs, anns, sink)

    @Before fun setUp() {
        db = inMemoryDb()
        t = newCoordinator()
    }

    @After fun tearDown() = db.close()

    private suspend fun <T> eventually(read: suspend () -> T, ok: (T) -> Boolean): T = withTimeout(10_000) {
        var v = read()
        while (!ok(v)) { delay(20); v = read() }
        v
    }

    // --- Wspólna praca ---

    @Test
    fun twoParallelCallsWithSameRequestDoOneFetch() = runBlocking<Unit> {
        timetable.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { coordinator.sync(SyncRequest.full()) }
        withTimeout(10_000) { timetable.started.await() }
        val b = async(Dispatchers.Default) { coordinator.sync(SyncRequest.full()) }
        delay(200)
        timetable.gate!!.complete(Unit)
        val ra = withTimeout(10_000) { a.await() }
        val rb = withTimeout(10_000) { b.await() }
        assertEquals(1, timetable.calls.get())
        assertSame(ra, rb)
        assertTrue(SyncOutcome.TIMETABLE in ra.succeeded)
    }

    @Test
    fun differentPlanDateIsSeparateWork() = runBlocking<Unit> {
        val thisWeek = SyncRequest.of(SyncOutcome.TIMETABLE)
        val nextWeek = SyncRequest.of(SyncOutcome.TIMETABLE, anchorDate = LocalDate.now().plusWeeks(1))
        timetable.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { coordinator.sync(thisWeek) }
        withTimeout(10_000) { timetable.started.await() }
        val b = async(Dispatchers.Default) { coordinator.sync(nextWeek) }
        delay(200)
        timetable.gate!!.complete(Unit)
        withTimeout(10_000) { a.await(); b.await() }
        assertEquals(2, timetable.calls.get())
    }

    @Test
    fun cancelledCallerDoesNotCancelWorkOthersWaitFor() = runBlocking<Unit> {
        timetable.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { coordinator.sync(SyncRequest.full()) }
        withTimeout(10_000) { timetable.started.await() }
        val b = async(Dispatchers.Default) { coordinator.sync(SyncRequest.full()) }
        delay(200)
        a.cancelAndJoin()                                    // np. zamknięty ekran
        timetable.gate!!.complete(Unit)
        val rb = withTimeout(10_000) { b.await() }
        assertTrue(SyncOutcome.TIMETABLE in rb.succeeded)
        assertEquals(1, timetable.calls.get())
        assertTrue(timetable.events.none { it.startsWith("cancelled") })
    }

    @Test
    fun lastWaiterLeavingCancelsWorkAndClearsSyncingFlag() = runBlocking<Unit> {
        timetable.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { coordinator.sync(SyncRequest.full()) }
        withTimeout(10_000) { timetable.started.await() }
        assertTrue(coordinator.isSyncing.first())
        a.cancelAndJoin()                                    // np. zatrzymany worker
        eventually({ timetable.events.toList() }) { ev -> ev.any { it.startsWith("cancelled:o3") } }
        eventually<Boolean>({ coordinator.isSyncing.first() }) { !it }
        assertTrue(a.isCancelled)
    }

    @Test
    fun classChangeInterruptsWaitersWithoutCancellation() = runBlocking<Unit> {
        timetable.gate = CompletableDeferred()
        timetable.blockedClass = "o3"
        val a = async(Dispatchers.Default) { coordinator.sync(SyncRequest.full()) }
        withTimeout(10_000) { timetable.started.await() }
        coordinator.selectClass("o4")
        // Wołający (np. SyncWorker) dostaje "przerwano", a nie CancellationException.
        val ra = withTimeout(10_000) { a.await() }
        assertTrue(ra.interrupted)
        eventually({ t.notificationsRepo.observeLoadedResources().first() }) { SyncOutcome.TIMETABLE in it }
        assertEquals("o4", settings.classIdFlow.value)
        val ev = timetable.events.toList()
        val cancelled = ev.indexOfFirst { it.startsWith("cancelled:o3") }
        assertTrue(ev.toString(), cancelled >= 0 && cancelled < ev.indexOf("start:o4"))
    }

    @Test
    fun resetWaitsForRunningWorkBeforeClearingDatabase() = runBlocking<Unit> {
        db.announcementDao().upsertAll(listOf(AnnouncementEntity(
            id = "znacznik", title = "x", url = "https://zse/x", publishedAtEpochSeconds = Instant.now().epochSecond,
            excerpt = null, coverImageUrl = null, fullHtml = null, isRead = false, source = "RSS_NEWS"
        )))
        timetable.gate = CompletableDeferred()
        timetable.blockOnce = true
        val a = async(Dispatchers.Default) { coordinator.sync(SyncRequest.full()) }
        withTimeout(10_000) { timetable.started.await() }
        coordinator.resetCacheAndResync("o3")
        assertTrue(withTimeout(10_000) { a.await() }.interrupted)
        eventually({ timetable.events.toList() }) { ev -> ev.count { it == "start:o3" } == 2 }
        // Przerwana praca zakończyła się, gdy baza była jeszcze cała - czyszczenie poszło później.
        assertTrue(timetable.events.toString(), "cancelled:o3:znacznik=1" in timetable.events)
        eventually({ t.notificationsRepo.observeLoadedResources().first() }) { SyncOutcome.TIMETABLE in it }
        assertEquals(0, db.announcementDao().count())
    }

    @Test fun cacheResetKeepsFavoriteAndOfflineArticle() = runBlocking<Unit> {
        val favorite = AnnouncementEntity("favorite", "Ważne", "https://zse/favorite", Instant.now().epochSecond,
            null, null, "<p>Zapisana treść</p>", false, "RSS_NEWS", isFavorite = true)
        db.announcementDao().upsertAll(listOf(favorite, favorite.copy(id = "ordinary", isFavorite = false)))
        coordinator.resetCacheAndResync("o3")
        eventually({ t.notificationsRepo.observeLoadedResources().first() }) { SyncOutcome.TIMETABLE in it }
        assertEquals(favorite, db.announcementDao().getById("favorite"))
        assertNull(db.announcementDao().getById("ordinary"))
    }

    // --- Wspólne kroki po synchronizacji ---

    @Test
    fun failedSourceDoesNotBlockOthers() = runBlocking<Unit> {
        subs.error = IOException("Zastępstwa: HTTP 503 dla x")
        val r = coordinator.sync(SyncRequest.full())
        assertTrue(SyncOutcome.SUBSTITUTIONS in r.failures)
        assertTrue(r.succeeded.containsAll(listOf(SyncOutcome.TIMETABLE, SyncOutcome.SIDEBAR, SyncOutcome.ANNOUNCEMENTS)))
        assertNotNull(t.notificationsRepo.getLastSyncAt())                     // plan się pobrał
        assertNotNull(t.notificationsRepo.observeLastSyncError().first())      // komunikat o zastępstwach
    }

    @Test
    fun onlyTimetableOrSubstitutionsSetLastSync() = runBlocking<Unit> {
        timetable.error = IOException("Plan lekcji: HTTP 503")
        subs.error = IOException("Zastępstwa: HTTP 503")
        val r = coordinator.sync(SyncRequest.full())
        assertTrue(r.succeeded.containsAll(listOf(SyncOutcome.SIDEBAR, SyncOutcome.ANNOUNCEMENTS)))
        assertNull(t.notificationsRepo.getLastSyncAt())
        timetable.error = null
        coordinator.sync(SyncRequest.of(SyncOutcome.TIMETABLE))
        assertNotNull(t.notificationsRepo.getLastSyncAt())
    }

    @Test
    fun announcementsOnlyRefreshKeepsPlanError() = runBlocking<Unit> {
        timetable.error = IOException("Plan lekcji: HTTP 503")
        coordinator.sync(SyncRequest.full())
        val error = t.notificationsRepo.observeLastSyncError().first()
        assertNotNull(error)
        coordinator.sync(SyncRequest.of(SyncOutcome.ANNOUNCEMENTS))
        assertEquals(error, t.notificationsRepo.observeLastSyncError().first())
    }

    // --- Powiadomienia: pierwsza synchronizacja (instalacja, zmiana klasy, reset) bez powiadomień ---

    @Test
    fun incompleteSubstitutionsPageStillNotifiesAndDoesNotRetry() = runBlocking<Unit> {
        // Jeden nieczytelny wiersz na stronie: zapisane wpisy mają działać jak zwykłe pobranie.
        subs.incomplete = setOf(nextMonday.format(fmt))
        subs.items = listOf(sub("1D", "A"))
        val first = coordinator.sync(SyncRequest.full())
        assertTrue(first.failures[SyncOutcome.SUBSTITUTIONS] is pl.zse.bydgoszcz.elektron.domain.model.IncompleteSchoolDataException)
        assertTrue("pierwsza synchronizacja po instalacji", sink.substitutions.isEmpty())
        assertTrue(t.notificationsRepo.isInitialSyncDone())

        subs.items = listOf(sub("1D", "A"), sub("1D", "B"))
        val pushLike = coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
        assertEquals("nowe zastępstwo mimo niepełnej strony", 1, sink.substitutions.size)
        assertFalse("ta sama strona nie jest pobierana ponownie", pushLike.shouldRetry)
    }

    @Test
    fun firstSyncAfterInstallClassChangeAndResetDoesNotNotify() = runBlocking<Unit> {
        subs.items = listOf(sub("1D", "A"))
        coordinator.sync(SyncRequest.full())
        assertTrue("pierwsza synchronizacja po instalacji", sink.substitutions.isEmpty())

        subs.items = listOf(sub("1D", "A"), sub("1D", "B"))
        coordinator.sync(SyncRequest.full())
        assertEquals(1, sink.substitutions.size)                         // zwykła: nowe B

        subs.items = subs.items + sub("1E", "C")                        // nowe dla klasy 1E
        coordinator.selectClass("o4")
        eventually({ t.notificationsRepo.observeLoadedResources().first() }) { SyncOutcome.TIMETABLE in it }
        eventually<Boolean>({ coordinator.isSyncing.first() }) { !it }
        assertEquals("pierwsza po zmianie klasy", 1, sink.substitutions.size)

        subs.items = subs.items + sub("1E", "D")
        coordinator.sync(SyncRequest.full())
        assertEquals(2, sink.substitutions.size)                         // zwykła: nowe D

        subs.items = subs.items + sub("1E", "E")
        coordinator.resetCacheAndResync("o4")
        eventually({ t.notificationsRepo.observeLoadedResources().first() }) { SyncOutcome.TIMETABLE in it }
        eventually<Boolean>({ coordinator.isSyncing.first() }) { !it }
        assertEquals("pierwsza po resecie", 2, sink.substitutions.size)
    }

    // --- Flaga "pierwsza synchronizacja po zmianie klasy" ---

    @Test
    fun initialSyncPendingSurvivesProcessRestartAfterReset() = runBlocking<Unit> {
        coordinator.sync(SyncRequest.full())
        assertFalse(coordinator.initialSyncPending.first())
        val gate = CompletableDeferred<Unit>()
        timetable.gate = gate
        timetable.blockOnce = true
        coordinator.resetCacheAndResync("o3")                            // baza czyszczona, plan czeka
        withTimeout(10_000) { timetable.started.await() }
        // "Restart procesu": nowy koordynator (pusta pamięć) na tej samej bazie i ustawieniach.
        val afterRestart = newCoordinator().coordinator
        assertTrue(afterRestart.initialSyncPending.first())
        gate.complete(Unit)
        eventually<Boolean>({ afterRestart.initialSyncPending.first() }) { !it }
    }

    @Test
    fun failedFirstSyncEndsPendingSoGroupsCanOfferRetry() = runBlocking<Unit> {
        timetable.error = IOException("Plan lekcji: HTTP 503")
        coordinator.selectClass("o4")
        eventually({ t.notificationsRepo.observeLastSyncError().first() }) { it != null }
        eventually<Boolean>({ coordinator.initialSyncPending.first() }) { !it }
    }

    @Test fun backgroundSkipsFreshTimetableButManualRefreshDoesNot() = runTest {
        coordinator.sync(SyncRequest.full())
        val calls = timetable.calls.get()
        val background = coordinator.sync(SyncRequest.full(background = true))
        assertEquals(calls, timetable.calls.get())
        assertEquals(setOf(SyncOutcome.SUBSTITUTIONS), background.succeeded)
        coordinator.sync(SyncRequest.full())
        assertEquals(calls + 1, timetable.calls.get())
    }

    @Test fun failingAnnouncementsWaitForTheirIntervalInBackgroundRuns() = runTest {
        coordinator.sync(SyncRequest.full())
        anns.error = IOException("RSS nieczytelny")
        assertTrue(SyncOutcome.ANNOUNCEMENTS in coordinator.sync(SyncRequest.of(SyncOutcome.ANNOUNCEMENTS)).failures.keys)
        // Następny przebieg w tle nie pyta znów o ten sam zepsuty kanał (zastępstwa - jak zawsze).
        val background = coordinator.sync(SyncRequest.full(background = true))
        assertFalse(SyncOutcome.ANNOUNCEMENTS in background.failures.keys)
        assertEquals(setOf(SyncOutcome.SUBSTITUTIONS), background.succeeded)
    }

    @Test fun nightBackgroundRunSkipsRecentlyCheckedSourcesButManualRefreshDoesNot() = runTest {
        coordinator.sync(SyncRequest.full())
        coordinator.localTime = { java.time.LocalTime.of(23, 30) }
        val night = coordinator.sync(SyncRequest.full(background = true))
        assertEquals(emptySet<String>(), night.succeeded)
        assertTrue(night.failures.isEmpty())
        val manual = coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
        assertEquals(setOf(SyncOutcome.SUBSTITUTIONS), manual.succeeded)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun loadMoreIsGuardedBeforeDatabaseCountReturns() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var requests = 0
        val repository = object : pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository by t.announcementsRepo {
            override suspend fun count(): Int { gate.await(); return 0 }
            override suspend fun loadOlder(): Result<pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository.OlderResult> {
                requests++
                return Result.success(pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository.OlderResult(1, false))
            }
        }
        val vm = pl.zse.bydgoszcz.elektron.presentation.announcements.AnnouncementsViewModel(repository, coordinator)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("test", vm)
        try {
            vm.loadMore()
            runCurrent()
            vm.loadMore()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, requests)
        } finally {
            store.clearAndAwait(vm)
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun favoritesFilterSearchAndPagingStayOffline() = runBlocking {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        val favorite = AnnouncementEntity("fav", "Zażółć ważne", "https://zse/fav", Instant.now().epochSecond,
            "Zapisane", null, "<p>Treść</p>", false, "RSS_NEWS", isFavorite = true)
        db.announcementDao().upsertAll((1..11).map { favorite.copy(id = "fav$it") } + favorite.copy(id = "other", isFavorite = false))
        val repository = object : pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository by t.announcementsRepo {
            override suspend fun loadOlder() = error("Ulubione nie mogą pobierać archiwum")
        }
        val vm = pl.zse.bydgoszcz.elektron.presentation.announcements.AnnouncementsViewModel(repository, coordinator)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("favorites", vm)
        try {
            withTimeout(10_000) { vm.state.first { it.ready } }
            vm.setFavoritesOnly(true)
            val first = withTimeout(10_000) { vm.state.first { it.favoritesOnly } }
            assertEquals(10, first.items.size)
            assertEquals(11, first.favoriteCount)
            assertTrue(first.items.all { it.isFavorite })
            vm.loadMore()
            withTimeout(10_000) { vm.state.first { it.items.size == 11 } }
            vm.setQuery("zazolc")
            val searching = withTimeout(10_000) { vm.state.first { it.query == "zazolc" } }
            assertEquals(11, searching.items.size)
            assertFalse(searching.hasMore)
            // Wyszukiwanie ukrywa część zakładek; czyszczenie musi nadal objąć wszystkie.
            vm.setQuery("brak wyniku")
            val hidden = withTimeout(10_000) { vm.state.first { it.query == "brak wyniku" } }
            assertTrue(hidden.items.isEmpty())
            assertEquals(11, hidden.favoriteCount)
            vm.clearFavorites()
            withTimeout(10_000) { vm.state.first { it.favoriteCount == 0 } }
            assertEquals(12, db.announcementDao().count())
            assertEquals("<p>Treść</p>", db.announcementDao().getById("fav1")!!.fullHtml)
            assertNotNull(vm.message.value)
            vm.consumeMessage("stary komunikat")
            assertNotNull(vm.message.value)
            vm.consumeMessage(vm.message.value!!)
            assertNull(vm.message.value)
        } finally { store.clearAndAwait(vm); Dispatchers.resetMain() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun favoritesPagingDoesNotWaitForArchiveAndDoesNotShowItsError() = runBlocking {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        val favorite = AnnouncementEntity("fav", "Ogłoszenie", "https://zse/fav", Instant.now().epochSecond,
            null, null, null, false, "RSS_NEWS", isFavorite = true)
        db.announcementDao().upsertAll((1..11).map { favorite.copy(id = "fav$it") })
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val repository = object : pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository by t.announcementsRepo {
            override suspend fun count(): Int = 0
            override suspend fun loadOlder(): Result<pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository.OlderResult> {
                started.complete(Unit)
                gate.await()
                return Result.failure(IOException("offline"))
            }
        }
        val vm = pl.zse.bydgoszcz.elektron.presentation.announcements.AnnouncementsViewModel(repository, coordinator)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("paging", vm)
        try {
            withTimeout(10_000) { vm.state.first { it.ready } }
            vm.loadMore()
            withTimeout(10_000) { started.await() }
            vm.setFavoritesOnly(true)
            val favorites = withTimeout(10_000) { vm.state.first { it.favoritesOnly } }
            assertFalse(favorites.isLoadingMore)
            assertFalse(favorites.isInitialLoading)
            vm.loadMore()
            withTimeout(10_000) { vm.state.first { it.items.size == 11 } }
            gate.complete(Unit)
            withTimeout(10_000) {
                while (vm.state.value.loadMoreError || vm.state.value.isLoadingMore) delay(10)
            }
            vm.setFavoritesOnly(false)
            val all = withTimeout(10_000) { vm.state.first { !it.favoritesOnly && it.loadMoreError } }
            assertEquals(10, all.items.size)
        } finally { gate.complete(Unit); store.clearAndAwait(vm); Dispatchers.resetMain() }
    }

    @Test fun backgroundRetriesFailedManualRefreshDespiteRecentSuccess() = runBlocking {
        coordinator.sync(SyncRequest.full())
        timetable.error = java.io.IOException("offline")
        coordinator.sync(SyncRequest.of(SyncOutcome.TIMETABLE))
        val before = timetable.calls.get()
        timetable.error = null
        coordinator.sync(SyncRequest.full(background = true))
        assertEquals(before + 1, timetable.calls.get())
    }

    @Test fun missingLoadedMarkerOverridesBackgroundInterval() = runBlocking {
        coordinator.sync(SyncRequest.full())
        val before = timetable.calls.get()
        t.notificationsRepo.clearLoaded()
        coordinator.sync(SyncRequest.full(background = true))
        assertEquals(before + 1, timetable.calls.get())
        assertTrue(SyncOutcome.TIMETABLE in t.notificationsRepo.observeLoadedResources().first())
    }
    @Test fun refreshingSubstitutionsDoesNotClearTimetableFailure() = runBlocking<Unit> {
        timetable.error = IOException("Plan: HTTP 503")
        coordinator.sync(SyncRequest.full())
        val error = t.notificationsRepo.observeLastSyncError().first()
        coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
        assertEquals(error, t.notificationsRepo.observeLastSyncError().first())
        timetable.error = null
        coordinator.sync(SyncRequest.of(SyncOutcome.TIMETABLE))
        assertNull(t.notificationsRepo.observeLastSyncError().first())
    }
    @Test fun refreshingTimetableDoesNotClearSubstitutionFailure() = runBlocking<Unit> {
        subs.error = IOException("Zastępstwa: HTTP 503")
        coordinator.sync(SyncRequest.full())
        val error = t.notificationsRepo.observeLastSyncError().first()
        coordinator.sync(SyncRequest.of(SyncOutcome.TIMETABLE))
        assertEquals(error, t.notificationsRepo.observeLastSyncError().first())
        subs.error = null
        coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
        assertNull(t.notificationsRepo.observeLastSyncError().first())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun dashboardStopsLoadingAfterFailedInitialSync() = runBlocking<Unit> {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        val updates = object : pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository {
            override val availableUpdate = kotlinx.coroutines.flow.flowOf<pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate?>(null)
            override suspend fun check(force: Boolean) = Result.success<pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate?>(null)
            override suspend fun dismiss(versionName: String) = Unit
        }
        val vm = pl.zse.bydgoszcz.elektron.presentation.dashboard.DashboardViewModel(settings, t.substitutionsRepo,
            t.announcementsRepo, t.notificationsRepo, t.timetableRepo, updates, coordinator)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("dashboard", vm) }
        try {
            timetable.error = IOException("Offline")
            subs.error = IOException("Offline")
            anns.error = IOException("Offline")
            coordinator.sync(SyncRequest.full())
            val state = withTimeout(10_000) { vm.state.first { it.ready && !it.isSyncing && it.lastSyncError != null } }
            assertFalse(state.loadingTimetable)
            assertFalse(state.loadingSubs)
            assertFalse(state.loadingAnns)
        } finally { owner.clearAndAwait(vm); Dispatchers.resetMain() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun failedGroupSaveKeepsDraftAndDoesNotCloseEditor() = runBlocking<Unit> {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        val failedSettings = object : pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository by settings {
            override suspend fun saveGroupChoices(classId: String, changes: Map<String, String?>) { throw IOException("ENOSPC") }
        }
        val vm = pl.zse.bydgoszcz.elektron.presentation.groups.GroupsViewModel(failedSettings, t.timetableRepo,
            t.notificationsRepo, coordinator, pl.zse.bydgoszcz.elektron.widget.WidgetUpdater(context))
        val owner = androidx.lifecycle.ViewModelStore().apply { put("groups", vm) }
        var closed = false
        try {
            vm.state.first { it.ready }
            vm.setChoice("ang", "1/2"); vm.setChoice("wf", "2/2")
            vm.finish { closed = true }
            vm.saveError.first { it != null }
            val state = vm.state.first { it.hasChanges }
            assertFalse(closed)
            assertEquals(mapOf("ang" to "1/2", "wf" to "2/2"), state.selections)
        } finally { owner.clearAndAwait(vm); Dispatchers.resetMain() }
    }

    @Test fun failedRefreshKeepsTimeOfLastSuccessfulSourceRead() = runBlocking {
        coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
        val success = coordinator.observeSourceLastSuccess(SyncOutcome.SUBSTITUTIONS).first()
        assertNotNull(success)
        subs.error = IOException("offline")
        val result = coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
        assertTrue(SyncOutcome.SUBSTITUTIONS in result.failures)
        assertEquals(success, coordinator.observeSourceLastSuccess(SyncOutcome.SUBSTITUTIONS).first())
        assertNotNull(coordinator.observeSourceError(SyncOutcome.SUBSTITUTIONS).first())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun groupDraftSurvivesRecreationButDoesNotLeakIntoAnotherClass() = runBlocking {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        val owner = androidx.lifecycle.ViewModelStore()
        val handle = androidx.lifecycle.SavedStateHandle()
        val created = mutableListOf<androidx.lifecycle.ViewModel>()
        try {
            fun vm(saved: androidx.lifecycle.SavedStateHandle) = pl.zse.bydgoszcz.elektron.presentation.groups.GroupsViewModel(
                settings, t.timetableRepo, t.notificationsRepo, coordinator, pl.zse.bydgoszcz.elektron.widget.WidgetUpdater(context), saved)
                .also { created += it }
            val first = vm(handle); owner.put("first", first)
            first.state.first { it.ready }; first.startEditing("o3"); first.setChoice("ang", "1/2")
            first.state.first { it.selections["ang"] == "1/2" }
            val restored = vm(androidx.lifecycle.SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
            owner.put("restored", restored)
            restored.startEditing("o3")
            assertEquals("1/2", restored.state.first { it.ready }.selections["ang"])
            restored.startEditing("o4")
            assertTrue(restored.state.first { it.selections.isEmpty() }.selections.isEmpty())
        } finally { owner.clearAndAwait(*created.toTypedArray()); Dispatchers.resetMain() }
    }

    @Test fun failedClassWriteDoesNotClearPreviousCacheOrLoadedMarkers() = runBlocking {
        coordinator.sync(SyncRequest.full())
        val before = t.timetableRepo.getLessonsOnce("o3", LocalDate.now(), LocalDate.now().plusDays(28))
        val loaded = t.notificationsRepo.observeLoadedResources().first()
        val brokenSettings = object : pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository by settings {
            override suspend fun setSelectedClassId(id: String) { throw IOException("ENOSPC") }
        }
        val broken = TestCoordinator(context, db, brokenSettings, timetable, subs, anns, sink)
        broken.coordinator.resetCacheAndResync("o4")
        eventually({ broken.coordinator.operationError.value }, { it != null })
        assertEquals("o3", settings.selectedClassId.first())
        assertEquals(loaded, t.notificationsRepo.observeLoadedResources().first())
        assertEquals(before, t.timetableRepo.getLessonsOnce("o3", LocalDate.now(), LocalDate.now().plusDays(28)))
        assertFalse(broken.coordinator.isSyncing.first())
    }

    @Test fun infrastructureFailureReturnsOutcomeAndReleasesBusyState() = runBlocking {
        val brokenNotifications = object : pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository by t.notificationsRepo {
            override fun observeLoadedResources() = kotlinx.coroutines.flow.flow<Set<String>> { throw IOException("Błąd odczytu bazy") }
        }
        val broken = SyncCoordinator(settings, t.timetableRepo, t.substitutionsRepo, t.announcementsRepo,
            brokenNotifications, pl.zse.bydgoszcz.elektron.work.SubstitutionNotifier(settings, t.timetableRepo, brokenNotifications, sink),
            sink, pl.zse.bydgoszcz.elektron.widget.WidgetUpdater(context),
            pl.zse.bydgoszcz.elektron.work.LessonReminderScheduler(context, settings, t.timetableRepo), db)
        val outcome = broken.sync(SyncRequest.full())
        assertTrue(outcome.succeeded.isEmpty())
        assertEquals(SyncRequest.ALL_SOURCES, outcome.failures.keys)
        assertFalse(broken.isSyncing.first())
    }

    @Test fun firstFailureAfterUpgradeRetainsPreviouslyStoredSuccessTime() = runBlocking {
        db.syncStateDao().upsert(pl.zse.bydgoszcz.elektron.data.local.SyncStateEntity("source_${SyncOutcome.SUBSTITUTIONS}", 1234L, "ok", null))
        subs.error = IOException("offline")
        coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
        assertEquals(Instant.ofEpochSecond(1234L), coordinator.observeSourceLastSuccess(SyncOutcome.SUBSTITUTIONS).first())
    }

}
