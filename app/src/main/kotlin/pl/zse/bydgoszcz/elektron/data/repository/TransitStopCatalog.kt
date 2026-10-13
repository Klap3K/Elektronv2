package pl.zse.bydgoszcz.elektron.data.repository

import android.os.SystemClock
import android.content.Context
import android.util.AtomicFile
import pl.zse.bydgoszcz.elektron.data.local.writeAtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import org.json.JSONArray
import pl.zse.bydgoszcz.elektron.data.remote.http.readCancellable
import pl.zse.bydgoszcz.elektron.domain.model.SchoolTransit
import pl.zse.bydgoszcz.elektron.domain.model.TransitDestination
import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransitStopCatalog @Inject constructor(@ApplicationContext context: Context, private val client: OkHttpClient) {
    data class Catalog(val stops: List<TransitDestination>, val stale: Boolean, val updatedAt: Long, val platforms: List<TransitDestination>)
    private val file = AtomicFile(File(context.cacheDir, "transit_stops.json"))
    private val mutex = Mutex()
    private var cached: Catalog? = null
    private var lastFailure: Throwable? = null
    private var failedAt = 0L
    private val maxCatalogBytes = 2_000_000L

    /** Called from the destination picker or visible departures screen. No background work. */
    suspend fun load(force: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val previous = cached ?: runCatching {
                decodeCatalog(file.openRead().source().buffer().use { source ->
                    source.request(maxCatalogBytes + 1)
                    if (source.buffer.size > maxCatalogBytes) throw IOException("Zbyt duży katalog")
                    source.readUtf8()
                }, file.baseFile.lastModified())
            }.getOrNull()?.also { cached = it }
            if (!force && previous != null && now - previous.updatedAt in 0 until 24 * 60 * 60 * 1000L) return@withLock previous
            // Avoid repeated failed requests from the picker and departures screen.
            // Explicit refresh always bypasses this short, monotonic cooldown.
            lastFailure?.let { failure ->
                if (!force && SystemClock.elapsedRealtime() - failedAt in 0 until 60_000L) {
                    return@withLock previous?.copy(stale = true) ?: throw failure
                }
            }
            runCatchingCancellable {
                val request = Request.Builder().url("https://api.busearch.pl/bydgoszcz/api/stops")
                    .header("Accept", "application/json").build()
                val json = client.newCall(request).readCancellable { response ->
                    if (!response.isSuccessful) throw IOException("Katalog: HTTP ${response.code}")
                    val source = response.body?.source() ?: throw IOException("Pusta odpowiedź")
                    source.request(maxCatalogBytes + 1)
                    if (source.buffer.size > maxCatalogBytes) throw IOException("Zbyt duży katalog")
                    source.readUtf8()
                }
                val result = decodeCatalog(json, now)
                // A failed cache write must not discard an otherwise valid result.
                runCatching {
                    writeAtomicFile(file.baseFile, json.toByteArray(Charsets.UTF_8))
                }
                result.also { cached = it; lastFailure = null }
            }.getOrElse { error ->
                lastFailure = error
                failedAt = SystemClock.elapsedRealtime()
                previous?.copy(stale = true) ?: throw error
            }
        }
    }
    private fun decodeCatalog(json: String, updatedAt: Long): Catalog {
        val platforms = TransitStopParser.platforms(json)
        return Catalog(TransitStopParser.group(platforms), false, updatedAt, platforms)
    }
}

internal object TransitStopParser {
    fun parse(json: String): List<TransitDestination> = group(platforms(json))
    fun platforms(json: String): List<TransitDestination> {
        val array = JSONArray(json)
        require(array.length() in 1..10000) { "Nieprawidłowy katalog przystanków" }
        val platforms = (0 until array.length()).mapNotNull { i -> runCatching {
            val obj = array.getJSONObject(i)
            TransitDestination(obj.getString("nazwa").trim(), listOf(obj.getString("id")), obj.getDouble("lat"), obj.getDouble("lon"))
        }.getOrNull() }.distinctBy { it.stopIds.single() }
        require(platforms.isNotEmpty()) { "Katalog nie zawiera przystanków" }
        return platforms
    }
    /**
     * Stanowiska o tej samej nazwie dalej niż [SAME_STOP_METERS] od siebie (np. "Szkoła" w dwóch
     * dzielnicach) to osobne przystanki. Dawniej łączone w jeden ze środkiem pomiędzy nimi - planer
     * dostawał punkt daleko od obu i nie znajdował połączeń.
     */
    fun group(platforms: List<TransitDestination>): List<TransitDestination> {
        val destinations = platforms.groupBy { it.name }.flatMap { (name, stops) -> clusters(stops).map { cluster ->
            TransitDestination(name, cluster.flatMap { it.stopIds }, cluster.map { it.latitude }.average(), cluster.map { it.longitude }.average())
        } }.sortedWith(compareBy<TransitDestination> { it.name }.thenBy { SchoolTransit.distance(it.latitude, it.longitude) })
        require(destinations.isNotEmpty()) { "Katalog nie zawiera przystanków" }
        return destinations
    }

    const val SAME_STOP_METERS = 800.0

    /** Łączenie pojedyncze: stanowisko należy do zespołu, gdy jest bliżej niż [SAME_STOP_METERS] od któregoś z nich. */
    private fun clusters(stops: List<TransitDestination>): List<List<TransitDestination>> {
        if (stops.size < 2) return listOf(stops)
        val parent = IntArray(stops.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        for (i in stops.indices) for (j in i + 1 until stops.size) {
            if (SchoolTransit.distanceBetween(stops[i].latitude, stops[i].longitude, stops[j].latitude, stops[j].longitude) <= SAME_STOP_METERS) {
                parent[root(j)] = root(i)
            }
        }
        return stops.indices.groupBy(::root).values.map { group -> group.map { stops[it] } }
    }
}
