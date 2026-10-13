package pl.zse.bydgoszcz.elektron.presentation.dashboard

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator
import pl.zse.bydgoszcz.elektron.domain.sync.SyncRequest
import java.time.LocalDateTime
import pl.zse.bydgoszcz.elektron.domain.model.LessonClock
import pl.zse.bydgoszcz.elektron.domain.model.DashboardCardItem
import pl.zse.bydgoszcz.elektron.domain.model.DashboardLayoutUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.Lesson
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.model.Substitution
import pl.zse.bydgoszcz.elektron.domain.model.SubstitutionRelevance
import pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SubstitutionsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository
import pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate
import pl.zse.bydgoszcz.elektron.presentation.common.currentDateFlow
import pl.zse.bydgoszcz.elektron.presentation.common.visibleMinuteTicker
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import pl.zse.bydgoszcz.elektron.domain.model.ClassroomDuty
import pl.zse.bydgoszcz.elektron.domain.model.OverallAttendance
import pl.zse.bydgoszcz.elektron.domain.model.UserActivity
import pl.zse.bydgoszcz.elektron.domain.repository.AttendanceRepository
import pl.zse.bydgoszcz.elektron.domain.repository.DutyRepository
import pl.zse.bydgoszcz.elektron.domain.repository.UserActivityRepository
import pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings
import pl.zse.bydgoszcz.elektron.service.AutoDndManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val substitutionsRepo: SubstitutionsRepository,
    private val announcementsRepo: AnnouncementsRepository,
    private val notificationsRepo: NotificationsRepository,
    private val timetableRepo: TimetableRepository,
    private val updateRepo: UpdateRepository,
    private val coordinator: SyncCoordinator,
    private val dutyRepo: DutyRepository,
    private val attendanceRepo: AttendanceRepository,
    private val activityRepo: UserActivityRepository,
    private val dndScheduler: pl.zse.bydgoszcz.elektron.service.AutoDndScheduler? = null,
    @ApplicationContext private val appContext: Context? = null
) : ViewModel() {
    private val visible = MutableStateFlow(false)
    fun setVisible(value: Boolean) {
        visible.value = value
        if (value) dndScheduler?.requestReschedule()
    }


    /** Break = przerwa między dwiema lekcjami dziś (pokazujemy następną lekcję + czas przerwy). */
    enum class LessonStatus { Now, Break, Next, None }

    data class State(
        val selectedClassId: String? = null,
        val className: String? = null,
        val isSyncing: Boolean = false,
        val lastSyncError: String? = null,
        val loadingTimetable: Boolean = true,
        val loadingSubs: Boolean = true,
        val loadingAnns: Boolean = true,
        val nextLesson: Lesson? = null,
        val nextLessonDayLabel: String? = null,
        val nextLessonStatus: LessonStatus = LessonStatus.None,
        val countdownMinutes: Long? = null,
        /** Trwająca lekcja: postęp 0..1 i pozostałe minuty (jak w widżecie). */
        val lessonProgress: Float? = null,
        /** Nowa wersja aplikacji na GitHubie (baner), null gdy brak. */
        val availableUpdate: AppUpdate? = null,
        /** Czas ostatniej udanej synchronizacji, jeśli dane są starsze niż godzina (offline). */
        val staleSince: Instant? = null,
        val minutesLeft: Long? = null,
        val upcomingSubstitutions: List<Substitution> = emptyList(),
        val latestAnnouncements: List<Announcement> = emptyList(),
        val isRefreshing: Boolean = false,
        val lastSyncAt: Instant? = null,
        val showDeparture: Boolean = false,
        val showNextLesson: Boolean = true,
        val showSubstitutions: Boolean = true,
        val showAnnouncements: Boolean = true,
        val duty: ClassroomDuty? = null,
        val overallAttendance: OverallAttendance? = null,
        val atRiskSubjectsCount: Int = 0,
        val attendanceTargetPercent: Int = 75,
        val todayActivities: List<UserActivity> = emptyList(),
        val countdown: CountdownSettings = CountdownSettings(),
        val autoDndActive: Boolean = false,
        val cardOrder: List<DashboardCardItem> = DashboardLayoutUtils.defaultOrder(),
        /** Pierwsze dane już są (do tego czasu ekran jest niewidoczny, bez mignięć). */
        val ready: Boolean = false
    )

    private data class Core(
        val cid: String? = null,
        // Bug #2: classShort MUSI pochodzić z listy klas (dostępnej od razu po sync sidebaru
        // podczas Setup), a nie z pierwszej zsynchronizowanej lekcji — inaczej, zanim plan
        // lekcji się załaduje, className jest null i filtr zastępstw przepuszczał WSZYSTKIE
        // klasy zamiast żadnej.
        val classShort: String? = null,
        val lessons: List<Lesson> = emptyList(),
        val subs: List<Substitution> = emptyList(),
        val refresh: Boolean = false,
        val anns: List<Announcement> = emptyList(),
        val lastSync: Instant? = null,
        val syncing: Boolean = false,
        val error: String? = null,
        val loaded: Set<String> = emptySet(),
        // Bug #1: sygnał "trwa pierwszy sync" — jaśniejszy niż samo "loaded", bo obejmuje
        // całe okno między wyborem klasy a zakończeniem (sukcesem lub porażką) syncu.
        val initialSyncPending: Boolean = false,
        /** Wybrane grupy zajęciowe dla klasy (przedmiot -> grupa / NONE). */
        val groups: Map<String, String> = emptyMap(),
        val update: AppUpdate? = null,
        val showNextLesson: Boolean = true,
        val showSubstitutions: Boolean = true,
        val showAnnouncements: Boolean = true,
        val duty: ClassroomDuty? = null,
        val overallAttendance: OverallAttendance? = null,
        val attendanceTargetPercent: Int = 75,
        val todayActivities: List<UserActivity> = emptyList(),
        val countdown: CountdownSettings? = null,
        val autoDnd: Boolean = false,
        val cardOrderRaw: String? = null
    )

    private val refreshing = MutableStateFlow(false)
    // Zakres dat liczony od bieżącej daty (przebudowywany po północy), nie od chwili
    // utworzenia ViewModelu — ten żyje przez cały czas działania aplikacji.
    private val todayFlow = currentDateFlow()

    private val selected = settings.selectedClassId

    // Skrót klasy niezależny od tego, czy plan lekcji już się zsynchronizował — pochodzi
    // z listy klas (observeClasses), dostępnej od razu po sync sidebaru.
    private val classShortFlow: Flow<String?> = selected.flatMapLatest { cid: String? ->
        if (cid == null) flowOf(null)
        else timetableRepo.observeClasses().map { list -> list.firstOrNull { it.id == cid }?.shortName }
    }

    private val lessonsFlow: Flow<List<Lesson>> = combine(selected, todayFlow) { cid, day -> cid to day }
        .flatMapLatest { (cid, day) ->
            if (cid == null) flowOf(emptyList())
            else timetableRepo.observeLessons(cid, day, day.plusDays(21))
        }

    private val subsFlow: Flow<List<Substitution>> = todayFlow.flatMapLatest { day -> substitutionsRepo.observeFrom(day) }

    val state: StateFlow<State> = combine(
        selected, classShortFlow, lessonsFlow, subsFlow, refreshing
    ) { cid: String?, classShort: String?, lessons: List<Lesson>, subs: List<Substitution>, refresh: Boolean ->
        Core(cid = cid, classShort = classShort, lessons = lessons, subs = subs, refresh = refresh)
    }
        .combine(announcementsRepo.observeLatest(3)) { core, anns -> core.copy(anns = anns) }
        .combine(notificationsRepo.observeLastSyncAt()) { core, at -> core.copy(lastSync = at) }
        .combine(coordinator.isSyncing) { core, syncing -> core.copy(syncing = syncing) }
        .combine(notificationsRepo.observeLastSyncError()) { core, err -> core.copy(error = err) }
        .combine(notificationsRepo.observeLoadedResources()) { core, loaded -> core.copy(loaded = loaded) }
        .combine(coordinator.initialSyncPending) { core, pending -> core.copy(initialSyncPending = pending) }
        .combine(settings.showNextLesson) { core, v -> core.copy(showNextLesson = v) }
        .combine(settings.showSubstitutions) { core, v -> core.copy(showSubstitutions = v) }
        .combine(settings.showAnnouncements) { core, v -> core.copy(showAnnouncements = v) }
        .combine(settings.activeGroupSelections) { core, sel -> core.copy(groups = sel) }
        .combine(updateRepo.availableUpdate) { core, u -> core.copy(update = u) }
        .combine(dutyRepo.observeDuty()) { core, d -> core.copy(duty = d) }
        .combine(attendanceRepo.observeOverall()) { core, a -> core.copy(overallAttendance = a) }
        .combine(settings.attendanceTargetPercent) { core, tp -> core.copy(attendanceTargetPercent = tp) }
        .combine(todayFlow.flatMapLatest { activityRepo.observeForDate(it) }) { core, acts -> core.copy(todayActivities = acts) }
        .combine(settings.countdownSettings) { core, cs -> core.copy(countdown = cs) }
        .combine(settings.autoDndEnabled) { core, dnd -> core.copy(autoDnd = dnd) }
        .combine(settings.dashboardCardsOrder) { core, order -> core.copy(cardOrderRaw = order) }
        .combine(visibleMinuteTicker(visible)) { core, _ -> core }
        .map { core -> compute(core).copy(ready = true) }
        // Przeliczanie stanu (filtry grup, zastępstwa, odliczanie) poza wątkiem UI.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    private fun compute(c: Core): State {
        val cid = c.cid
        if (cid == null) {
            return State(selectedClassId = null, loadingTimetable = false,
                loadingSubs = false, loadingAnns = false,
                latestAnnouncements = emptyList(), lastSyncAt = c.lastSync,
                isSyncing = c.syncing, lastSyncError = c.error,
                showNextLesson = c.showNextLesson, showSubstitutions = c.showSubstitutions,
                showAnnouncements = c.showAnnouncements,
                attendanceTargetPercent = c.attendanceTargetPercent,
                countdown = c.countdown ?: CountdownSettings(),
                autoDndActive = false)
        }

        // Bug #1 (drugorzędny): today/now liczone na nowo przy każdym przeliczeniu, nie raz
        // przy tworzeniu ViewModelu — appka otwarta przez noc nie pokaże wczorajszej lekcji
        // jako "trwającej teraz".
        val today = AppClock.today()
        val now = AppClock.time()

        // Lekcje po odfiltrowaniu grup, na które użytkownik nie chodzi. c.lessons (surowe)
        // zostają do oceny, czy zastępstwo z numerem grupy dotyczy użytkownika.
        val lessons = LessonGroups.filter(c.lessons, c.groups)
        val className = lessons.firstOrNull()?.className ?: c.lessons.firstOrNull()?.className

        // Trwa / przerwa / przed lekcją - wspólna reguła z widżetami i planem (LessonClock):
        // czas do lekcji tylko w przerwie albo w ostatnich 30 min przed nią (nie w okienku).
        val clock = LessonClock.status(lessons, LocalDateTime.of(today, now))
        val (lesson, status) = when (clock) {
            is LessonClock.During -> clock.lesson to LessonStatus.Now
            is LessonClock.Break -> clock.next to LessonStatus.Break
            is LessonClock.Before -> clock.next to LessonStatus.Next
            LessonClock.Nothing -> null to LessonStatus.None
        }

        val dayLabel = lesson?.date?.let { d: LocalDate ->
            when (d) {
                today -> "Dziś"
                today.plusDays(1) -> "Jutro"
                else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("pl", "PL"))
                    .replaceFirstChar { it.titlecase(Locale("pl", "PL")) }
            }
        }

        val countdownMinutes: Long? = (clock as? LessonClock.Before)?.minutesUntil

        // Koniec lekcji wg dzwonków (z całego planu klasy, przed filtrem grup) - zastępstwo
        // znika ze strony głównej po swojej lekcji, także za lekcję innej grupy.
        val lessonEnds = SubstitutionRelevance.lessonEnds(c.lessons)
        val nowDt = LocalDateTime.of(today, now)

        // Bug #2, druga część: gdy klasa nie jest jeszcze znana, pokazujemy PUSTĄ listę,
        // nigdy wszystkie zastępstwa. To bezpieczny domyślny wariant — lepiej chwilę
        // "Ładowanie" niż zastępstwa cudzej klasy.
        val upcoming: List<Substitution> = if (c.classShort == null) emptyList() else c.subs
            .filter { sub: Substitution -> SubstitutionRelevance.matchesClass(sub, c.classShort) }
            .filter { sub: Substitution -> LessonGroups.substitutionRelevant(sub, c.lessons, c.groups) }
            .filter { sub: Substitution -> !SubstitutionRelevance.isOver(sub, nowDt, lessonEnds) }
            .sortedWith(compareBy({ it.date }, { it.lessonNumber }))
            .take(10)
            .map { LessonGroups.withSubject(it, c.lessons, c.groups) }

        val timetableLoaded = "timetable" in c.loaded
        val subsLoaded = "subs" in c.loaded
        val annsLoaded = "anns" in c.loaded

        // Bug #1: dopóki trwa pierwszy sync ALBO zasób jeszcze nie jest oznaczony jako
        // załadowany, a nie mamy jeszcze danych do pokazania — pokazujemy "Ładowanie",
        // nie pusty/"brak" stan.
        val stillLoadingBase = c.initialSyncPending
        val isLessonOngoing = (status == LessonStatus.Now)
        val autoDndActive = (appContext?.let { AutoDndManager.isSilenced(it) } ?: false) || (c.autoDnd && isLessonOngoing)

        return State(
            selectedClassId = cid,
            className = className,
            isSyncing = c.syncing,
            lastSyncError = c.error,
            loadingTimetable = (stillLoadingBase || (c.syncing && !timetableLoaded)) && lesson == null,
            loadingSubs = (stillLoadingBase || (c.syncing && !subsLoaded)) && upcoming.isEmpty(),
            loadingAnns = (stillLoadingBase || (c.syncing && !annsLoaded)) && c.anns.isEmpty(),
            nextLesson = lesson,
            nextLessonDayLabel = dayLabel,
            nextLessonStatus = status,
            staleSince = c.lastSync?.takeIf { java.time.Duration.between(it, Instant.now()).toMinutes() >= 60 },
            availableUpdate = c.update,
            countdownMinutes = countdownMinutes,
            // Postęp: w trakcie lekcji — lekcji; w przerwie — przerwy (do początku następnej lekcji).
            lessonProgress = when (clock) {
                is LessonClock.During -> clock.progress
                is LessonClock.Break -> clock.progress
                else -> null
            },
            minutesLeft = when (clock) {
                is LessonClock.During -> clock.minutesLeft
                is LessonClock.Break -> clock.minutesUntil
                else -> null
            },
            upcomingSubstitutions = upcoming,
            latestAnnouncements = c.anns.take(3),
            isRefreshing = c.refresh,
            lastSyncAt = c.lastSync,
            showDeparture = pl.zse.bydgoszcz.elektron.domain.model.DashboardDepartures.isTime(lessons.map { lesson ->
                val sub = c.subs.firstOrNull { it.date == lesson.date && it.lessonNumber == lesson.number &&
                    c.classShort != null && SubstitutionRelevance.matchesClass(it, c.classShort) &&
                    LessonGroups.substitutionRelevant(it, c.lessons, c.groups) }
                if (sub == null) lesson else lesson.copy(substitution = sub)
            }, nowDt),
            showNextLesson = c.showNextLesson,
            showSubstitutions = c.showSubstitutions,
            showAnnouncements = c.showAnnouncements,
            duty = c.duty,
            overallAttendance = c.overallAttendance,
            atRiskSubjectsCount = c.overallAttendance?.criticalSubjectsCount ?: 0,
            attendanceTargetPercent = c.attendanceTargetPercent,
            todayActivities = c.todayActivities,
            countdown = c.countdown ?: CountdownSettings(),
            autoDndActive = autoDndActive,
            cardOrder = DashboardLayoutUtils.decode(c.cardOrderRaw)
        )
    }

    fun saveDashboardLayout(items: List<DashboardCardItem>) {
        viewModelScope.launch {
            settings.setDashboardCardsOrder(DashboardLayoutUtils.encode(items))
        }
    }

    fun dismissUpdate(versionName: String) {
        viewModelScope.launch { updateRepo.dismiss(versionName) }
    }

    fun setStudentNumber(number: Int?) {
        viewModelScope.launch { dutyRepo.setStudentNumber(number) }
    }

    fun setClassSize(size: Int) {
        viewModelScope.launch { dutyRepo.setClassSize(size) }
    }

    fun toggleActivity(id: String, completed: Boolean) {
        viewModelScope.launch { activityRepo.setCompleted(id, completed) }
    }

    fun deleteActivity(id: String) {
        viewModelScope.launch { activityRepo.delete(id) }
    }

    fun setCountdownSettings(settings: CountdownSettings) {
        viewModelScope.launch { this@DashboardViewModel.settings.setCountdownSettings(settings) }
    }

    fun setAutoDndEnabled(enabled: Boolean) {
        viewModelScope.launch {
            this@DashboardViewModel.settings.setAutoDndEnabled(enabled)
            dndScheduler?.requestReschedule()
        }
    }

    /** Pull-to-refresh: pełna synchronizacja przez koordynator i odświeżenie frekwencji. */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                coordinator.sync(SyncRequest.full())
                attendanceRepo.sync()
            } finally {
                refreshing.value = false
            }
        }
    }
}
