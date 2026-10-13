package pl.zse.bydgoszcz.elektron.presentation.timetable

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator
import pl.zse.bydgoszcz.elektron.domain.sync.SyncRequest
import pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome
import pl.zse.bydgoszcz.elektron.domain.model.SubstitutionDisplay
import pl.zse.bydgoszcz.elektron.domain.model.SchoolPageChangedException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import pl.zse.bydgoszcz.elektron.domain.model.LessonLinks
import pl.zse.bydgoszcz.elektron.domain.model.LessonTarget
import pl.zse.bydgoszcz.elektron.domain.model.Lesson
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import java.time.DayOfWeek as JavaDayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Plan lekcji jako strony (HorizontalPager): każda strona to dzień szkolny albo tydzień.
 *
 * Dane tygodnia są udostępniane na żądanie ([week]) i buforowane per poniedziałek, więc
 * sąsiednie strony są gotowe, zanim palec do nich dojedzie, a powrót jest natychmiastowy.
 * Strona startowa ([START_PAGE]) odpowiada [baseDate] — dniowi otwarcia ekranu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repo: TimetableRepository,
    private val coordinator: SyncCoordinator
) : ViewModel() {

    enum class ViewMode { DAY, WEEK }

    data class DayColumn(val date: LocalDate, val dayOfWeek: JavaDayOfWeek, val lessons: List<Lesson>)

    data class State(
        val selectedClassId: String? = null,
        val mode: ViewMode = ViewMode.DAY,
        val anchorDate: LocalDate = AppClock.today(),
        val isRefreshing: Boolean = false
    )

    /** Dzień szkolny, od którego liczone są strony (dziś; w weekend — najbliższy poniedziałek). */
    val baseDate: LocalDate = initialAnchor()

    private val anchor = MutableStateFlow(baseDate)
    private val mode = MutableStateFlow(ViewMode.DAY)
    /** Tryb już ustawiony (z zapisanego wyboru albo przez użytkownika) - nie nadpisuj. */
    private var modeInitialized = false

    /** Tryb Dzień/Tydzień - ekran czyta go wprost (bez opóźnienia łączonego stanu). */
    val viewMode: StateFlow<ViewMode> get() = mode

    /**
     * Zapamiętany tryb z personalizacji (wczytanej przed pokazaniem aplikacji) - ekran woła
     * to przy pierwszym wyświetleniu, więc tydzień jest od pierwszej klatki, bez mignięcia dnia.
     */
    /** Initial UI value before the lifecycle collector starts; does not mutate state. */
    fun modeForOpening(weekView: Boolean): ViewMode =
        if (modeInitialized) mode.value else if (weekView) ViewMode.WEEK else ViewMode.DAY

    fun initSavedMode(weekView: Boolean) {
        if (modeInitialized) return
        modeInitialized = true
        mode.value = if (weekView) ViewMode.WEEK else ViewMode.DAY
    }
    private val refreshing = MutableStateFlow(false)

    private val _syncingWeek = MutableStateFlow<LocalDate?>(null)
    /** Poniedziałek tygodnia, który właśnie się pobiera (brakował w bazie) — do stanu "ładowanie". */
    val syncingWeek: StateFlow<LocalDate?> = _syncingWeek

    /** Bieżąca data (do ustawienia strony startowej pagera po zmianie trybu). */
    val currentAnchor: LocalDate get() = anchor.value

    // Przed blokiem init (kolejność inicjalizacji): używa go także sync brakującego tygodnia.
    /** Komunikat po nieudanym odświeżeniu (pokazywany raz na ekranie planu). */
    private val _refreshMessage = MutableStateFlow<String?>(null)
    val refreshMessage: StateFlow<String?> = _refreshMessage

    fun consumeRefreshMessage() {
        _refreshMessage.value = null
    }

    init {
        // Ostatnio wybrany tryb (Dzień/Tydzień) - zapamiętany w ustawieniach.
        viewModelScope.launch {
            val week = runCatchingCancellable { settings.timetableLook.first().weekView }.getOrDefault(false)
            if (!modeInitialized) initSavedMode(week)
        }
        // Sync tylko gdy oglądanego tygodnia brakuje w bazie (plan zapisywany jest na
        // 4 tygodnie naraz, świeżość zapewnia SyncWorker i odświeżanie ręczne).
        combine(settings.selectedClassId, anchor) { cid, day -> cid to day.with(JavaDayOfWeek.MONDAY) }
            .distinctUntilChanged()
            .onEach { (cid, monday) ->
                if (cid == null) return@onEach
                // Miniony tydzień spoza bazy: strona szkoły ma tylko aktualny plan - nie pobieramy
                // (dawniej bieżący szablon trafiał jako plan sprzed miesięcy). Ekran pokazuje komunikat.
                if (isPastWeek(monday, AppClock.today())) return@onEach
                runCatchingCancellable {
                if (!repo.hasLessons(cid, monday, monday.plusDays(4))) {
                    _syncingWeek.value = monday
                    try {
                        // Przez koordynator (plan od tego tygodnia). Błąd nie jest cicho ignorowany.
                        val outcome = coordinator.sync(SyncRequest.of(SyncOutcome.TIMETABLE, anchorDate = monday))
                        outcome.failures[SyncOutcome.TIMETABLE]?.let {
                            _refreshMessage.value = SyncOutcome.errorMessage(mapOf(SyncOutcome.TIMETABLE to it))
                        }
                    } finally { _syncingWeek.value = null }
                }
                }.onFailure { _refreshMessage.value = pl.zse.bydgoszcz.elektron.domain.model.SyncErrors.userMessage(it) }
            }
            .launchIn(viewModelScope)
    }

    val state: StateFlow<State> = combine(settings.selectedClassId, anchor, mode, refreshing) { cid, day, m, r ->
        State(selectedClassId = cid, mode = m, anchorDate = day, isRefreshing = r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State(anchorDate = baseDate))

    /**
     * Ostatnio oglądane tygodnie (LRU, najwyżej [WEEK_CACHE_SIZE]). Dawniej HashMap rosła bez
     * końca przy przewijaniu planu - każdy obejrzany tydzień zostawał w pamięci na zawsze.
     * Usunięty tydzień, jeśli wciąż jest na ekranie, działa dalej (jest tylko poza buforem).
     */
    private val weekCache = SharedWeekCache<LocalDate, List<DayColumn>?>(viewModelScope, WEEK_CACHE_SIZE) { monday ->
        settings.selectedClassId.flatMapLatest { cid ->
            val data: Flow<List<DayColumn>?> = if (cid == null) flowOf(emptyList())
            else combine(
                repo.observeLessons(cid, monday, monday.plusDays(4)),
                settings.activeGroupSelections
            ) { raw, sel -> LessonGroups.filter(raw, sel) }.map { lessons ->
                (0L..4L).map { i ->
                    val d = monday.plusDays(i)
                    DayColumn(d, d.dayOfWeek, lessons.filter { it.date == d }.sortedBy { it.number })
                }
            }
            data.onStart { emit(null) }
        }.flowOn(Dispatchers.Default)
    }

    fun week(monday: LocalDate): Flow<List<DayColumn>?> = weekCache.get(monday)

    fun setMode(m: ViewMode) {
        modeInitialized = true
        if (mode.value == m) return
        mode.value = m
        viewModelScope.launch {
            runCatchingCancellable {
                val look = settings.timetableLook.first()
                settings.setTimetableLook(look.copy(weekView = m == ViewMode.WEEK))
            }.onFailure { _refreshMessage.value = "Nie udało się zapisać widoku planu. ${pl.zse.bydgoszcz.elektron.domain.model.SyncErrors.userMessage(it)}" }
        }
    }

    /** Pager zatrzymał się na stronie dnia [date]. */
    fun onDaySettled(date: LocalDate) {
        anchor.value = date
    }

    /** Pager zatrzymał się na tygodniu od [monday] — zachowujemy dzień tygodnia z poprzedniej daty. */
    fun onWeekSettled(monday: LocalDate) {
        val weekdayIndex = (anchor.value.dayOfWeek.value - 1).coerceIn(0, 4)
        anchor.value = monday.plusDays(weekdayIndex.toLong())
    }

    /**
     * Odświeżenie (przycisk u góry): plan od oglądanego tygodnia i zastępstwa - przez koordynator
     * (komunikaty, przypomnienia, widżety, powiadomienia - tam). Na minionym tygodniu repozytorium
     * odświeża tylko bieżący i przyszłe - planu sprzed tygodni nie nadpisuje.
     */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                if (settings.selectedClassId.first() == null) return@launch
                val outcome = coordinator.sync(
                    SyncRequest.of(
                        SyncOutcome.TIMETABLE,
                        SyncOutcome.SUBSTITUTIONS,
                        SyncOutcome.CALENDAR,
                        anchorDate = anchor.value
                    )
                )
                // Komunikat na ekranie planu (raz); przerwane zmianą klasy - bez komunikatu.
                if (!outcome.interrupted) _refreshMessage.value = SyncOutcome.errorMessage(outcome.failures)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _refreshMessage.value = pl.zse.bydgoszcz.elektron.domain.model.SyncErrors.userMessage(e) }
            finally {
                refreshing.value = false
            }
        }
    }

    private fun initialAnchor(): LocalDate = nextSchoolDay(AppClock.today())

    /**
     * Dzień startowy planu (dziś; po lekcjach następny dzień) - liczony w ViewModelu, a nie na
     * ekranie: przy wyjściu z zakładki planu i przy wejściu do niej (NavHost), po powrocie do
     * aplikacji po >= 3 min i przy starcie. Dzięki temu plan zwykle już stoi na właściwym dniu,
     * zanim się pokaże - bez mignięcia poprzedniego dnia i przeskoku.
     * [id] rośnie z każdym żądaniem; ekran obsługuje każde najwyżej raz.
     */
    data class OpeningJump(val day: LocalDate, val id: Int)

    private val _openingJump = MutableStateFlow<OpeningJump?>(null)
    val openingJump: StateFlow<OpeningJump?> = _openingJump
    private var jumpSeq = 0
    /** Ostatnie żądanie obsłużone przez ekran (przewinięcie pagera). */
    var handledJumpId = 0
        private set
    private var leftAppAt = 0L

    /**
     * Szczegóły lekcji do otwarcia (dotknięcie zastępstwa) - jednorazowo: ekran pokazuje okno
     * i woła [consumeLessonDetails]. Obrót ekranu, powrót do aplikacji czy zmiana zakładki nie
     * otwierają go ponownie.
     */
    private val _lessonDetails = MutableStateFlow<Lesson?>(null)
    val lessonDetails: StateFlow<Lesson?> = _lessonDetails
    private var openLessonJob: Job? = null
    private var pinnedUntilNanos = 0L

    // Po deklaracji pól powyżej (kolejność inicjalizacji w Kotlinie!): dzień startowy od razu
    // przy utworzeniu - ViewModel powstaje w NavHost, zanim plan zostanie pokazany.
    init {
        applyOpeningDay()
    }

    fun applyOpeningDay() {
        viewModelScope.launch {
            val day = preferredDay()
            // Właśnie otwarto lekcję z zastępstwa (openLesson) - nie przeskakuj z jej dnia
            // (wejście na zakładkę planu woła applyOpeningDay chwilę po openLesson).
            if (lessonDayPinned()) return@launch
            anchor.value = day
            _openingJump.value = OpeningJump(day, ++jumpSeq)
        }
    }

    fun consumeLessonDetails() {
        _lessonDetails.value = null
    }

    private fun lessonDayPinned(): Boolean = System.nanoTime() < pinnedUntilNanos

    /**
     * Plan na dzień [date] bez okna szczegółów - karta "Następna lekcja" na stronie głównej.
     * Dawniej sama zakładka otwierała dzień preferowany (dziś albo następny dzień roboczy),
     * który nie musiał mieć pokazanej lekcji (np. dziś bez lekcji, karta "Jutro").
     */
    fun openDay(date: LocalDate) {
        pinnedUntilNanos = System.nanoTime() + PIN_MS * 1_000_000
        openLessonJob?.cancel()
        anchor.value = date
        _openingJump.value = OpeningJump(date, ++jumpSeq)
    }

    /**
     * Plan na dzień [target] (w widoku tygodnia - jego tydzień) i okno szczegółów tej lekcji,
     * gdy jest w planie po filtrze grup. Plan jeszcze się wczytuje - czekamy najwyżej
     * [LESSON_WAIT_MS]; lekcji nie ma - zostaje sam plan na ten dzień, bez okna.
     */
    fun openLesson(target: LessonTarget) {
        pinnedUntilNanos = System.nanoTime() + PIN_MS * 1_000_000
        anchor.value = target.date
        _openingJump.value = OpeningJump(target.date, ++jumpSeq)
        _lessonDetails.value = null
        openLessonJob?.cancel()
        openLessonJob = viewModelScope.launch {
            val lesson = runCatchingCancellable { awaitLesson(target) }.getOrNull()
            if (lesson != null) _lessonDetails.value = lesson
        }
    }

    private suspend fun awaitLesson(target: LessonTarget): Lesson? {
        val cid = settings.selectedClassId.first() ?: return null
        val monday = target.date.with(JavaDayOfWeek.MONDAY)
        val found = withTimeoutOrNull(LESSON_WAIT_MS) {
            combine(
                repo.observeLessons(cid, monday, monday.plusDays(4)),
                settings.activeGroupSelections,
                _syncingWeek
            ) { raw, sel, syncing ->
                when {
                    raw.isEmpty() || syncing == monday -> null // jeszcze się wczytuje - czekamy
                    else -> LessonLookup(LessonLinks.findLesson(raw, target, sel))
                }
            }.filterNotNull().first()
        }
        return found?.lesson
    }

    private class LessonLookup(val lesson: Lesson?)

    fun markJumpHandled(id: Int) {
        if (id > handledJumpId) handledJumpId = id
    }

    fun onLeftApp() {
        leftAppAt = System.currentTimeMillis()
    }

    /** Powrót do aplikacji: po dłuższej nieobecności (np. po lekcjach) - dzień od nowa. */
    fun onAppStart() {
        val away = leftAppAt != 0L && System.currentTimeMillis() - leftAppAt >= RETURN_RESET_MS
        leftAppAt = 0L
        if (away) applyOpeningDay()
    }

    /** Dzień otwierany domyślnie: dziś, a po ostatniej dzisiejszej lekcji (według grup) — następny. */
    suspend fun preferredDay(): LocalDate {
        val today = AppClock.today()
        val now = AppClock.time()
        val cid = settings.selectedClassId.first() ?: return nextSchoolDay(today)
        val lessons = runCatchingCancellable {
            LessonGroups.filter(repo.getLessonsOnce(cid, today, today), settings.groupSelections(cid).first())
        }.getOrDefault(emptyList())
        // "Po lekcjach" liczone od ostatniej lekcji, która się odbywa (zwolnienie z ostatnich
        // lekcji = koniec dnia wcześniej).
        return preferredDay(today, now, lessons.filter(SubstitutionDisplay::takesPlace).maxOfOrNull { it.timeTo })
    }

    companion object {
        /** Środek zakresu stron — wystarczy na lata przewijania w obie strony. */
        const val START_PAGE = 5_000
        const val PAGE_COUNT = START_PAGE * 2
        private const val RETURN_RESET_MS = 3 * 60_000L
        /** Jak długo czekać na plan przed otwarciem szczegółów lekcji (potem bez okna). */
        const val LESSON_WAIT_MS = 5_000L
        /** Ile tygodni planu trzymać w buforze [week]. */
        const val WEEK_CACHE_SIZE = 12
        /** Jak długo dzień otwartej lekcji ma pierwszeństwo przed dniem startowym. */
        private const val PIN_MS = 3_000L

        /**
         * Czysta reguła dnia domyślnego: weekend -> poniedziałek; dziś po ostatniej lekcji
         * ([lastLessonEnd]) -> następny dzień szkolny; inaczej dziś.
         */
        fun preferredDay(today: LocalDate, now: LocalTime, lastLessonEnd: LocalTime?): LocalDate {
            val start = nextSchoolDay(today)
            if (start != today) return start
            return if (lastLessonEnd != null && now >= lastLessonEnd) nextSchoolDay(today.plusDays(1)) else today
        }

        /**
         * Tydzień od [monday] jest już miniony (przed bieżącym tygodniem [today]). Takiego planu
         * nie pobieramy - strona szkoły publikuje tylko aktualny; w bazie są ostatnie tygodnie,
         * które aplikacja zdążyła zapisać.
         */
        fun isPastWeek(monday: LocalDate, today: LocalDate): Boolean =
            monday.with(JavaDayOfWeek.MONDAY) < today.with(JavaDayOfWeek.MONDAY)

        fun nextSchoolDay(d: LocalDate): LocalDate = when (d.dayOfWeek) {
            JavaDayOfWeek.SATURDAY -> d.plusDays(2)
            JavaDayOfWeek.SUNDAY -> d.plusDays(1)
            else -> d
        }

        /** Data dnia szkolnego dla strony (tryb dnia): weekendy pomijane. */
        fun dayForPage(base: LocalDate, page: Int): LocalDate = addSchoolDays(base, page - START_PAGE)

        fun pageForDay(base: LocalDate, date: LocalDate): Int =
            START_PAGE + schoolDaysBetween(base, nextSchoolDay(date))

        /** Poniedziałek tygodnia dla strony (tryb tygodnia). */
        fun mondayForPage(base: LocalDate, page: Int): LocalDate =
            base.with(JavaDayOfWeek.MONDAY).plusWeeks((page - START_PAGE).toLong())

        fun pageForWeek(base: LocalDate, date: LocalDate): Int =
            START_PAGE + ChronoUnit.WEEKS.between(base.with(JavaDayOfWeek.MONDAY), date.with(JavaDayOfWeek.MONDAY)).toInt()

        private fun addSchoolDays(start: LocalDate, n: Int): LocalDate {
            // Pełne tygodnie od razu, reszta dniami — szybkie także dla odległych stron.
            var d = start.plusWeeks((n / 5).toLong())
            var rest = n % 5
            val step = if (rest >= 0) 1L else -1L
            while (rest != 0) {
                d = d.plusDays(step)
                if (d.dayOfWeek != JavaDayOfWeek.SATURDAY && d.dayOfWeek != JavaDayOfWeek.SUNDAY) {
                    rest -= step.toInt()
                }
            }
            return d
        }

        private fun schoolDaysBetween(from: LocalDate, to: LocalDate): Int {
            val weeks = ChronoUnit.WEEKS.between(from.with(JavaDayOfWeek.MONDAY), to.with(JavaDayOfWeek.MONDAY)).toInt()
            return weeks * 5 + (to.dayOfWeek.value - from.dayOfWeek.value)
        }
    }
}
