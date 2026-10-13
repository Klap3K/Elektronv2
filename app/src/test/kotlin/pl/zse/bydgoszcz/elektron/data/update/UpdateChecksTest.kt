package pl.zse.bydgoszcz.elektron.data.update

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.repository.inMemoryDb
import pl.zse.bydgoszcz.elektron.testutil.FakeSettings
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateChecksTest {
    @Test fun simultaneousAutomaticChecksOnlyFetchOnce() = runBlocking {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("[]".toResponseBody()).build()
        }.build()
        val db = inMemoryDb()
        try {
            val repo = UpdateRepositoryImpl(client, db.syncStateDao(), FakeSettings())
            val results = (1..5).map { async(Dispatchers.IO) { repo.check(false) } }.awaitAll()
            assertTrue(results.all { it.isSuccess })
            assertEquals(1, count.get())
        } finally { db.close(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun failedAutomaticCheckWaitsButUserCanRetry() = runBlocking {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Unavailable").body("".toResponseBody()).build()
        }.build()
        val db = inMemoryDb()
        try {
            val repo = UpdateRepositoryImpl(client, db.syncStateDao(), FakeSettings())
            assertTrue(repo.check(false).isFailure)
            assertTrue(repo.check(false).isFailure)
            assertEquals(1, count.get())
            assertTrue(repo.check(true).isFailure)
            assertEquals(2, count.get())
        } finally { db.close(); client.dispatcher.executorService.shutdown() }
    }
}
