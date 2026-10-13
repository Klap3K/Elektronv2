package pl.zse.bydgoszcz.elektron.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Nowa wersja aplikacji dostępna na GitHubie. [pageUrl] - strona wydania, [apkUrl] - plik APK
 * z sekcji Assets (null - wydanie bez APK: zostaje otwarcie strony), [sha256] - suma pliku
 * podawana przez GitHuba (null - starsze wydania bez sumy).
 */
data class AppUpdate(
    val versionName: String,
    val pageUrl: String,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long = 0,
    /** Wydanie oznaczone na GitHubie jako Pre-release (także bez dopisku w numerze). */
    val prerelease: Boolean = false
)

interface UpdateRepository {
    /** Nowsza wersja niż zainstalowana (i nieodłożona przez użytkownika), inaczej null. */
    val availableUpdate: Flow<AppUpdate?>

    /**
     * Sprawdza GitHub Releases. Bez [force] najwyżej co 12 godzin.
     * Wynik: nowsza wersja albo null (masz najnowszą).
     */
    suspend fun check(force: Boolean = false): Result<AppUpdate?>

    /** "Później" - nie pokazuj banera tej wersji przez kilka dni. */
    suspend fun dismiss(versionName: String)
}

/** Stan pobierania i instalacji aktualizacji w aplikacji. */
sealed interface InstallState {
    data object Idle : InstallState
    /** [progress] 0..1 albo null, gdy serwer nie podał rozmiaru. */
    data class Downloading(val versionName: String, val progress: Float?) : InstallState
    /** Android wymaga zgody na instalowanie aplikacji z eLektronu - otwarto ustawienia. */
    data class NeedsPermission(val versionName: String) : InstallState
    /** Pobrano i sprawdzono - otwarto systemowy instalator. */
    data class ReadyToInstall(val versionName: String) : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Pobieranie i instalacja APK w obu wariantach dystrybucji GitHub (github/gms).
 */
interface UpdateInstaller {
    val supported: Boolean
    val state: StateFlow<InstallState>

    /** Pobiera APK, sprawdza SHA-256 i otwiera instalator. */
    fun start(update: AppUpdate)

    /** Otwiera instalator dla pobranego pliku ("Zainstaluj"). */
    fun install()

    /** Po powrocie z ustawień zgody na instalowanie - dokończ, jeśli plik jest gotowy. */
    fun resumeIfPermitted()
}
