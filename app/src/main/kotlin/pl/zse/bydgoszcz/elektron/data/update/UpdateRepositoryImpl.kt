package pl.zse.bydgoszcz.elektron.data.update

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.zse.bydgoszcz.elektron.data.remote.http.readCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.zse.bydgoszcz.elektron.BuildConfig
import pl.zse.bydgoszcz.elektron.data.local.SyncStateDao
import pl.zse.bydgoszcz.elektron.data.local.SyncStateEntity
import pl.zse.bydgoszcz.elektron.domain.model.AppVersion
import pl.zse.bydgoszcz.elektron.domain.model.UpdateChannel
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate
import pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sprawdzanie nowej wersji w GitHub Releases. Stan (kiedy sprawdzono, najnowsza wersja,
 * zamknięty baner) w tabeli sync_state — bez migracji bazy.
 *
 * Zapytanie idzie przez wspólny klient OkHttp: cache HTTP z rewalidacją (ETag), więc
 * odpowiedź "bez zmian" (304) nie liczy się do limitu GitHuba (60 zapytań/h bez logowania).
 */
@Singleton
class UpdateRepositoryImpl @Inject constructor(
    private val client: OkHttpClient,
    private val syncStateDao: SyncStateDao,
    private val settings: SettingsRepository
) : UpdateRepository {

    private val checkMutex = Mutex()
    private val currentVersion = BuildConfig.VERSION_NAME

    override val availableUpdate: Flow<AppUpdate?> = combine(
        syncStateDao.observe(KEY_LATEST),
        syncStateDao.observe(KEY_DISMISSED),
        settings.updateChannel
    ) { latest, dismissed, channel ->
        if (!BuildConfig.UPDATE_CHECK) return@combine null
        // Zapisana wersja z kanału beta nie pokazuje się po powrocie na kanał stabilny.
        val update = decode(latest?.message)?.takeIf { channel.accepts(it.versionName, it.prerelease) }
            ?: return@combine null
        // "Później" odkłada baner tej wersji na DISMISS_SECONDS (dawniej na zawsze - kto raz
        // kliknął "Później", nie dowiadywał się już o tej wersji).
        val postponed = dismissed != null && dismissed.message == update.versionName &&
            Instant.now().epochSecond - dismissed.lastSyncEpochSeconds < DISMISS_SECONDS
        when {
            !AppVersion.isNewer(update.versionName, currentVersion) -> null
            postponed -> null
            else -> update
        }
    }

    override suspend fun check(force: Boolean): Result<AppUpdate?> = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            // Guard for distributions which disable update checks.
            if (!BuildConfig.UPDATE_CHECK) return@withLock Result.success(null)
            runCatchingCancellable {
                val now = Instant.now().epochSecond
                val channel = settings.updateChannel.first()
                if (!force) {
                    // Wynik z innego kanału (zmiana kanału bez internetu) nie jest aktualny dla tego.
                    val checked = syncStateDao.get(KEY_CHECKED)?.takeIf { it.message == channel.name }
                    val last = checked?.lastSyncEpochSeconds ?: 0L
                    if (now - last in 0 until CHECK_INTERVAL_SECONDS) {
                        return@runCatchingCancellable decode(syncStateDao.get(KEY_LATEST)?.message)
                            ?.takeIf { channel.accepts(it.versionName, it.prerelease) }
                            ?.takeIf { AppVersion.isNewer(it.versionName, currentVersion) }
                    }
                }
                if (!force) {
                    val attempted = syncStateDao.get(KEY_ATTEMPTED)?.lastSyncEpochSeconds ?: 0L
                    if (now - attempted in 0 until RETRY_INTERVAL_SECONDS) {
                        throw IOException("Sprawdzenie aktualizacji zostanie ponowione później")
                    }
                }
                syncStateDao.upsert(SyncStateEntity(KEY_ATTEMPTED, now, "attempt", null))
                val request = Request.Builder().url(RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .get().build()
                val releases = client.newCall(request).readCancellable { resp ->
                    if (!resp.isSuccessful) throw IOException("GitHub: HTTP ${resp.code}")
                    GitHubReleases.parse(resp.body?.string() ?: throw IOException("GitHub: pusta odpowiedź"))
                }
                val newest = newestFor(channel, releases)
                syncStateDao.upsert(SyncStateEntity(KEY_CHECKED, now, "ok", channel.name))
                // Także "brak" - inaczej po zmianie kanału zostawała zapisana wersja z poprzedniego.
                syncStateDao.upsert(SyncStateEntity(KEY_LATEST, now, "ok", newest?.let(::encode)))
                newest?.takeIf { AppVersion.isNewer(it.versionName, currentVersion) }
            }
        }
    }

    override suspend fun dismiss(versionName: String) = withContext(Dispatchers.IO) {
        syncStateDao.upsert(SyncStateEntity(KEY_DISMISSED, Instant.now().epochSecond, "ok", versionName))
    }

    internal companion object {
        // Linie: wersja, strona, [APK, SHA-256, rozmiar, Pre-release] - starsze zapisy (2-5 linii) nadal czytelne.
        fun encode(u: AppUpdate) =
            listOf(u.versionName, u.pageUrl, u.apkUrl.orEmpty(), u.sha256.orEmpty(), u.sizeBytes.toString(), if (u.prerelease) "1" else "0")
                .joinToString("\n")

        fun decode(raw: String?): AppUpdate? {
            val parts = raw?.split('\n') ?: return null
            if (parts.size < 2 || parts[0].isBlank()) return null
            return AppUpdate(
                versionName = parts[0],
                pageUrl = parts[1],
                apkUrl = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                sha256 = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
                sizeBytes = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
                // Zapis sprzed kanałów (bez tej linii) - ostrożnie jak Pre-release: kanał Stabilne go
                // nie pokaże, aż do kolejnego sprawdzenia GitHuba.
                prerelease = parts.getOrNull(5)?.let { it == "1" } ?: true
            )
        }

        /** Najnowsze wydanie z kanału (bez szkiców); null, gdy w kanale nie ma żadnego. */
        fun newestFor(channel: UpdateChannel, releases: List<GitHubRelease>): AppUpdate? =
            releases.filter { !it.draft && channel.accepts(it.tag, it.prerelease) }
                .maxWithOrNull { a, b -> AppVersion.compare(a.tag, b.tag) }
                ?.let { AppUpdate(it.tag.removePrefix("v").removePrefix("V"), it.pageUrl, it.apkUrl, it.apkSha256, it.apkSize, it.prerelease) }

        // 100 wydań w jednym zapytaniu: przy serii wersji testowych wydanie stabilne wypadało z 10
        // najnowszych i kanał Stabilne przestawał je widzieć.
        const val RELEASES_API = "https://api.github.com/repos/Klap3K/Elektronv2/releases?per_page=100"
        const val CHECK_INTERVAL_SECONDS = 5 * 60L
        const val KEY_ATTEMPTED = "update_attempted"
        const val RETRY_INTERVAL_SECONDS = 2 * 60L
        const val KEY_CHECKED = "update_checked"
        const val KEY_LATEST = "update_latest"
        const val KEY_DISMISSED = "update_dismissed"
        const val DISMISS_SECONDS = 24 * 60 * 60L
    }
}
