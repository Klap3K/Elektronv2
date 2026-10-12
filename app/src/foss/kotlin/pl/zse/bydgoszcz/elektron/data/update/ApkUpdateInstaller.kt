package pl.zse.bydgoszcz.elektron.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.zse.bydgoszcz.elektron.BuildConfig
import pl.zse.bydgoszcz.elektron.data.remote.http.OkHttpClientProvider
import pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate
import pl.zse.bydgoszcz.elektron.domain.repository.InstallState
import pl.zse.bydgoszcz.elektron.domain.repository.UpdateInstaller
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pobiera plik APK nowej wersji z GitHub Releases (Klap3K/Elektronv2) i otwiera systemowy
 * instalator pakietów w celu aktualizacji aplikacji bez odinstalowywania poprzedniej wersji.
 */
@Singleton
class ApkUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    client: OkHttpClient
) : UpdateInstaller {

    override val supported: Boolean = true

    private val http: OkHttpClient = OkHttpClientProvider.createDownloadClient(client, BuildConfig.VERSION_NAME)

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    override val state: StateFlow<InstallState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var ready: Pair<File, String>? = null

    private val dir: File get() = File(context.cacheDir, "updates")

    override fun start(update: AppUpdate) {
        val url = update.apkUrl ?: run {
            _state.value = InstallState.Failed("To wydanie nie ma pliku APK.")
            return
        }
        if (job?.isActive == true) return
        ready?.let { (file, version) ->
            if (version == update.versionName && file.exists()) { install(); return }
        }
        job = scope.launch {
            _state.value = InstallState.Downloading(update.versionName, if (update.sizeBytes > 0) 0f else null)
            try {
                val file = download(update, url)
                ready = file to update.versionName
                install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Aktualizacja nie powiodła się", e)
                _state.value = InstallState.Failed(
                    (e as? UpdateException)?.message
                        ?: "Nie udało się pobrać aktualizacji. Sprawdź połączenie z internetem i wolne miejsce w telefonie."
                )
                runCatching { dir.listFiles()?.forEach { it.delete() } }
            }
        }
    }

    private suspend fun download(update: AppUpdate, url: String): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "download.part")
        val target = File(dir, "Elektron-Vulcan-${update.versionName.replace(Regex("[^0-9A-Za-z.-]"), "")}.apk")
        val digest = MessageDigest.getInstance("SHA-256")

        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw UpdateException("Serwer GitHuba odpowiedział błędem (HTTP ${resp.code}).")
            val body = resp.body ?: throw UpdateException("Pusta odpowiedź serwera.")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes.takeIf { it > 0 }
            var done = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = InstallState.Downloading(update.versionName, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (update.sha256 != null && !sha.equals(update.sha256, ignoreCase = true)) {
            part.delete()
            throw UpdateException("Pobrany plik jest uszkodzony (inna suma SHA-256). Spróbuj ponownie.")
        }
        val info = context.packageManager.getPackageArchiveInfo(part.path, 0)
        if (info == null || info.packageName != context.packageName) {
            part.delete()
            throw UpdateException("Pobrany plik nie jest aplikacją Elektron-Vulcan.")
        }
        if (PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE) {
            part.delete()
            throw UpdateException("Pobrany plik nie zawiera nowszej wersji.")
        }
        if (!part.renameTo(target)) throw UpdateException("Nie udało się zapisać pliku.")
        return target
    }

    override fun install() {
        val (file, version) = ready ?: return
        if (!file.exists()) {
            ready = null
            _state.value = InstallState.Failed("Pobrany plik zniknął - pobierz aktualizację ponownie.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = InstallState.NeedsPermission(version)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.updates", file) }
            .getOrElse {
                Log.w(TAG, "FileProvider niedostępny", it)
                _state.value = InstallState.Failed("Nie udało się otworzyć instalatora.")
                return
            }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        _state.value = InstallState.ReadyToInstall(version)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Nie udało się otworzyć instalatora", it) }
    }

    override fun resumeIfPermitted() {
        val st = _state.value
        if (st is InstallState.NeedsPermission &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())
        ) install()
    }

    private class UpdateException(message: String) : IOException(message)

    private companion object {
        const val TAG = "ApkUpdateInstaller"
    }
}
