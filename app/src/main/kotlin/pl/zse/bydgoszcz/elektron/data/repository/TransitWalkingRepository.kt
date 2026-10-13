package pl.zse.bydgoszcz.elektron.data.repository

import android.content.Context
import android.util.AtomicFile
import pl.zse.bydgoszcz.elektron.data.local.writeAtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.BuildConfig
import pl.zse.bydgoszcz.elektron.data.remote.http.readCancellable
import pl.zse.bydgoszcz.elektron.domain.model.SchoolTransit
import pl.zse.bydgoszcz.elektron.domain.model.TransitDestination
import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

@Singleton
class TransitWalkingRepository internal constructor(private val client: OkHttpClient, private val cacheFile: File? = null,
    private val elapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    @Inject constructor(client: OkHttpClient, @ApplicationContext context: Context) : this(client, File(context.cacheDir, "transit_walks.json"))
    data class Path(val minutes: Int, val distanceMeters: Double, val savedAt: Long)
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Path>()
    private var restored = false
    private var lastRequest: Long? = null
    // Short, memory-only cooldowns avoid repeating optional routing work when offline
    // or when the router cannot reach a platform. Departure refreshes remain independent.
    private var retryAfter = 0L
    /** Ostatnia próba padła na sieci (nie na braku trasy) - po [retryAfter] warto ponowić. */
    @Volatile private var networkFailed = false
    private val unavailableUntil = linkedMapOf<String, Long>()
    private fun cacheKey(stop: TransitDestination) = "${SchoolTransit.LATITUDE},${SchoolTransit.LONGITUDE}:${stop.latitude},${stop.longitude}"

    private fun restoreCache() {
        if (!restored) {
            restored = true
            runCatching {
                val file = cacheFile ?: return@runCatching
                val data = AtomicFile(file).openRead().use { input ->
                    val bytes = ByteArray(64_001)
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count <= 0) break
                        size += count
                    }
                    require(size <= 64_000)
                    JSONObject(String(bytes, 0, size, Charsets.UTF_8))
                }
                data.keys().forEach { key ->
                    val item = data.getJSONObject(key)
                    val path = Path(item.getInt("minutes"), item.getDouble("distance"), item.getLong("saved"))
                    if (path.minutes in 1..120 && path.distanceMeters.isFinite() && path.distanceMeters in 0.0..10000.0) cache[key] = path
                }
            }
        }
    }
    suspend fun cachedFromSchool(stops: List<TransitDestination>): Map<String, Path> = withContext(Dispatchers.IO) {
        synchronized(cache) {
            restoreCache()
            cachedPaths(stops, System.currentTimeMillis())
        }
    }
    private fun cachedPaths(stops: List<TransitDestination>, now: Long) = buildMap {
        stops.forEach { stop -> cache[cacheKey(stop)]?.takeIf { now - it.savedAt in 0..604_800_000L }?.let { path ->
            stop.stopIds.forEach { put(it, path) }
        } }
    }
    /** Trasy nie udało się pobrać przez sieć, a przerwa po błędzie minęła (np. wrócił internet). */
    fun retryDue(): Boolean = networkFailed && elapsedMs() >= retryAfter

    suspend fun fromSchool(stops: List<TransitDestination>): Map<String, Path> = withContext(Dispatchers.IO) {
        lock.withLock {
            synchronized(cache) { restoreCache() }
            val now = System.currentTimeMillis()
            val unique = stops.distinctBy { cacheKey(it) }.take(50)
            val elapsed = elapsedMs()
            unavailableUntil.entries.removeAll { it.value <= elapsed }
            val missing = synchronized(cache) { unique.filter {
                cache[cacheKey(it)]?.let { path -> now - path.savedAt in 0..604_800_000L } != true &&
                    cacheKey(it) !in unavailableUntil
            } }
            // Nic do pobrania (wszystko w pamięci albo bez trasy) - nie ma czego ponawiać.
            if (missing.isEmpty()) networkFailed = false
            if (missing.isNotEmpty() && elapsed >= retryAfter) runCatchingCancellable {
                // At most one request per second, including retries after failures/cancellation.
                lastRequest?.let { delay((1000 - (elapsedMs() - it)).coerceAtLeast(0)) }
                lastRequest = elapsedMs()
                val coords = listOf("${SchoolTransit.LONGITUDE},${SchoolTransit.LATITUDE}") + missing.map { "${it.longitude},${it.latitude}" }
                val url = "https://routing.openstreetmap.de/routed-foot/table/v1/foot/${coords.joinToString(";")}?sources=0&destinations=${(1..missing.size).joinToString(";")}&annotations=duration,distance"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Elektron-Vulcan/${BuildConfig.VERSION_NAME} (+https://github.com/Klap3K/Elektronv2)")
                    .header("Accept", "application/json").build()
                val json = client.newCall(request).readCancellable {
                    if (!it.isSuccessful) throw IOException("Trasa piesza: HTTP ${it.code}")
                    val source = it.body?.source() ?: throw IOException("Pusta odpowiedź")
                    source.request(200_001)
                    if (source.buffer.size > 200_000) throw IOException("Zbyt duża odpowiedź")
                    JSONObject(source.readUtf8())
                }
                require(json.getString("code") == "Ok")
                require(json.getJSONArray("sources").getJSONObject(0).getDouble("distance") <= 50)
                val durations = json.getJSONArray("durations").getJSONArray(0)
                val distances = json.getJSONArray("distances").getJSONArray(0)
                val destinations = json.getJSONArray("destinations")
                val resolved = mutableMapOf<String, Path>()
                missing.forEachIndexed { i, stop ->
                    if (!durations.isNull(i) && !distances.isNull(i)) {
                        val duration = durations.getDouble(i); val distance = distances.getDouble(i)
                        if (duration.isFinite() && duration in 0.0..7200.0 && distance.isFinite() && distance in 0.0..10000.0 &&
                            destinations.getJSONObject(i).getDouble("distance") <= 50) {
                            resolved[cacheKey(stop)] = Path(ceil(duration / 60).toInt().coerceAtLeast(1), distance, now)
                        }
                    }
                }
                missing.filter { cacheKey(it) !in resolved }.forEach { unavailableUntil[cacheKey(it)] = elapsedMs() + 300_000L }
                while (unavailableUntil.size > 100) unavailableUntil.remove(unavailableUntil.keys.first())
                synchronized(cache) { cache.putAll(resolved) }
                synchronized(cache) { cache.entries.removeAll { now - it.value.savedAt !in 0..604_800_000L } }
                cacheFile?.takeIf { resolved.isNotEmpty() }?.let { file -> runCatching {
                    val data = JSONObject()
                    synchronized(cache) { cache.entries.take(100).map { it.key to it.value } }.forEach { (key, path) -> data.put(key, JSONObject().put("minutes", path.minutes).put("distance", path.distanceMeters).put("saved", path.savedAt)) }
                    writeAtomicFile(file, data.toString().toByteArray(Charsets.UTF_8))
                } }
            }.onSuccess { networkFailed = false }.onFailure { retryAfter = elapsedMs() + 60_000L; networkFailed = true }
            synchronized(cache) { cachedPaths(stops, now) }
        }
    }
}
