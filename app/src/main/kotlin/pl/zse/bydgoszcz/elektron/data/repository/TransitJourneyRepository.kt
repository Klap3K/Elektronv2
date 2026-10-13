package pl.zse.bydgoszcz.elektron.data.repository

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.data.remote.http.readCancellable
import pl.zse.bydgoszcz.elektron.domain.model.*
import java.io.IOException
import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransitJourneyRepository internal constructor(private val client: OkHttpClient, private val catalog: TransitStopCatalog,
    private val walking: TransitWalkingRepository, private val cacheFile: java.io.File?) {
    @Inject constructor(client: OkHttpClient, catalog: TransitStopCatalog, walking: TransitWalkingRepository,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context) :
        this(client, catalog, walking, java.io.File(context.cacheDir, "transit_last_result.json"))
    internal constructor(client: OkHttpClient, catalog: TransitStopCatalog) : this(client, catalog, TransitWalkingRepository(client), null)
    data class Result(val destinationKey: String, val journeys: List<TransitJourney>, val fetchedAt: Long, val originKey: String? = null, val nextWhenMs: Long? = null, val allowTransfers: Boolean = false, val boardingStops: List<TransitDestination> = emptyList(), val platformCursors: Map<String, Long>? = null, val partialFailure: Boolean = false,
        /** false - wynik cząstkowy (część stanowisk jeszcze się sprawdza); pokazywany, ale nie do ponownego użycia. */
        val complete: Boolean = true) {
        // Godziny są rozkładowe, a minione kursy UI odfiltrowuje lokalnie - dawniej wynik wygasał
        // po minucie i widoczna karta/zakładka pytała planer o każde stanowisko co minutę.
        // Niepełny wynik (część stanowisk bez odpowiedzi) ponawiamy po minucie.
        fun canReuse(key: String, nowMs: Long, origin: String? = null, transfers: Boolean = false): Boolean = complete &&
            destinationKey == key && originKey == origin && allowTransfers == transfers &&
            nowMs - fetchedAt in 0 until (if (partialFailure) RETRY_PARTIAL_MS else REUSE_MS) &&
            (journeys.isEmpty() || journeys.any { it.departureMs > nowMs })
        companion object {
            const val REUSE_MS = 10 * 60_000L
            const val RETRY_PARTIAL_MS = 60_000L
        }
    }
    private data class Batch(val platform: String, val journeys: List<TransitJourney>, val cursor: Long?, val failed: Boolean = false)

    /**
     * Co wiadomo po ostatnim pełnym sprawdzeniu danego celu: stanowiska z kursem i wszystkie sprawdzone.
     * Ważne [knowledgeTtlMs] - kursy zależą od pory dnia (wieczorem bez kursu, rano z kursem), a skrócone
     * zapytanie nie znalazłoby kursu dopiero w drugim oknie; potem znów pełne sprawdzenie.
     */
    private data class Knowledge(val useful: Set<String>, val queried: Set<String>, val at: Long)
    private val knowledge = mutableMapOf<Triple<String, String?, Boolean>, Knowledge>()
    internal var knowledgeTtlMs = KNOWLEDGE_TTL_MS

    /**
     * [onPartial] - wynik cząstkowy po każdym stanowisku z kursami (pierwsze odjazdy widać, zanim
     * odpowiedzą wszystkie stanowiska). Stanowiska, z których ostatnio był kurs do celu, są pytane
     * najpierw; te, z których przy ostatnim pełnym sprawdzeniu nie było żadnego, dostają jedno
     * zapytanie zamiast dwóch (bez drugiego okna czasowego).
     */
    suspend fun find(destination: TransitDestination, origin: TransitDestination? = null, afterMs: Long? = null, allowTransfers: Boolean = false, platformCursors: Map<String, Long>? = null,
        onPartial: (suspend (Result) -> Unit)? = null): Result = withContext(Dispatchers.IO) {
        val now = AppClock.millis()
        val platforms = catalog.load().platforms.associateBy { it.stopIds.single() }
        val knowledgeKey = Triple(destination.key, origin?.key, allowTransfers)
        val known = synchronized(knowledge) { knowledge[knowledgeKey] }?.takeIf { now - it.at in 0 until knowledgeTtlMs }
        // A selected stop is a constraint, not just a coordinate near other stops.
        // Query each platform of the selected stop using the API's documented stopId.
        // Query boarding platforms, not a walking journey starting at school. Otherwise
        // the server itself hides rides that depart before the student can walk there.
        val nearby = origin?.stopIds ?: platforms.values.filter {
            SchoolTransit.distance(it.latitude, it.longitude) <= 1000
        }.sortedBy { SchoolTransit.distance(it.latitude, it.longitude) }.map { it.stopIds.single() }
        // Stabilne sortowanie: w obrębie grupy zostaje kolejność od najbliższego szkoły.
        val originIds = if (known == null) nearby else nearby.sortedBy { if (it in known.useful) 0 else 1 }
        val startingPoints = originIds.map { JSONObject().put("stopId", it.toLongOrNull() ?: it) }
        suspend fun assemble(batches: List<Batch>, complete: Boolean): Result {
            val journeys = batches.flatMap { it.journeys }
            val nextCursors = batches.mapNotNull { batch -> batch.cursor?.let { batch.platform to it } }.toMap()
            val boardingStops = journeys.mapNotNull { platforms[it.rides.first().fromId] }.distinctBy { it.key }
            val paths = walking.cachedFromSchool(boardingStops)
            val withPaths = journeys.map { trip ->
                val path = paths[trip.rides.first().fromId]
                if (path != null) trip.copy(walkMinutes = path.minutes, distanceMeters = path.distanceMeters, walkAvailable = true)
                else trip.copy(walkAvailable = false, walkPending = true)
            }
            val at = AppClock.millis()
            return Result(destination.key, TransitJourneys.rank(withPaths, at, origin, allowTransfers), at, origin?.key,
                nextCursors.values.minOrNull(), allowTransfers, boardingStops, nextCursors,
                partialFailure = batches.any { it.failed }, complete = complete)
        }
        val published = mutableListOf<Batch>()
        val publishLock = kotlinx.coroutines.sync.Mutex()
        val batches = kotlinx.coroutines.coroutineScope {
            val permits = kotlinx.coroutines.sync.Semaphore(PARALLEL_REQUESTS)
            // Each platform has its own search window. Finished platforms must not be
            // fetched again, and later windows must not restart at another platform's cursor.
            startingPoints.filter { afterMs == null || platformCursors == null || it.optString("stopId") in platformCursors }.map { from -> async {
                val platform = from.optString("stopId")
                val windows = if (known != null && platform in known.queried && platform !in known.useful) 1 else 2
                val batch = permits.withPermit {
                    runCatchingCancellable {
                        var whenMs = if (afterMs != null) platformCursors?.get(platform) ?: afterMs else now
                        var cursor: Long? = null
                        val trips = mutableListOf<TransitJourney>()
                        repeat(windows) {
                            val body = JSONObject().put("from", from)
                                .put("to", JSONObject().put("lat", destination.latitude).put("lon", destination.longitude))
                                .put("maxPrzesiadki", if (allowTransfers) 1 else 0).put("ksztalt", false).put("whenMs", whenMs)
                            val request = Request.Builder().url("https://api.busearch.pl/bydgoszcz/api/polaczenia")
                                .header("Accept", "application/json").post(body.toString().toRequestBody("application/json".toMediaType())).build()
                            val response = client.newCall(request).readCancellable {
                                if (!it.isSuccessful) throw IOException("Połączenia: HTTP ${it.code}")
                                val source = it.body?.source() ?: throw IOException("Pusta odpowiedź")
                                source.request(4_000_001)
                                if (source.buffer.size > 4_000_000) throw IOException("Zbyt duża odpowiedź")
                                JSONObject(source.readUtf8())
                            }
                            // Wybrany przystanek: wybór oferuje go według środka zespołu stanowisk (do 1 km),
                            // więc jego dalsze stanowisko może leżeć tuż za 1 km - jego kursy znikały bez śladu.
                            trips += TransitJourneyParser.parse(response, destination, platforms, now, allowTransfers,
                                if (origin != null) TransitJourneyParser.MAX_CHOSEN_ORIGIN_METERS else TransitJourneyParser.MAX_BOARDING_METERS)
                                .filter { origin == null || it.rides.first().fromId in origin.stopIds }
                            cursor = response.optLong("nastepneWhenMs", 0).takeIf { it > whenMs && it <= now + 24 * 60 * 60_000L }
                            if (trips.isNotEmpty() || cursor == null) return@runCatchingCancellable Batch(platform, trips.toList(), cursor)
                            whenMs = cursor!!
                        }
                        Batch(platform, trips.toList(), cursor)
                    }.getOrElse { Batch(platform, emptyList(), null, failed = true) }
                }
                if (onPartial != null && batch.journeys.isNotEmpty()) publishLock.withLock {
                    published += batch
                    onPartial(assemble(published.toList(), complete = false))
                }
                batch
            } }.map { it.await() }
        }
        if (batches.isNotEmpty() && batches.all { it.failed }) throw IOException("Nie udało się sprawdzić żadnego stanowiska")
        val result = assemble(batches, complete = true)
        if (afterMs == null && !result.partialFailure) synchronized(knowledge) {
            knowledge[knowledgeKey] = Knowledge(batches.filter { it.journeys.isNotEmpty() }.mapTo(HashSet()) { it.platform }, batches.mapTo(HashSet()) { it.platform }, now)
        }
        result
    }

    private val cacheLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Ostatni pełny wynik (rozkład się nie zmienia z minuty na minutę) - po ponownym uruchomieniu
     * aplikacji karta i zakładka pokazują go od razu, a nowe sprawdzenie idzie w tle.
     */
    suspend fun saveLast(result: Result) {
        val file = cacheFile ?: return
        if (!result.complete) return
        withContext(Dispatchers.IO) { cacheLock.withLock {
            runCatching {
                pl.zse.bydgoszcz.elektron.data.local.writeAtomicFile(file, TransitResultCodec.encode(result).toByteArray(Charsets.UTF_8))
            }.onFailure { android.util.Log.w("TransitJourneys", "Nie udało się zapisać ostatnich połączeń", it) }
        } }
    }

    /** Zapisany wynik z nadchodzącymi odjazdami, najwyżej [CACHE_MAX_AGE_MS] wstecz; null, gdy brak. */
    suspend fun loadLast(nowMs: Long = AppClock.millis()): Result? {
        // Bez pliku (testy) - bez przełączania wątku, pierwsze zapytanie startuje od razu.
        val file = cacheFile ?: return null
        return withContext(Dispatchers.IO) { cacheLock.withLock {
            runCatching {
                if (!file.exists()) return@runCatching null
                val text = android.util.AtomicFile(file).openRead().use { input ->
                    val bytes = input.readBytes()
                    require(bytes.size <= 1_000_000)
                    String(bytes, Charsets.UTF_8)
                }
                TransitResultCodec.decode(text)?.let { restored(it, nowMs) }
            }.getOrNull()
        }?.also { result ->
            // Kolejność stanowisk po ponownym uruchomieniu: najpierw te, z których był kurs.
            synchronized(knowledge) {
                knowledge.putIfAbsent(Triple(result.destinationKey, result.originKey, result.allowTransfers),
                    Knowledge(result.boardingStops.flatMapTo(HashSet()) { it.stopIds }, emptySet(), nowMs))
            }
        } }
    }

    companion object {
        const val PARALLEL_REQUESTS = 3
        const val KNOWLEDGE_TTL_MS = 60 * 60_000L
        const val CACHE_MAX_AGE_MS = 12 * 60 * 60_000L

        /** Tylko nadchodzące odjazdy ze świeżego zapisu; czas sprawdzenia zostaje (napis "Ostatnio sprawdzono"). */
        internal fun restored(result: Result, nowMs: Long): Result? {
            if (nowMs - result.fetchedAt !in 0..CACHE_MAX_AGE_MS) return null
            val upcoming = result.journeys.filter { it.departureMs > nowMs }
            return if (upcoming.isEmpty()) null else result.copy(journeys = upcoming)
        }
    }
    /** Dojście nie było dostępne przez brak sieci, a można już ponowić. */
    fun walkingRetryDue(): Boolean = walking.retryDue()

    /** Walking requests do not block displaying or paging departures. */
    suspend fun enrichWalking(result: Result): Result = withContext(Dispatchers.IO) {
        val now = AppClock.millis()
        val pendingStops = result.journeys.filter { it.walkPending && it.departureMs > now }.map { it.rides.first().fromId }.toSet()
        val paths = walking.fromSchool(result.boardingStops.filter { stop -> stop.stopIds.any { it in pendingStops } })
        result.copy(journeys = result.journeys.map { trip ->
            if (!trip.walkPending) return@map trip
            val path = paths[trip.rides.first().fromId]
            if (path != null) trip.copy(walkMinutes = path.minutes, distanceMeters = path.distanceMeters, walkAvailable = true, walkPending = false)
            else trip.copy(walkAvailable = false, walkPending = false)
        })
    }

}

internal object TransitJourneyParser {
    /** Limit odległości stanowiska od szkoły (sanity); przy wybranym przystanku początkowym luźniejszy. */
    const val MAX_BOARDING_METERS = 1000.0
    const val MAX_CHOSEN_ORIGIN_METERS = 2000.0

    fun parse(response: JSONObject, destination: TransitDestination, platforms: Map<String, TransitDestination>, nowMs: Long, allowTransfers: Boolean = true,
        maxBoardingMeters: Double = MAX_BOARDING_METERS): List<TransitJourney> {
        require(response.getBoolean("ok")) { "Planer nie zwrócił poprawnych danych" }
        val options = response.getJSONArray("polaczenia")
        require(options.length() <= 200)
        return (0 until options.length()).mapNotNull { index -> runCatching {
            val segments = options.getJSONObject(index).getJSONArray("odcinki")
            val rides = mutableListOf<TransitRide>()
            var reached = false
            for (i in 0 until segments.length()) {
                val leg = segments.getJSONObject(i)
                if (leg.optString("rodzaj") != "przejazd") continue
                val from = leg.getJSONObject("zPrzystanku")
                val to = leg.getJSONObject("doPrzystanku")
                val fromId = from.getString("id")
                val departure = leg.getLong("odjazdMs")
                var toId = to.getString("id")
                var arrival = leg.getLong("przyjazdMs")
                var toName = to.getString("nazwa")
                if (fromId in destination.stopIds) break // already at the chosen stop
                val calls = leg.optJSONArray("poDrodze")
                if (calls != null) {
                    val fromIndex = (0 until calls.length()).firstOrNull { calls.getJSONObject(it).optString("id") == fromId }
                    val targetIndex = fromIndex?.let { start -> (start + 1 until calls.length()).firstOrNull {
                        calls.getJSONObject(it).optString("id") in destination.stopIds
                    } }
                    if (targetIndex != null) {
                        val target = calls.getJSONObject(targetIndex)
                        toId = target.getString("id"); arrival = target.getLong("at"); toName = destination.name; reached = true
                    }
                }
                if (toId in destination.stopIds) reached = true
                require(departure >= nowMs && arrival >= departure && arrival <= nowMs + 24 * 60 * 60_000L)
                val line = leg.getString("linia"); val direction = leg.getString("cel")
                require(line.isNotBlank() && line.length <= 32 && direction.isNotBlank() && direction.length <= 200)
                if (rides.isNotEmpty()) {
                    val previous = rides.last()
                    val walkMs = if (previous.toId == fromId) 0L else {
                        val exit = platforms[previous.toId] ?: return@runCatching null
                        val boarding = platforms[fromId] ?: return@runCatching null
                        val distance = SchoolTransit.distanceBetween(exit.latitude, exit.longitude, boarding.latitude, boarding.longitude)
                        require(distance <= 500) // reject distant, disconnected transfers
                        SchoolTransit.walkMinutes(distance) * 60_000L
                    }
                    require(departure >= previous.arrivalMs + walkMs + 60_000L)
                }
                rides += TransitRide(line, direction, fromId, from.getString("nazwa"), toId, toName, departure, arrival)
                if (reached) break
            }
            require(reached && rides.size in 1..(if (allowTransfers) 2 else 1))
            val start = platforms[rides.first().fromId] ?: return@runCatching null
            val distance = SchoolTransit.distance(start.latitude, start.longitude)
            require(distance <= maxBoardingMeters)
            TransitJourney(rides, SchoolTransit.walkMinutes(distance), distance)
        }.getOrNull() }
    }
}
