package pl.zse.bydgoszcz.elektron

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import pl.zse.bydgoszcz.elektron.presentation.common.Personalization
import pl.zse.bydgoszcz.elektron.presentation.common.DelayedLoading
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository
import javax.inject.Inject
import pl.zse.bydgoszcz.elektron.presentation.common.LocalPersonalization
import androidx.compose.runtime.CompositionLocalProvider
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pl.zse.bydgoszcz.elektron.crash.CrashReporter
import pl.zse.bydgoszcz.elektron.crash.CrashReportDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import pl.zse.bydgoszcz.elektron.presentation.common.NewUpdateDialog
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.runtime.DisposableEffect
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import pl.zse.bydgoszcz.elektron.domain.repository.ThemeMode
import pl.zse.bydgoszcz.elektron.presentation.common.ChangelogDialog
import pl.zse.bydgoszcz.elektron.presentation.common.ElektronAppViewModel
import pl.zse.bydgoszcz.elektron.presentation.common.theme.ElektronTheme
import pl.zse.bydgoszcz.elektron.presentation.common.SafeUrls
import pl.zse.bydgoszcz.elektron.presentation.groups.GroupsScreen
import pl.zse.bydgoszcz.elektron.presentation.navigation.ElektronNavHost
import pl.zse.bydgoszcz.elektron.presentation.setup.SetupScreen
import pl.zse.bydgoszcz.elektron.work.LocalNotificationSink
import pl.zse.bydgoszcz.elektron.domain.model.LessonLinks
import java.time.LocalDate

@AndroidEntryPoint
class ElektronActivity : ComponentActivity() {

    @Inject lateinit var updateRepo: UpdateRepository
    @Inject lateinit var coordinator: pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator

    private val appViewModel: ElektronAppViewModel by viewModels()
    private val pendingDeepLink = MutableStateFlow<String?>(null)

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Tylko przy pierwszym utworzeniu Activity. Przy odtworzeniu (np. obrót ekranu)
        // intent jest ten sam — dawniej deep link wykonywał się ponownie (dla linku http:
        // ponowne otwarcie przeglądarki), a prośba o uprawnienie pojawiała się od nowa.
        Log.i(TAG, "onCreate (odtworzenie: ${savedInstanceState != null})")
        if (savedInstanceState == null) {
            pendingDeepLink.value = resolveDeepLink(intent)
        }

        setContent {
            val state by appViewModel.state.collectAsStateWithLifecycle()
            val permissionPrefs = remember { getSharedPreferences("notification_permission", MODE_PRIVATE) }
            var explainNotifications by remember { mutableStateOf(false) }
            LaunchedEffect(state.isReady, state.selectedClassId, state.groupsConfiguredFor) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.isReady &&
                    state.selectedClassId != null && state.groupsConfiguredFor == state.selectedClassId &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    !permissionPrefs.getBoolean("explained", false)) explainNotifications = true
            }
            val operationError by coordinator.operationError.collectAsStateWithLifecycle()
            val deepLink by pendingDeepLink.collectAsStateWithLifecycle()
            val personalization by appViewModel.personalization.collectAsStateWithLifecycle()
            // Raport z poprzedniej awarii (jeśli była) - pokazywany raz, potem usuwany.
            var crashReport by remember { mutableStateOf(CrashReporter.pending(this@ElektronActivity)) }
            val dark = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(Unit) { appViewModel.ensureLastSeenInitialized() }
            // Kolor ikon pasków systemowych wg motywu APLIKACJI, nie systemu. Dawniej przy
            // ciemnym motywie systemu i jasnym w appce ikony zegara/baterii były białe na
            // jasnym tle (niewidoczne) — i odwrotnie.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
                onDispose { }
            }

            ElektronTheme(darkTheme = dark, dynamicColor = state.dynamicColor, accent = state.accent) {
                CompositionLocalProvider(LocalPersonalization provides (personalization ?: Personalization())) {
                    // Po kolei: najpierw raport awarii i "Co nowego", dopiero potem prośba
                    // o powiadomienia (dawniej dwa okna naraz po aktualizacji).
                    if (explainNotifications && crashReport == null && state.changelogToShow.isEmpty()) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { explainNotifications = false; permissionPrefs.edit().putBoolean("explained", true).apply() },
                        title = { androidx.compose.material3.Text("Powiadomienia ze szkoły") },
                        text = { androidx.compose.material3.Text("Możesz otrzymywać powiadomienia o nowych zastępstwach. Powiadomienia o ogłoszeniach oraz przypomnienia o lekcjach i notatkach są domyślnie wyłączone. Możesz je włączyć osobno w ustawieniach.") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = {
                            explainNotifications = false; permissionPrefs.edit().putBoolean("explained", true).apply()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { androidx.compose.material3.Text("Zezwól") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = {
                            explainNotifications = false; permissionPrefs.edit().putBoolean("explained", true).apply()
                        }) { androidx.compose.material3.Text("Później") } }
                    )
                    when {
                        // Start: kółko dopiero, gdy wczytywanie trwa dłużej (zwykle to ułamek sekundy).
                        !state.isReady || personalization == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DelayedLoading { CircularProgressIndicator() }
                        }
                        state.selectedClassId == null -> SetupScreen()
                        // Po wyborze klasy (w Setup albo w Ustawieniach) — krok wyboru grup.
                        state.groupsConfiguredFor != state.selectedClassId -> GroupsScreen(asSetupStep = true)
                        else -> ElektronNavHost(
                            deepLink = deepLink,
                            onDeepLinkConsumed = { pendingDeepLink.value = null },
                            startRoute = state.startRoute
                        )
                    }

                    operationError?.let { message ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = coordinator::consumeOperationError,
                            title = { androidx.compose.material3.Text("Zmiana nie została zakończona") },
                            text = { androidx.compose.material3.Text(message) },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = coordinator::consumeOperationError) {
                                androidx.compose.material3.Text("OK")
                            } }
                        )
                    }
                    crashReport?.let { report ->
                        CrashReportDialog(report = report, onDismiss = {
                            CrashReporter.clear(this@ElektronActivity)
                            crashReport = null
                        })
                    }

                    val availableUpdate by updateRepo.availableUpdate.collectAsStateWithLifecycle(null)
                    var dismissedUpdateSession by rememberSaveable { mutableStateOf<String?>(null) }

                    if (crashReport == null && state.changelogToShow.isNotEmpty()) {
                        ChangelogDialog(
                            entries = state.changelogToShow,
                            onDismiss = { appViewModel.markChangelogSeen() }
                        )
                    } else if (crashReport == null && availableUpdate != null && availableUpdate?.versionName != dismissedUpdateSession) {
                        NewUpdateDialog(
                            update = availableUpdate!!,
                            onDismiss = {
                                val ver = availableUpdate?.versionName
                                dismissedUpdateSession = ver
                                if (ver != null) {
                                    lifecycleScope.launch { updateRepo.dismiss(ver) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Nowa wersja na GitHubie: sprawdzanie w tle przy każdym uruchomieniu i powrocie do aplikacji
     * (co min. 5 minut dzięki cache i ETag w UpdateRepository).
     */
    @javax.inject.Inject lateinit var noteReminders: pl.zse.bydgoszcz.elektron.work.NoteReminderScheduler
    @javax.inject.Inject lateinit var liveBellScheduler: pl.zse.bydgoszcz.elektron.service.LiveBellScheduler

    override fun onStart() {
        super.onStart()
        noteReminders.requestReschedule(force = true)
        liveBellScheduler.requestReschedule()
        if (BuildConfig.UPDATE_CHECK) lifecycleScope.launch { updateRepo.check(force = false) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent")
        pendingDeepLink.value = resolveDeepLink(intent)
    }

    private fun resolveDeepLink(intent: Intent?): String? {
        val link = resolveDeepLinkInner(intent)
        Log.i(TAG, "Wynik: ${link ?: "brak deep linku (zwykłe otwarcie)"}")
        return link
    }

    private fun resolveDeepLinkInner(intent: Intent?): String? {
        intent ?: return null
        // Diagnostyka (raport "Zgłoś problem"): akcja, data i same NAZWY kluczy extras - bez wartości.
        val data = intent.dataString
        Log.i(TAG, "Intencja: action=${intent.action}, data=${data?.takeIf { it.startsWith("elektron://") } ?: data?.let { "(inny URI)" }}, " +
            "extras=${runCatching { intent.extras?.keySet()?.sorted() }.getOrNull() ?: "[]"}")
        val fromNotif = intent.getStringExtra(LocalNotificationSink.EXTRA_DEEP_LINK)
        if (!fromNotif.isNullOrBlank()) {
            if (fromNotif.startsWith("http://") || fromNotif.startsWith("https://")) {
                // ElektronActivity jest eksportowana — link z intentu mógł podać dowolna aplikacja.
                // Otwieramy tylko strony szkoły (dawniej: każdy adres).
                if (SafeUrls.isSchoolUrl(fromNotif)) SafeUrls.open(this, fromNotif)
                return null
            }
            return fromNotif
        }
        // Wiersz widżetu Zastępstwa: szczegóły lekcji z extras albo z data URI (błędne dane -
        // zwykłe otwarcie aplikacji).
        val lessonExtra = intent.getStringExtra(LessonLinks.EXTRA_LESSON)
        if (lessonExtra != null || data?.startsWith("elektron://") == true) {
            val r = LessonLinks.resolveIntent(lessonExtra, data, AppClock.today())
            Log.i(TAG, "Lekcja: extra=$lessonExtra, źródło=${r.source}, ${r.reason}")
            r.target?.let { return LessonLinks.deepLink(it) }
        }
        return when (intent.getStringExtra("elektron_shortcut")) {
            "substitutions" -> "substitutions"
            "announcements" -> "announcements"
            "timetable" -> "timetable"
            "transit" -> "transit" // skrót tylko z włączoną zakładką Odjazdy
            "dashboard" -> "dashboard" // widżety i skrót
            else -> null
        }
    }

    private companion object {
        const val TAG = "ElektronActivity"
    }
}
