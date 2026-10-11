package pl.zse.bydgoszcz.elektron.presentation.transit

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.repository.*
import pl.zse.bydgoszcz.elektron.domain.model.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Odjazdy przy starcie: zapisany wynik od razu, wyniki cząstkowe przy pierwszym wyszukiwaniu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TransitStartupTest {
    /**
     * Testy przechodzą przez prawdziwe wątki (OkHttp, DataStore, zapis pliku) - na wolnym runnerze CI
     * domyślne 10 s runTest bywało za mało i test kończył się UncompletedCoroutinesError.
     */
    private val IO_TIMEOUT = 60.seconds

    /**
     * Czekanie na stan ViewModelu z limitem w czasie rzeczywistym (wątek Default - limit runTest liczy
     * czas wirtualny) i opisem: przy zawieszeniu test mówi, na co czekał i jaki był ostatni stan.
     */
    private suspend fun <T> kotlinx.coroutines.flow.StateFlow<T>.await(label: String, predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeoutOrNull(20_000) { first(predicate) } }
            ?: throw AssertionError("Nie doczekano się: $label. Ostatni stan: $value")

    private val target = TransitDestination("Cel", listOf("99"), 53.1236, 18.007)
    private val depart = System.currentTimeMillis() + 900_000L

    /** Trzy stanowiska przy szkole, każde z jednym kursem; [slowStops] odpowiadają dopiero po [release]. */
    private fun client(release: CountDownLatch, slowStops: Set<Int>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val json = when {
            request.url.encodedPath.endsWith("/stops") ->
                """[{"id":24,"nazwa":"A","lat":53.12340,"lon":18.02664},{"id":25,"nazwa":"B","lat":53.12360,"lon":18.02664},{"id":26,"nazwa":"C","lat":53.12380,"lon":18.02664}]"""
            request.url.host == "routing.openstreetmap.de" -> """{"code":"Ok","durations":[[120]],"distances":[[100]]}"""
            else -> {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                val body = JSONObject(buffer.readUtf8())
                val stop = body.getJSONObject("from").getInt("stopId")
                if (stop in slowStops) check(release.await(10, TimeUnit.SECONDS))
                val whenMs = body.getLong("whenMs")
                val d = if (stop == 25) depart else depart + 120_000L
                """{"ok":true,"nastepneWhenMs":${whenMs + 600_000L},"polaczenia":[{"odcinki":[{"rodzaj":"przejazd","linia":"$stop","cel":"K",
                    "zPrzystanku":{"id":$stop,"nazwa":"X"},"doPrzystanku":{"id":99,"nazwa":"Cel"},"odjazdMs":$d,"przyjazdMs":${d + 300_000L}}]}]}"""
            }
        }
        okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun firstSearchShowsTheFirstAnsweringPlatformBeforeTheSlowOnes() = runTest(timeout = IO_TIMEOUT) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "transit-partial-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = directory }
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = TransitPreferencesRepository(PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(directory, "settings.preferences_pb") }))
        val release = CountDownLatch(1)
        val client = client(release, slowStops = setOf(24, 26))
        val catalog = TransitStopCatalog(context, client)
        val file = File(directory, "last.json")
        val vm = TransitViewModel(prefs, catalog, TransitJourneyRepository(client, catalog, TransitWalkingRepository(client), file))
        val owner = ViewModelStore().apply { put("transit", vm) }
        try {
            prefs.select(target); prefs.setEnabled(true)
            vm.settings.await("ustawienia gotowe") { it.ready && it.preferences.visible && it.preferences.destination?.key == target.key }
            vm.startJourneySession(Any(), 3)
            val partial = vm.journeys.await("wynik połączeń") { it != null }!!
            assertFalse(partial.complete)
            assertEquals(listOf("25"), partial.journeys.map { it.rides.first().fromId })
            assertTrue(vm.journeyLoading.value)
            release.countDown()
            val full = vm.journeys.await("wynik połączeń") { it?.complete == true && it.journeys.size == 3 }!!
            assertEquals(setOf("24", "25", "26"), full.journeys.map { it.rides.first().fromId }.toSet())
            // Pełny wynik trafia do pamięci podręcznej.
            assertTrue(withContext(Dispatchers.IO) { (1..500).any { file.exists() || run { Thread.sleep(10); false } } })
        } finally {
            release.countDown(); owner.clear(); ioScope.cancel(); ioScope.coroutineContext[Job]!!.join()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            Dispatchers.resetMain(); directory.deleteRecursively()
        }
    }

    @Test fun restartShowsTheSavedResultAtOnceAndRefreshesInTheBackground() = runTest(timeout = IO_TIMEOUT) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "transit-restart-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = directory }
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = TransitPreferencesRepository(PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(directory, "settings.preferences_pb") }))
        val release = CountDownLatch(1)
        // Stanowisko 25 odpowiada od razu (wynik cząstkowy), pozostałe czekają.
        val client = client(release, slowStops = setOf(24, 26))
        val catalog = TransitStopCatalog(context, client)
        val file = File(directory, "last.json")
        val now = System.currentTimeMillis()
        val ride = TransitJourney(listOf(TransitRide("6", "K", "25", "B", "99", "Cel", now + 600_000L, now + 900_000L)), 2, 100.0, walkAvailable = true)
        // Zapis sprzed 20 min: pokazany od razu, ale za stary do ponownego użycia - sprawdzenie w tle.
        TransitJourneyRepository(client, catalog, TransitWalkingRepository(client), file)
            .saveLast(TransitJourneyRepository.Result(target.key, listOf(ride), now - 20 * 60_000L, nextWhenMs = now + 3_600_000L))
        val vm = TransitViewModel(prefs, catalog, TransitJourneyRepository(client, catalog, TransitWalkingRepository(client), file))
        val owner = ViewModelStore().apply { put("transit", vm) }
        try {
            prefs.select(target); prefs.setEnabled(true)
            vm.settings.await("ustawienia gotowe") { it.ready && it.preferences.visible && it.preferences.destination?.key == target.key }
            vm.startJourneySession(Any(), 1)
            val shown = vm.journeys.await("wynik połączeń") { it != null }!!
            assertEquals(now - 20 * 60_000L, shown.fetchedAt)
            assertEquals(listOf(ride.key), shown.journeys.map { it.key })
            vm.journeyLoading.await("wczytywanie") { it }
            // Wynik cząstkowy (stanowisko 25) nie podmienia pokazanej, pełnej listy.
            withContext(Dispatchers.IO) { Thread.sleep(300) }
            assertEquals(now - 20 * 60_000L, vm.journeys.value!!.fetchedAt)
            release.countDown()
            val fresh = vm.journeys.await("wynik połączeń") { it!!.fetchedAt > now }!!
            assertTrue(fresh.complete)
            assertEquals(3, fresh.journeys.size)
        } finally {
            release.countDown(); owner.clear(); ioScope.cancel(); ioScope.coroutineContext[Job]!!.join()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            Dispatchers.resetMain(); directory.deleteRecursively()
        }
    }

    @Test fun freshSavedResultNeedsNoRequestEvenWhenTheScreenAsksTwice() = runTest(timeout = IO_TIMEOUT) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "transit-fresh-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = directory }
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = TransitPreferencesRepository(PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(directory, "settings.preferences_pb") }))
        val release = CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val fake = client(release, slowStops = emptySet())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/polaczenia")) requests.incrementAndGet()
            chain.proceed(chain.request())
        }.apply { fake.interceptors.forEach { addInterceptor(it) } }.build()
        val catalog = TransitStopCatalog(context, client)
        val file = File(directory, "last.json")
        val now = System.currentTimeMillis()
        val ride = TransitJourney(listOf(TransitRide("6", "K", "25", "B", "99", "Cel", now + 600_000L, now + 900_000L)), 2, 100.0, walkAvailable = true)
        TransitJourneyRepository(client, catalog, TransitWalkingRepository(client), file)
            .saveLast(TransitJourneyRepository.Result(target.key, listOf(ride), now - 2 * 60_000L, nextWhenMs = now + 3_600_000L))
        val vm = TransitViewModel(prefs, catalog, TransitJourneyRepository(client, catalog, TransitWalkingRepository(client), file))
        val owner = ViewModelStore().apply { put("transit", vm) }
        try {
            prefs.select(target); prefs.setEnabled(true)
            vm.settings.await("ustawienia gotowe") { it.ready && it.preferences.visible && it.preferences.destination?.key == target.key }
            // Jak TransitScreen: sesja i LaunchedEffect w tej samej klatce, zanim zapis zostanie odczytany.
            vm.startJourneySession(Any(), 1); vm.refreshJourneys(1); vm.refreshJourneys(1)
            assertTrue("pusta karta bez ładowania w trakcie odczytu", vm.journeys.value != null || vm.journeyLoading.value)
            assertEquals(listOf(ride.key), vm.journeys.await("wynik połączeń") { it != null }!!.journeys.map { it.key })
            withContext(Dispatchers.IO) { Thread.sleep(300) }
            assertEquals(0, requests.get())
            assertFalse(vm.journeyLoading.value)
        } finally {
            release.countDown(); owner.clear(); ioScope.cancel(); ioScope.coroutineContext[Job]!!.join()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            Dispatchers.resetMain(); directory.deleteRecursively()
        }
    }

    @Test fun pagingOnTopOfAnInterruptedPartialResultKeepsItIncomplete() = runTest(timeout = IO_TIMEOUT) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "transit-merge-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = directory }
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = TransitPreferencesRepository(PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(directory, "settings.preferences_pb") }))
        val release = CountDownLatch(1)
        val client = client(release, slowStops = setOf(24, 26))
        val catalog = TransitStopCatalog(context, client)
        val vm = TransitViewModel(prefs, catalog, TransitJourneyRepository(client, catalog, TransitWalkingRepository(client), File(directory, "last.json")))
        val owner = ViewModelStore().apply { put("transit", vm) }
        try {
            prefs.select(target); prefs.setEnabled(true)
            vm.settings.await("ustawienia gotowe") { it.ready && it.preferences.visible && it.preferences.destination?.key == target.key }
            val consumer = Any()
            vm.startJourneySession(consumer, 3)
            val partial = vm.journeys.await("wynik połączeń") { it != null }!!
            vm.stopJourneySession(consumer)          // ekran zamknięty w trakcie pierwszego sprawdzenia
            release.countDown()
            vm.loadJourneys(more = true, minimumCount = 3)
            val merged = vm.journeys.await("wynik połączeń") { it != null && it.fetchedAt > partial.fetchedAt }!!
            assertFalse("stanowiska bez odpowiedzi nadal brakują", merged.complete)
            assertFalse(merged.canReuse(target.key, System.currentTimeMillis()))
        } finally {
            release.countDown(); owner.clear(); ioScope.cancel(); ioScope.coroutineContext[Job]!!.join()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            Dispatchers.resetMain(); directory.deleteRecursively()
        }
    }
}
