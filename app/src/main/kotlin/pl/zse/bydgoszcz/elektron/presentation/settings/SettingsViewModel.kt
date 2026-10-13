package pl.zse.bydgoszcz.elektron.presentation.settings

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import pl.zse.bydgoszcz.elektron.domain.model.SyncErrors
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import pl.zse.bydgoszcz.elektron.work.DeveloperTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import pl.zse.bydgoszcz.elektron.crash.Diagnostics
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import pl.zse.bydgoszcz.elektron.domain.model.AccentSetting
import pl.zse.bydgoszcz.elektron.work.LessonReminderScheduler
import pl.zse.bydgoszcz.elektron.domain.model.WidgetLook
import pl.zse.bydgoszcz.elektron.domain.model.QuietHours
import pl.zse.bydgoszcz.elektron.domain.model.ReminderSettings
import pl.zse.bydgoszcz.elektron.domain.model.StartScreen
import pl.zse.bydgoszcz.elektron.domain.model.TimetableLook
import pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate
import pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository
import pl.zse.bydgoszcz.elektron.domain.model.UpdateChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.domain.model.SchoolClass
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.ThemeMode
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import pl.zse.bydgoszcz.elektron.domain.usecase.ClassSelection
import pl.zse.bydgoszcz.elektron.widget.WidgetUpdater
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val timetableRepo: TimetableRepository,
    private val classSelection: ClassSelection,
    private val updateRepo: UpdateRepository,
    private val widgetUpdater: WidgetUpdater,
    private val reminders: LessonReminderScheduler,
    private val notificationsRepo: NotificationsRepository,
    private val developerTools: DeveloperTools,
    private val dndScheduler: pl.zse.bydgoszcz.elektron.service.AutoDndScheduler,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val settingError = MutableStateFlow<String?>(null)
    private fun writeSetting(block: suspend () -> Unit) = viewModelScope.launch {
        runCatchingCancellable { block() }.onFailure {
            settingError.value = "Nie udało się zapisać ustawienia. Poprzedni wybór pozostał."
        }
    }

    val reminderStatus = reminders.status
    fun refreshReminderStatus() { reminders.requestReschedule() }

    /**
     * Tryb dewelopera - na czas działania aplikacji, nie zapisywany. Panel z symulacjami w Ustawieniach.
     * Trwająca symulacja czasu (zapisana, przetrwa restart) od razu włącza panel - inaczej zmieniony
     * czas nie byłby nigdzie widoczny, a "Wróć do czasu telefonu" schowane.
     */
    private val _devMode = MutableStateFlow(pl.zse.bydgoszcz.elektron.domain.util.AppClock.simulated)
    val devMode: StateFlow<Boolean> = _devMode

    /** Komunikat po akcji trybu dewelopera (pokazywany raz). */
    private val _devMessage = MutableStateFlow<String?>(null)
    val devMessage: StateFlow<String?> = _devMessage

    fun setDevMode(enabled: Boolean) {
        _devMode.value = enabled
        // Wyłączenie trybu dewelopera kończy też symulację czasu.
        if (!enabled && pl.zse.bydgoszcz.elektron.domain.util.AppClock.simulated) resetSimulatedTime()
    }

    /** Symulowany czas (tryb dewelopera): plan, zastępstwa, strona główna, Odjazdy i widżety. */
    val simulatedOffset = pl.zse.bydgoszcz.elektron.domain.util.AppClock.offsetMs

    fun simulateTime(at: java.time.LocalDateTime) {
        pl.zse.bydgoszcz.elektron.domain.util.AppClock.simulate(at)
        _devMessage.value = "Symulowany czas: ${at.format(java.time.format.DateTimeFormatter.ofPattern("d.MM.yyyy, HH:mm"))}"
    }

    /** Okno symulacji w trybie dewelopera (null - zamknięte). */
    sealed interface DevDialog {
        /** Wybór wzoru zastępstwa: własne, zwolnienie albo zapisane (także minione). */
        data class Substitution(val past: List<Pair<DeveloperTools.SubstitutionDraft, String>>) : DevDialog
        data object CustomSubstitution : DevDialog
        /** Wybór wzoru ogłoszenia: własne albo zapisane (id, tytuł). */
        data class Announcement(val past: List<Pair<String, String>>) : DevDialog
        data object CustomAnnouncement : DevDialog
    }
    val devDialog = MutableStateFlow<DevDialog?>(null)

    fun devOpenSubstitution() = viewModelScope.launch {
        devDialog.value = DevDialog.Substitution(runCatchingCancellable { developerTools.pastSubstitutions() }.getOrDefault(emptyList()))
    }
    fun devOpenAnnouncement() = viewModelScope.launch {
        devDialog.value = DevDialog.Announcement(runCatchingCancellable { developerTools.pastAnnouncements().map { it.id to it.title } }.getOrDefault(emptyList()))
    }
    fun devSimulateSubstitution(draft: DeveloperTools.SubstitutionDraft) {
        devDialog.value = null
        devAction { developerTools.simulateSubstitution(draft) }
    }
    fun devSimulateAnnouncement(title: String, text: String, templateId: String? = null) {
        devDialog.value = null
        devAction { developerTools.simulateAnnouncement(title, text, templateId) }
    }

    fun devRefreshWidgets() {
        widgetUpdater.requestUpdate()
        _devMessage.value = "Widżety odświeżone."
    }

    fun resetSimulatedTime() {
        pl.zse.bydgoszcz.elektron.domain.util.AppClock.reset()
        _devMessage.value = "Przywrócono czas telefonu."
    }

    fun consumeDevMessage() {
        _devMessage.value = null
    }

    fun devClearSimulations() = devAction { developerTools.clearSimulations() }
    fun devCrash() = developerTools.crash()

    private fun devAction(block: suspend () -> String) {
        viewModelScope.launch {
            _devMessage.value = runCatchingCancellable { block() }.getOrElse { "Błąd: ${SyncErrors.userMessage(it)}" }
        }
    }

    /** Raport do "Zgłoś problem" (null - okno zamknięte). */
    private val _feedbackReport = MutableStateFlow<String?>(null)
    val feedbackReport: StateFlow<String?> = _feedbackReport

    /**
     * "Zgłoś problem": raport z logami aplikacji i stanem synchronizacji (najważniejsze przy
     * problemach z pobieraniem danych ze strony szkoły).
     */
    fun openFeedback() {
        viewModelScope.launch {
            val s = state.value
            val className = s.classes.firstOrNull { it.id == s.selectedClassId }?.fullName ?: s.selectedClassId ?: "nie wybrano"
            val lastSync = runCatchingCancellable { notificationsRepo.getLastSyncAt() }.getOrNull()
            val lastError = runCatchingCancellable { notificationsRepo.observeLastSyncError().first() }.getOrNull()
            val details = listOf(
                "Klasa" to className,
                "Ostatnia udana synchronizacja" to (lastSync?.atZone(java.time.ZoneId.systemDefault())
                    ?.toLocalDateTime()?.withNano(0)?.toString()?.replace('T', ' ') ?: "brak"),
                "Ostatni błąd synchronizacji" to (lastError ?: "brak")
            )
            _feedbackReport.value = withContext(Dispatchers.IO) { Diagnostics.feedbackReport(appContext, details) }
        }
    }

    fun closeFeedback() {
        _feedbackReport.value = null
    }

    data class State(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = false,
        val selectedClassId: String? = null,
        val notifSubs: Boolean = true,
        val notifAnn: Boolean = false,
        val showNextLesson: Boolean = true,
        val showSubstitutions: Boolean = true,
        val showAnnouncements: Boolean = true,
        val startScreen: StartScreen = StartScreen.SMART,
        val classes: List<SchoolClass> = emptyList(),
        val look: TimetableLook = TimetableLook(),
        val reminder: ReminderSettings = ReminderSettings(),
        val quiet: QuietHours = QuietHours(),
        val widgetLook: WidgetLook = WidgetLook(),
        val accent: AccentSetting = AccentSetting(),
        val updateChannel: UpdateChannel = UpdateChannel.STABLE,
        val countdownSettings: CountdownSettings = CountdownSettings(),
        val autoDndEnabled: Boolean = false,
        /** Pierwsze dane już są (do tego czasu ekran jest niewidoczny, bez mignięć). */
        val ready: Boolean = false
    )

    val state: StateFlow<State> = combine(
        settings.themeMode, settings.dynamicColor, settings.selectedClassId,
        settings.notificationsSubstitutions, settings.notificationsAnnouncements,
        settings.showNextLesson, settings.showSubstitutions, settings.showAnnouncements,
        settings.startScreen,
        timetableRepo.observeClasses(),
        settings.timetableLook,
        settings.reminderSettings,
        settings.quietHours,
        settings.widgetLook,
        settings.accent,
        settings.updateChannel,
        settings.countdownSettings,
        settings.autoDndEnabled
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        State(
            ready = true,
            themeMode = values[0] as ThemeMode,
            dynamicColor = values[1] as Boolean,
            selectedClassId = values[2] as String?,
            notifSubs = values[3] as Boolean,
            notifAnn = values[4] as Boolean,
            showNextLesson = values[5] as Boolean,
            showSubstitutions = values[6] as Boolean,
            showAnnouncements = values[7] as Boolean,
            startScreen = values[8] as StartScreen,
            classes = values[9] as List<SchoolClass>,
            look = values[10] as TimetableLook,
            reminder = values[11] as ReminderSettings,
            quiet = values[12] as QuietHours,
            widgetLook = values[13] as WidgetLook,
            accent = values[14] as AccentSetting,
            updateChannel = values[15] as UpdateChannel,
            countdownSettings = values[16] as CountdownSettings,
            autoDndEnabled = values[17] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun setTheme(m: ThemeMode) = writeSetting {
        settings.setThemeMode(m)
        widgetUpdater.requestUpdate()   // widżety w motywie aplikacji
    }

    // Ponowne tapnięcie tej samej klasy nic nie robi (dawniej: pełny resync + miganie
    // "Ładowanie"). Sam sync działa w ClassSelection (zasięg aplikacji) — przeżyje
    // przełączenie ekranu na krok wyboru grup.
    fun setClass(id: String) {
        if (id == state.value.selectedClassId) return
        classSelection.select(id)
    }

    /** Stan ręcznego sprawdzania aktualizacji ("O aplikacji"). */
    sealed interface UpdateStatus {
        data object Idle : UpdateStatus
        data object Checking : UpdateStatus
        data object UpToDate : UpdateStatus
        data class Available(val update: AppUpdate) : UpdateStatus
        data object Failed : UpdateStatus
    }

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus

    fun checkForUpdates() {
        if (_updateStatus.value == UpdateStatus.Checking) return
        _updateStatus.value = UpdateStatus.Checking
        viewModelScope.launch {
            _updateStatus.value = updateRepo.check(force = true).fold(
                onSuccess = { u -> if (u != null) UpdateStatus.Available(u) else UpdateStatus.UpToDate },
                onFailure = { UpdateStatus.Failed }
            )
        }
    }

    /** Zmiana kanału od razu sprawdza GitHuba - wynik (np. nowa beta) widać w tej samej sekcji. */
    fun setUpdateChannel(channel: UpdateChannel) = writeSetting {
        if (channel == state.value.updateChannel) return@writeSetting
        settings.setUpdateChannel(channel)
        _updateStatus.value = UpdateStatus.Idle
        checkForUpdates()
    }

    fun setDynamicColor(enabled: Boolean) = writeSetting {
        settings.setDynamicColor(enabled)
        widgetUpdater.requestUpdate()   // widżety w kolorach z tapety
    }

    fun setStartScreen(screen: StartScreen) = writeSetting { settings.setStartScreen(screen) }

    fun setReminder(value: ReminderSettings) = writeSetting {
        settings.setReminderSettings(value)
        reminders.reschedule()
    }

    fun setQuietHours(value: QuietHours) = writeSetting { settings.setQuietHours(value) }

    fun setAccent(value: AccentSetting) = writeSetting {
        settings.setAccent(value)
        widgetUpdater.requestUpdate()   // widżety w nowym kolorze
    }

    fun setWidgetLook(value: WidgetLook) = writeSetting {
        settings.setWidgetLook(value)
        widgetUpdater.requestUpdate()
    }

    fun setTimetableLook(look: TimetableLook) = writeSetting { settings.setTimetableLook(look) }

    fun resetCache() {
        state.value.selectedClassId?.let { classSelection.resetCacheAndResync(it) }
    }

    fun setNotifSubs(b: Boolean) = writeSetting { settings.setNotificationsSubstitutions(b) }
    fun setNotifAnn(b: Boolean) = writeSetting { settings.setNotificationsAnnouncements(b) }
    fun setShowNextLesson(b: Boolean) = writeSetting { settings.setShowNextLesson(b) }
    fun setShowSubstitutions(b: Boolean) = writeSetting { settings.setShowSubstitutions(b) }
    fun setShowAnnouncements(b: Boolean) = writeSetting { settings.setShowAnnouncements(b) }
    fun setCountdownSettings(value: CountdownSettings) = writeSetting { settings.setCountdownSettings(value) }
    fun setAutoDndEnabled(value: Boolean) = writeSetting {
        settings.setAutoDndEnabled(value)
        dndScheduler.requestReschedule()
    }

    companion object { private const val TAG = "SettingsViewModel" }
}
