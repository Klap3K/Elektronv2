package pl.zse.bydgoszcz.elektron.presentation.settings

import androidx.compose.foundation.selection.toggleable

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.width
import pl.zse.bydgoszcz.elektron.crash.openContactEmail
import pl.zse.bydgoszcz.elektron.crash.ReportDialog
import pl.zse.bydgoszcz.elektron.presentation.common.revealWhen
import androidx.compose.foundation.layout.Box
import pl.zse.bydgoszcz.elektron.presentation.common.UpdateActions
import pl.zse.bydgoszcz.elektron.crash.CrashReport
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.DisposableEffect
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.zse.bydgoszcz.elektron.BuildConfig
import pl.zse.bydgoszcz.elektron.R
import pl.zse.bydgoszcz.elektron.domain.repository.ThemeMode
import pl.zse.bydgoszcz.elektron.presentation.common.GroupedSection
import pl.zse.bydgoszcz.elektron.presentation.common.LargeTitleBar
import pl.zse.bydgoszcz.elektron.presentation.common.SafeUrls
import pl.zse.bydgoszcz.elektron.presentation.common.pressable
import pl.zse.bydgoszcz.elektron.domain.model.UpdateChannel
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate

private const val REPO_URL = "https://github.com/Klap3K/Elektronv2"
private const val PRIVACY_URL = "https://github.com/Klap3K/Elektronv2/blob/main/PRYWATNOSC.md"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenGroups: () -> Unit = {},
    onOpenSubjects: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    transitViewModel: pl.zse.bydgoszcz.elektron.presentation.transit.TransitViewModel = hiltViewModel(),
    calendarViewModel: pl.zse.bydgoszcz.elektron.presentation.calendar.CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transitSettings by transitViewModel.settings.collectAsStateWithLifecycle()
    val transitSaving by transitViewModel.saving.collectAsStateWithLifecycle()
    val transitError by transitViewModel.settingsError.collectAsStateWithLifecycle()
    var transitPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    if (transitPicker != 0) pl.zse.bydgoszcz.elektron.presentation.transit.StopPicker(transitViewModel, origin = transitPicker == 2) { transitPicker = 0 }
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    // Tryb dewelopera.
    val devMode by viewModel.devMode.collectAsStateWithLifecycle()
    val notesViewModel: pl.zse.bydgoszcz.elektron.presentation.timetable.LessonNotesViewModel = hiltViewModel()
    val notesState by notesViewModel.state.collectAsStateWithLifecycle()
    var showNotes by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (showNotes) pl.zse.bydgoszcz.elektron.presentation.timetable.LessonNotesLibrary(
        notesState.notes.filter { it.classId == state.selectedClassId }, notesViewModel) { showNotes = false }
    val noteError by notesViewModel.settingsError.collectAsStateWithLifecycle()
    val settingError by viewModel.settingError.collectAsStateWithLifecycle()
    val devMessage by viewModel.devMessage.collectAsStateWithLifecycle()
    val vulcanAccount by calendarViewModel.vulcanAccount.collectAsStateWithLifecycle()
    val isRegisteringVulcan by calendarViewModel.isRegistering.collectAsStateWithLifecycle()
    val calendarUrl by calendarViewModel.calendarUrl.collectAsStateWithLifecycle()
    val isSyncingCalendar by calendarViewModel.isSyncing.collectAsStateWithLifecycle()
    val calendarLastSync by calendarViewModel.lastSyncEpochSeconds.collectAsStateWithLifecycle()
    val calendarError by calendarViewModel.lastSyncError.collectAsStateWithLifecycle()
    val calendarMessage by calendarViewModel.userMessage.collectAsStateWithLifecycle()
    val toastContext = LocalContext.current
    var toast by remember { mutableStateOf<Toast?>(null) }
    val showToast: (String) -> Unit = { text ->
        toast?.cancel()
        toast = Toast.makeText(toastContext, text, Toast.LENGTH_SHORT).also { it.show() }
    }
    LaunchedEffect(settingError) { settingError?.let { showToast(it); viewModel.settingError.value = null } }
    LaunchedEffect(noteError) { noteError?.let { showToast(it); notesViewModel.settingsError.value = null } }
    LaunchedEffect(devMessage) {
        devMessage?.let { showToast(it); viewModel.consumeDevMessage() }
    }
    LaunchedEffect(calendarMessage) {
        calendarMessage?.let { showToast(it); calendarViewModel.consumeUserMessage() }
    }
    var logoTaps by remember { mutableIntStateOf(0) }
    var lastLogoTap by remember { mutableLongStateOf(0L) }
    val onLogoTap: () -> Unit = {
        val t = SystemClock.uptimeMillis()
        logoTaps = if (t - lastLogoTap < 700) logoTaps + 1 else 1
        lastLogoTap = t
        if (!devMode && logoTaps >= 5) {
            viewModel.setDevMode(true)
            logoTaps = 0
            showToast("Tryb dewelopera włączony.")
        }
    }
    var confirmCrash by remember { mutableStateOf(false) }
    if (confirmCrash) {
        AlertDialog(
            onDismissRequest = { confirmCrash = false },
            title = { Text("Symulować awarię?") },
            text = { Text("Aplikacja zamknie się z błędem. Po ponownym uruchomieniu pojawi się okno raportu (kopiowanie logów, e-mail, GitHub).") },
            confirmButton = { TextButton(onClick = { confirmCrash = false; viewModel.devCrash() }) { Text("Zamknij z błędem") } },
            dismissButton = { TextButton(onClick = { confirmCrash = false }) { Text("Anuluj") } }
        )
    }

    val feedbackReport by viewModel.feedbackReport.collectAsStateWithLifecycle()
    feedbackReport?.let { report ->
        ReportDialog(
            title = "Zgłoś problem",
            intro = "Opisz w wiadomości, co nie działa. Raport techniczny poniżej zostanie dołączony.",
            report = report,
            issueUrl = CrashReport.feedbackUrl(
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR,
                Build.VERSION.RELEASE ?: "?", Build.VERSION.SDK_INT, "${Build.MANUFACTURER} ${Build.MODEL}"
            ),
            onDismiss = viewModel::closeFeedback
        )
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val ctx = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LargeTitleBar(title = "Ustawienia", scrollBehavior = scrollBehavior) }
    ) { padding ->
        // Kolejność: kim jesteś (klasa), co i kiedy dostajesz (powiadomienia, przypomnienia),
        // co widzisz (strona główna, Odjazdy, wygląd, plan, pasek, widżety), a na końcu sprawy
        // techniczne (praca w tle, aktualizacje, dane, informacje).
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).revealWhen(state.ready),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            item(key = "class") { ClassCard(state.classes, state.selectedClassId, onSelect = viewModel::setClass, onOpenGroups = onOpenGroups) }

            item(key = "notifications") {
                NotificationsSection(state.notifSubs, state.notifAnn, state.quiet,
                    onSubs = viewModel::setNotifSubs, onAnnouncements = viewModel::setNotifAnn, onQuiet = viewModel::setQuietHours)
            }

            item(key = "reminders") {
                val reminderStatus by viewModel.reminderStatus.collectAsStateWithLifecycle()
                RemindersSection(state.reminder, reminderStatus, viewModel::refreshReminderStatus) { viewModel.setReminder(it) }
            }

            item(key = "notes") {
                NoteRemindersSection(notesState.enabled, notesState.ready && !notesState.error, notesState.timing, notesViewModel::setReminderTiming, notesViewModel::refreshReminders, notesViewModel::setReminders) { showNotes = true }
            }

            item(key = "calendar") {
                CalendarSection(
                    vulcanAccount = vulcanAccount,
                    isRegistering = isRegisteringVulcan,
                    calendarUrl = calendarUrl,
                    isSyncing = isSyncingCalendar,
                    lastSyncEpochSeconds = calendarLastSync,
                    lastSyncError = calendarError,
                    onConnectEduVulcan = calendarViewModel::connectEduVulcanSession,
                    onDisconnectVulcan = calendarViewModel::clearVulcanAccount,
                    onSetUrl = calendarViewModel::setCalendarUrl,
                    onSync = calendarViewModel::sync,
                    onClear = calendarViewModel::clearCalendar
                )
            }

            item(key = "home") {
                HomeSection(state.startScreen, transitTab = transitSettings.preferences.visible, onChange = viewModel::setStartScreen) {
                    SwitchRow("Następna lekcja", state.showNextLesson) { viewModel.setShowNextLesson(it) }
                    RowDivider()
                    SwitchRow("Zastępstwa", state.showSubstitutions) { viewModel.setShowSubstitutions(it) }
                    RowDivider()
                    SwitchRow("Najnowsze ogłoszenia", state.showAnnouncements) { viewModel.setShowAnnouncements(it) }
                }
            }

            item(key = "transit") {
                TransitSection(transitSettings, transitSaving, transitError,
                    onTab = { transitViewModel.setTabVisible("transit", it) },
                    onCard = transitViewModel::setShowOnDashboard,
                    onTransfers = transitViewModel::setAllowTransfers,
                    onPick = { origin -> transitViewModel.query.value = ""; transitPicker = if (origin) 2 else 1 })
            }

            item(key = "look") {
                GroupedSection("Wygląd", icon = Icons.Outlined.Palette) {
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        val options = listOf(ThemeMode.SYSTEM to "Auto", ThemeMode.LIGHT to "Jasny", ThemeMode.DARK to "Ciemny")
                        options.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = state.themeMode == mode,
                                onClick = { viewModel.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                icon = {}
                            ) { Text(label) }
                        }
                    }
                    // Kolory akcentu zawsze widoczne. Przy kolorach z tapety żaden nie jest zaznaczony,
                    // a wybór koloru wyłącza kolory z tapety (dawniej wybór się chował).
                    RowDivider()
                    val wallpaper = state.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    AccentPicker(state.accent, wallpaperActive = wallpaper) {
                        viewModel.setAccent(it)
                        if (wallpaper) viewModel.setDynamicColor(false)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RowDivider()
                        SwitchRow("Kolory z tapety", state.dynamicColor, supporting = "Material You zamiast koloru akcentu") { viewModel.setDynamicColor(it) }
                    }
                    RowDivider()
                    AppIconRow()
                }
            }

            item(key = "plan") {
                val look = state.look
                GroupedSection("Plan lekcji", icon = Icons.Outlined.CalendarMonth) {
                    ActionRow("Nazwy i kolory przedmiotów", trailingChevron = true, onClick = onOpenSubjects)
                    RowDivider()
                    SwitchRow("Pokazuj salę", look.showRoom) { viewModel.setTimetableLook(look.copy(showRoom = it)) }
                    RowDivider()
                    SwitchRow("Pokazuj nauczyciela", look.showTeacher) { viewModel.setTimetableLook(look.copy(showTeacher = it)) }
                }
            }

            item(key = "bar") { pl.zse.bydgoszcz.elektron.presentation.transit.TransitVisibilitySection(transitViewModel) }

            item(key = "widgets") { WidgetsSection(state.widgetLook) { viewModel.setWidgetLook(it) } }

            item(key = "background") { BackgroundSection() }

            // Aktualizacje (GitHub Releases); nie w wersji F-Droid.
            if (BuildConfig.UPDATE_CHECK) {
                item(key = "updates") {
                    var choosingChannel by remember { mutableStateOf(false) }
                    GroupedSection("Aktualizacje", icon = Icons.Outlined.SystemUpdate,
                        footer = if (state.updateChannel == UpdateChannel.BETA)
                            "Wersje beta są testowe i mogą zawierać błędy. Gdy ukaże się wersja stabilna, też ją dostaniesz."
                        else null) {
                        ValueRow("Kanał", state.updateChannel.label) { choosingChannel = true }
                        RowDivider()
                        ActionRow("Sprawdź aktualizacje", enabled = updateStatus != SettingsViewModel.UpdateStatus.Checking) { viewModel.checkForUpdates() }
                        when (val st = updateStatus) {
                            SettingsViewModel.UpdateStatus.Idle -> Unit
                            SettingsViewModel.UpdateStatus.Checking -> UpdateNote("Sprawdzam…")
                            SettingsViewModel.UpdateStatus.UpToDate -> UpdateNote("Masz najnowszą wersję (${BuildConfig.VERSION_NAME}).")
                            SettingsViewModel.UpdateStatus.Failed -> UpdateNote("Nie udało się sprawdzić - brak połączenia z GitHubem.")
                            is SettingsViewModel.UpdateStatus.Available -> {
                                UpdateNote("Dostępna wersja ${st.update.versionName}.")
                                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                    UpdateActions(st.update, onLater = null)
                                }
                            }
                        }
                    }
                    if (choosingChannel) {
                        ChoiceDialog("Kanał aktualizacji",
                            listOf(UpdateChannel.STABLE to "Stabilne (zalecane)", UpdateChannel.BETA to "Beta - wersje testowe i stabilne"),
                            state.updateChannel,
                            onSelect = { viewModel.setUpdateChannel(it); choosingChannel = false },
                            onDismiss = { choosingChannel = false })
                    }
                }
            }

            item(key = "data") {
                var confirmReset by remember { mutableStateOf(false) }
                GroupedSection("Dane", icon = Icons.Outlined.Storage,
                    footer = "Pobiera plan, zastępstwa i ogłoszenia od nowa. Klasa, grupy, notatki i ustawienia zostają.") {
                    ActionRow("Wyczyść dane podręczne", destructive = true) { confirmReset = true }
                }
                if (confirmReset) {
                    AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("Wyczyścić dane podręczne?") },
                        text = { Text("Lokalna kopia planu, zastępstw i ogłoszeń zostanie usunięta i pobrana od nowa ze strony szkoły.") },
                        confirmButton = {
                            TextButton(onClick = { viewModel.resetCache(); confirmReset = false }) {
                                Text("Wyczyść", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Anuluj") } }
                    )
                }
            }

            item(key = "about") {
                GroupedSection("O aplikacji", icon = Icons.Outlined.Info) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onLogoTap
                                )
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Elektron-Vulcan", style = MaterialTheme.typography.titleLarge)
                        Text("Wersja ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(aboutText(linkColor),
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    RowDivider()
                    // Raport z logami: kopiowanie, e-mail do dewelopera albo zgłoszenie na GitHubie.
                    ActionRow("Zgłoś problem", trailingChevron = true) { viewModel.openFeedback() }
                    RowDivider()
                    ActionRow("Napisz do autora", supporting = CrashReport.CONTACT_EMAIL, trailingIcon = true) {
                        if (!openContactEmail(ctx)) SafeUrls.open(ctx, REPO_URL)
                    }
                    RowDivider()
                    ActionRow("Kod źródłowy na GitHubie", trailingIcon = true) {
                        SafeUrls.open(ctx, REPO_URL)
                    }
                    RowDivider()
                    ActionRow("Polityka prywatności", trailingIcon = true) {
                        SafeUrls.open(ctx, PRIVACY_URL)
                    }
                }
            }

            if (devMode) {
                item(key = "dev") {
                    val devDialog by viewModel.devDialog.collectAsStateWithLifecycle()
                    DevSimulationDialogs(devDialog,
                        onSubstitution = viewModel::devSimulateSubstitution,
                        onAnnouncement = viewModel::devSimulateAnnouncement,
                        onCustomSubstitution = { viewModel.devDialog.value = SettingsViewModel.DevDialog.CustomSubstitution },
                        onCustomAnnouncement = { viewModel.devDialog.value = SettingsViewModel.DevDialog.CustomAnnouncement },
                        onDismiss = { viewModel.devDialog.value = null })
                    val offset by viewModel.simulatedOffset.collectAsStateWithLifecycle()
                    GroupedSection(
                        "Tryb dewelopera",
                        icon = Icons.Outlined.Code,
                        footer = "Symulacje są widoczne tylko na tym telefonie. Zastępstwo (własne albo wzorowane na zapisanym, " +
                            "także minionym) trafia na najbliższą lekcję - do planu, zastępstw, strony głównej i widżetów - " +
                            "i wysyła powiadomienie; tak samo ogłoszenie. Znikają po „Usuń symulacje” albo przy synchronizacji. " +
                            "Symulowany czas dalej płynie; synchronizacja, przypomnienia i powiadomienia zostają w prawdziwym czasie."
                    ) {
                        DevTimeRows(offset, onSimulate = viewModel::simulateTime, onReset = viewModel::resetSimulatedTime)
                        RowDivider()
                        ActionRow("Symuluj zastępstwo…", trailingChevron = true) { viewModel.devOpenSubstitution() }
                        RowDivider()
                        ActionRow("Symuluj ogłoszenie…", trailingChevron = true) { viewModel.devOpenAnnouncement() }
                        RowDivider()
                        ActionRow("Usuń symulacje") { viewModel.devClearSimulations() }
                        RowDivider()
                        ActionRow("Odśwież widżety") { viewModel.devRefreshWidgets() }
                        RowDivider()
                        ActionRow("Raport błędu (bez awarii)") { viewModel.openFeedback() }
                        RowDivider()
                        ActionRow("Symuluj awarię aplikacji", destructive = true) { confirmCrash = true }
                        RowDivider()
                        ActionRow("Wyłącz tryb dewelopera") { viewModel.setDevMode(false) }
                    }
                }
            }

            // Licencja: plakietka GPLv3 (dotknięcie otwiera tekst licencji).
            item(key = "license") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.gpl_v3),
                        contentDescription = "Licencja GNU GPL w wersji 3 lub nowszej - otwórz tekst licencji",
                        modifier = Modifier.width(112.dp).pressable { SafeUrls.open(ctx, LICENSE_URL) }
                    )
                }
            }
        }
    }
}

private fun aboutText(linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        append("Nieoficjalny odpowiednik aplikacji ")
        link("Atom", "https://github.com/kacpergorka/atom", linkColor)
        append(" autorstwa ")
        link("Kacpra Górki", "https://github.com/kacpergorka", linkColor)
        append(", przeznaczony na Androida. Projekt niezależny, niepowiązany z ZSE w Bydgoszczy ani z firmą VULCAN. ")
        append("Kod aplikacji napisało bezduszne AI, głównie Claude (Anthropic) i ChatGPT (OpenAI).")
    }

private fun AnnotatedString.Builder.link(text: String, url: String, color: Color) {
    val start = length
    append(text)
    addStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline), start, length)
    addLink(LinkAnnotation.Url(url), start, length)
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Etykieta wiersza z opcjonalnym opisem pod spodem (zamiast długich stopek pod sekcją). */
@Composable
private fun RowLabel(label: String, supporting: String?, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(Modifier.toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange))
            // Obszar dotyku co najmniej 52 dp (dawniej ok. 44 dp: 32 dp przełącznika + marginesy).
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        RowLabel(label, supporting,
            if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.weight(1f).padding(end = 12.dp))
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent,
                // outline, nie outlineVariant: wyłączony przełącznik był prawie niewidoczny na białej karcie.
                uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                uncheckedThumbColor = Color.White
            ),
            thumbContent = null
        )
    }
}

/**
 * Wiersz-akcja. Przejście dalej (strzałka) ma zwykły kolor tekstu, czynność - kolor akcentu,
 * nieodwracalna - czerwony; link do strony - ikona otwarcia na końcu.
 */
@Composable
internal fun ActionRow(
    label: String,
    destructive: Boolean = false,
    trailingIcon: Boolean = false,
    trailingChevron: Boolean = false,
    supporting: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().pressable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = when {
            !enabled -> colors.onSurfaceVariant
            destructive -> colors.error
            trailingChevron || trailingIcon -> colors.onSurface
            else -> colors.primary
        }
        RowLabel(label, supporting, color, Modifier.weight(1f).padding(end = 8.dp))
        if (trailing != null) {
            trailing()
        } else if (trailingIcon) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        } else if (trailingChevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun UpdateNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
}

@Composable
internal fun SystemNotificationStatus() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!allowed) {
        Text("Android blokuje powiadomienia eLektrona. Zmiana opcji poniżej nie odblokuje ich w systemie.",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        // Z zapasowym ekranem aplikacji - bez awarii na systemach bez ustawień powiadomień.
        ActionRow("Otwórz ustawienia powiadomień", trailingIcon = true) { openNotificationSettings(context) }
        RowDivider()
    }
}
