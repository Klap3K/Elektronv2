package pl.zse.bydgoszcz.elektron.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.domain.model.TransitDestination
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class TransitWalkingRepositoryTest {
    private val stop = TransitDestination("Start", listOf("24"), 53.12194, 18.02764)
    @Test fun routesAreBatchedRoundedUpAndRestoredFromDiskWithoutNetwork() = runBlocking {
        val file = File.createTempFile("transit-walk", ".json").apply { delete() }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("routing.openstreetmap.de", chain.request().url.host)
            assertTrue(chain.request().header("User-Agent")!!.startsWith("Elektron-Vulcan/"))
            assertEquals("1;2", chain.request().url.queryParameter("destinations"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":"Ok","sources":[{"distance":0}],"destinations":[{"distance":0},{"distance":1}],"durations":[[192,301]],"distances":[[240.2,400]]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val stops = listOf(stop, stop.copy(stopIds = listOf("25"), latitude = 53.122))
            val first = TransitWalkingRepository(client, file).fromSchool(stops)
            assertEquals(4, first["24"]!!.minutes)
            assertEquals(6, first["25"]!!.minutes)
            assertEquals(240.2, first["24"]!!.distanceMeters, 0.01)
            assertEquals(first, TransitWalkingRepository(client, file).fromSchool(stops))
            assertEquals(1, requests)
        } finally { file.delete(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun routingFailureDoesNotInventAWalkingTime() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("Brak sieci") }.build()
        try { assertTrue(TransitWalkingRepository(client).fromSchool(listOf(stop)).isEmpty()) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun routingServerErrorsDoNotTriggerUnthrottledHttpRetries() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("routing-retry-").toFile()
        var attempts = 0
        val client = pl.zse.bydgoszcz.elektron.data.remote.http.OkHttpClientProvider.create(false, "1.0.0-rc6-dev", directory)
            .newBuilder().addInterceptor { chain ->
                attempts++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build()
        try {
            assertTrue(TransitWalkingRepository(client).fromSchool(listOf(stop)).isEmpty())
            assertEquals(1, attempts)
        } finally { client.cache?.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); directory.deleteRecursively() }
    }
    @Test fun cacheReadsNeverWaitForAnInFlightWalkingRequest() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":"Ok","sources":[{"distance":0}],"destinations":[{"distance":0}],"durations":[[192]],"distances":[[240.2]]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = TransitWalkingRepository(client)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { repo.fromSchool(listOf(stop)) }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(kotlinx.coroutines.withTimeout(1000) { repo.cachedFromSchool(listOf(stop)) }.isEmpty())
            release.countDown()
            assertEquals(4, job.await()["24"]!!.minutes)
        } finally {
            release.countDown(); job.cancel()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun failedRoutingHasAShortCooldownButCanRecover() = runBlocking {
        var elapsed = 10_000L
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (requests == 1) throw java.io.IOException("Offline")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":"Ok","sources":[{"distance":0}],"destinations":[{"distance":0}],"durations":[[192]],"distances":[[240.2]]}""".toResponseBody()).build()
        }.build()
        val repo = TransitWalkingRepository(client, elapsedMs = { elapsed })
        try {
            assertTrue(repo.fromSchool(listOf(stop)).isEmpty())
            elapsed += 59_999L
            // Even another boarding platform must not repeat work during an outage.
            assertTrue(repo.fromSchool(listOf(stop.copy(latitude = stop.latitude + 0.001))).isEmpty())
            assertEquals(1, requests)
            elapsed++
            assertEquals(4, repo.fromSchool(listOf(stop))["24"]!!.minutes)
            assertEquals(2, requests)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun unreachablePlatformsAreTemporarilyRememberedWithoutBlockingOtherStops() = runBlocking {
        var elapsed = 10_000L
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            // Every request should contain only the one currently unresolved platform.
            assertEquals("1", chain.request().url.queryParameter("destinations"))
            val unavailable = requests != 2
            val duration = if (unavailable) "null" else "192"
            val distance = if (unavailable) "null" else "240.2"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":"Ok","sources":[{"distance":0}],"destinations":[{"distance":0}],"durations":[[$duration]],"distances":[[$distance]]}""".toResponseBody()).build()
        }.build()
        val repo = TransitWalkingRepository(client, elapsedMs = { elapsed })
        val other = stop.copy(stopIds = listOf("25"), latitude = stop.latitude + 0.001)
        try {
            assertTrue(repo.fromSchool(listOf(stop)).isEmpty())
            assertTrue(repo.fromSchool(listOf(stop)).isEmpty())
            elapsed += 1_000L
            assertEquals(4, repo.fromSchool(listOf(stop, other))["25"]!!.minutes)
            assertEquals(2, requests)
            elapsed = 310_000L
            val paths = repo.fromSchool(listOf(stop, other))
            assertFalse(paths.containsKey("24"))
            assertEquals(4, paths["25"]!!.minutes)
            assertEquals(3, requests)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun cancellationDoesNotStartTheFailureCooldown() = runBlocking {
        var elapsed = 10_000L
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 1) {
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":"Ok","sources":[{"distance":0}],"destinations":[{"distance":0}],"durations":[[192]],"distances":[[240.2]]}""".toResponseBody()).build()
        }.build()
        val repo = TransitWalkingRepository(client, elapsedMs = { elapsed })
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { repo.fromSchool(listOf(stop)) }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel(); job.join()
            release.countDown()
            elapsed += 1_000L
            assertEquals(4, repo.fromSchool(listOf(stop))["24"]!!.minutes)
            assertEquals(2, requests.get())
        } finally {
            release.countDown(); job.cancel()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

}
