package pl.zse.bydgoszcz.elektron.presentation.dashboard

import pl.zse.bydgoszcz.elektron.presentation.common.DelayedLoading
import pl.zse.bydgoszcz.elektron.presentation.common.revealWhen
import pl.zse.bydgoszcz.elektron.presentation.common.UpdateActions
import pl.zse.bydgoszcz.elektron.presentation.common.LocalPersonalization
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import pl.zse.bydgoszcz.elektron.presentation.common.pressable
import pl.zse.bydgoszcz.elektron.presentation.common.rememberNow
import pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate
import pl.zse.bydgoszcz.elektron.domain.util.AppClock
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import coil.request.ImageRequest
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.Substitution
import pl.zse.bydgoszcz.elektron.domain.model.SubstitutionDisplay
import pl.zse.bydgoszcz.elektron.presentation.common.ElektronCard
import pl.zse.bydgoszcz.elektron.presentation.common.LargeTitleBar
import pl.zse.bydgoszcz.elektron.presentation.common.SectionTitle
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    /** Dotknięcie karty najbliższej lekcji - przejście do planu (na dzień startowy). */
    /** Dzień pokazanej lekcji (null - brak lekcji, zwykłe otwarcie planu). */
    onOpenTimetable: (java.time.LocalDate?) -> Unit = {},
    /** Dotknięcie zastępstwa - przejście do zakładki Zastępstwa. */
    onOpenSubstitutions: () -> Unit = {},
    onOpenTransit: () -> Unit = {},
    /** Dotknięcie ogłoszenia - podgląd w aplikacji (zakładka Ogłoszenia: ulubione, treść offline). */
    onOpenAnnouncement: (String) -> Unit = {},
    transitViewModel: pl.zse.bydgoszcz.elektron.presentation.transit.TransitViewModel = hiltViewModel(),
    calendarViewModel: pl.zse.bydgoszcz.elektron.presentation.calendar.CalendarViewModel = hiltViewModel(),
    gradesViewModel: pl.zse.bydgoszcz.elektron.presentation.grades.GradesViewModel = hiltViewModel(),
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val visible = pl.zse.bydgoszcz.elektron.presentation.common.LocalScreenVisible.current
    androidx.compose.runtime.DisposableEffect(viewModel, visible) {
        viewModel.setVisible(visible)
        onDispose { viewModel.setVisible(false) }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val transitSettings by transitViewModel.settings.collectAsStateWithLifecycle()
    val upcomingEvents by calendarViewModel.upcomingEvents.collectAsStateWithLifecycle()
    val semesterEvents by calendarViewModel.semesterEvents.collectAsStateWithLifecycle()
    val gradeSummaries by gradesViewModel.subjectSummaries.collectAsStateWithLifecycle()
    val overallGradesAvg by gradesViewModel.overallAverage.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showAttendanceDialog by rememberSaveable { mutableStateOf(false) }
    var showGradesDialog by rememberSaveable { mutableStateOf(false) }
    var showExamsDialog by rememberSaveable { mutableStateOf(false) }
    var showDutyConfigDialog by rememberSaveable { mutableStateOf(false) }

    if (showAttendanceDialog) {
        pl.zse.bydgoszcz.elektron.presentation.attendance.AttendanceGuardianDialog(
            onDismiss = { showAttendanceDialog = false }
        )
    }
    if (showGradesDialog) {
        pl.zse.bydgoszcz.elektron.presentation.grades.GradesDialog(
            onDismiss = { showGradesDialog = false },
            viewModel = gradesViewModel
        )
    }
    if (showExamsDialog) {
        pl.zse.bydgoszcz.elektron.presentation.calendar.ExamsDialog(
            onDismiss = { showExamsDialog = false },
            onOpenDay = { date -> onOpenTimetable(date) },
            viewModel = calendarViewModel
        )
    }
    if (showDutyConfigDialog) {
        DutyConfigDialog(
            initialNumber = state.duty?.studentNumber,
            initialSize = state.duty?.classSize ?: 30,
            onDismiss = { showDutyConfigDialog = false },
            onSave = { num, size ->
                viewModel.setStudentNumber(num)
                viewModel.setClassSize(size)
                showDutyConfigDialog = false
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LargeTitleBar(title = "Strona główna", scrollBehavior = scrollBehavior) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().revealWhen(state.ready),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.selectedClassId == null) {
                    item {
                        InfoCard("Witaj w Elektron-Vulcan",
                            "Nie wybrano jeszcze klasy. Przejdź do Ustawień i wybierz swoją klasę.")
                    }
                } else {
                    state.availableUpdate?.let { update ->
                        item(key = "update") {
                            UpdateBanner(
                                update = update,
                                onLater = { viewModel.dismissUpdate(update.versionName) }
                            )
                        }
                    }
                    state.staleSince?.let { since ->
                        item(key = "stale") { StaleNote(since) }
                    }
                    state.lastSyncError?.let { err ->
                        item(key = "error") {
                            if (pl.zse.bydgoszcz.elektron.domain.model.IncompleteSchoolDataException.isWarning(err)) WarningNote(err)
                            else ErrorBanner(err, syncing = state.isSyncing) { viewModel.refresh() }
                        }
                    }

                    // Tylko gdy widoczny: pusty element listy dokładał odstęp (spacedBy) - zbędna przerwa
                    // nad "Następną lekcją".
                    if (state.isSyncing && !state.isRefreshing && state.lastSyncError == null) {
                        item(key = "syncing") { SyncingIndicator(Modifier.animateItem()) }
                    }

                    if (state.showNextLesson) {
                        val icon = when (state.nextLessonStatus) {
                            DashboardViewModel.LessonStatus.Now -> Icons.Filled.PlayArrow
                            DashboardViewModel.LessonStatus.Break -> Icons.Filled.FreeBreakfast
                            else -> Icons.Filled.Schedule
                        }
                        val title = when (state.nextLessonStatus) {
                            DashboardViewModel.LessonStatus.Now -> "Trwa teraz"
                            DashboardViewModel.LessonStatus.Break -> "Przerwa · następna lekcja"
                            DashboardViewModel.LessonStatus.Next ->
                                state.nextLessonDayLabel?.let { "Następna lekcja · $it" } ?: "Następna lekcja"
                            DashboardViewModel.LessonStatus.None -> "Następna lekcja"
                        }
                        item(key = "h_next") { SectionTitle(title, icon) }
                        item(key = "next") {
                            if (state.loadingTimetable) DelayedLoading { LoadingCard("Ładowanie planu lekcji…") }
                            else NextLessonCard(state) { onOpenTimetable(state.nextLesson?.date) }
                        }
                    }

                    state.duty?.let { duty ->
                        item(key = "duty_card") {
                            DutyCard(duty, onConfigure = { showDutyConfigDialog = true })
                        }
                    }

                    state.overallAttendance?.let { att ->
                        item(key = "attendance_card") {
                            AttendanceGuardianCard(
                                attendance = att,
                                atRiskCount = state.atRiskSubjectsCount,
                                onClick = { showAttendanceDialog = true }
                            )
                        }
                    }

                    item(key = "grades_card") {
                        val totalGradesCount = gradeSummaries.sumOf { it.grades.size }
                        GradesSummaryCard(
                            overallAverage = overallGradesAvg,
                            subjectsCount = gradeSummaries.size,
                            gradesCount = totalGradesCount,
                            onClick = { showGradesDialog = true }
                        )
                    }

                    if (state.todayActivities.isNotEmpty()) {
                        item(key = "today_activities") {
                            TodayActivitiesCard(
                                activities = state.todayActivities,
                                onToggle = { id, comp -> viewModel.toggleActivity(id, comp) },
                                onOpenTimetable = { onOpenTimetable(AppClock.today()) }
                            )
                        }
                    }

                    // Jak wyżej: karta Odjazdów tylko wtedy, gdy ma się pojawić (inaczej pusty odstęp).
                    if (state.showDeparture && transitSettings.ready && !transitSettings.failed && transitSettings.preferences.showOnDashboard) {
                        item(key = "departure") {
                            DashboardDeparture(transitViewModel, eligible = true, onOpenTransit)
                        }
                    }
                    if (state.showSubstitutions) {
                        item(key = "h_subs") {
                            val t = if (state.upcomingSubstitutions.isEmpty()) "Zastępstwa"
                                else "Zastępstwa (${state.upcomingSubstitutions.size})"
                            SectionTitle(t, Icons.Filled.EventBusy)
                        }
                        when {
                            state.loadingSubs -> item(key = "subs_loading") { DelayedLoading { LoadingCard("Ładowanie zastępstw…") } }
                            state.upcomingSubstitutions.isEmpty() -> item(key = "subs_empty") { EmptyCard("Brak zastępstw") }
                            else -> items(state.upcomingSubstitutions, key = { "sub_${it.id}" }) {
                                SubstitutionRow(it, Modifier.animateItem().semantics(mergeDescendants = true) {}, onOpenSubstitutions)
                            }
                        }
                    }
                }

                if (upcomingEvents.isNotEmpty() || semesterEvents.isNotEmpty()) {
                    item(key = "h_events") {
                        val countLabel = if (upcomingEvents.isNotEmpty()) upcomingEvents.size else semesterEvents.size
                        SectionTitle("Sprawdziany, prace klasowe i zadania ($countLabel)", Icons.Outlined.CalendarMonth)
                    }
                    item(key = "events_card") {
                        UpcomingEventsCard(
                            events = upcomingEvents.ifEmpty { semesterEvents.takeLast(5) },
                            totalCount = semesterEvents.size,
                            onOpenDay = { date -> onOpenTimetable(date) },
                            onOpenAll = { showExamsDialog = true }
                        )
                    }
                }

                if (state.showAnnouncements) {
                    if (state.latestAnnouncements.isNotEmpty()) {
                        item(key = "h_anns") { SectionTitle("Najnowsze ogłoszenia", Icons.Filled.Campaign) }
                        items(state.latestAnnouncements, key = { "ann_${it.id}" }) { ann ->
                            AnnouncementRow(ann, Modifier.animateItem()) { onOpenAnnouncement(ann.id) }
                        }
                    } else if (state.loadingAnns) {
                        item(key = "h_anns") { SectionTitle("Najnowsze ogłoszenia", Icons.Filled.Campaign) }
                        item(key = "anns_loading") { DelayedLoading { LoadingCard("Ładowanie ogłoszeń…") } }
                    }
                }
            }
        }
    }
}

/** Baner o nowej wersji aplikacji na GitHubie. */
@Composable
private fun UpdateBanner(update: AppUpdate, onLater: () -> Unit) {
    ElektronCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Dostępna nowa wersja ${update.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(10.dp))
            // Pobieranie i instalacja w aplikacji (wersja z GitHuba), z paskiem postępu.
            UpdateActions(update, onLater, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Subtelna informacja, z kiedy są dane — gdy ostatni udany sync był ponad godzinę temu. */
@Composable
private fun StaleNote(since: java.time.Instant) {
    val zoned = since.atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val day = when (zoned.toLocalDate()) {
        today -> "dziś"
        today.minusDays(1) -> "wczoraj"
        else -> zoned.format(DateTimeFormatter.ofPattern("dd.MM"))
    }
    Text(
        "Dane z $day, ${zoned.format(DateTimeFormatter.ofPattern("HH:mm"))}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp)
    )
}

@Composable
private fun SyncingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Synchronizacja…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingCard(text: String) {
    ElektronCard {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    ElektronCard {
        Text(text, Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorBanner(text: String, syncing: Boolean, onRetry: () -> Unit) {
    val onError = MaterialTheme.colorScheme.onErrorContainer
    ElektronCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = onError)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = onError)
        }
        // W trakcie synchronizacji (np. pierwszej po aktualizacji) karta zostaje na miejscu,
        // ale widać, że sprawdzanie trwa - bez wciskania "Spróbuj ponownie" w ciemno.
        // Przycisk w kolorach karty: tonalny przycisk w akcencie (np. zielonym) gryzł się z czerwienią.
        TextButton(onClick = onRetry, enabled = !syncing,
            colors = ButtonDefaults.textButtonColors(contentColor = onError, disabledContentColor = onError.copy(alpha = 0.6f)),
            modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)) {
            Text(if (syncing) "Sprawdzam…" else "Spróbuj ponownie", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Ostrzeżenie o niepełnych danych szkoły - informacja, nie błąd (ponowienie nic nie zmieni). */
@Composable
private fun WarningNote(text: String) {
    ElektronCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NextLessonCard(state: DashboardViewModel.State, onClick: () -> Unit) {
    val lesson = state.nextLesson
    val sub = lesson?.substitution
    val container = if (sub != null) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.primaryContainer
    val onContainer = if (sub != null) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer
    ElektronCard(containerColor = container, modifier = Modifier.animateContentSize(), onClick = onClick) {
        Column(Modifier.padding(18.dp)) {
            if (lesson == null) {
                Text("Brak nadchodzących lekcji", style = MaterialTheme.typography.bodyLarge,
                    color = onContainer)
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        val personal = LocalPersonalization.current
                        val subjectName = lesson.groups.mapNotNull { g -> personal.subjectName(g.subject) }
                            .distinct().joinToString(" / ").ifBlank { null }
                        if (sub != null) {
                            // Zastępstwo: na pierwszym planie informacja szkoły, a bez niej nauczyciel zastępujący; potem sala i uwagi.
                            Text(SubstitutionDisplay.headline(sub, infoFirst = true), style = MaterialTheme.typography.headlineSmall,
                                color = onContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(listOfNotNull("${lesson.timeFrom}–${lesson.timeTo}", SubstitutionDisplay.place(sub, infoFirst = true)).joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge, color = onContainer)
                            Text("Za: " + listOfNotNull(subjectName, sub.originalTeacher).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium, color = onContainer.copy(alpha = 0.8f))
                            SubstitutionDisplay.notes(sub)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                    color = onContainer)
                            }
                        } else {
                            Text(subjectName ?: "Lekcja", style = MaterialTheme.typography.headlineSmall,
                                color = onContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            val room = lesson.groups.firstOrNull()?.room ?: "—"
                            Text("${lesson.timeFrom}–${lesson.timeTo} · sala $room",
                                style = MaterialTheme.typography.bodyLarge, color = onContainer)
                            val teacher = lesson.groups.firstOrNull()?.teacherFullName
                                ?: lesson.groups.firstOrNull()?.teacherCode
                            teacher?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium,
                                    color = onContainer.copy(alpha = 0.8f))
                            }
                        }
                    }
                    LessonNumberBadge(lesson.number, onContainer)
                }
                if (state.lessonProgress != null && state.minutesLeft != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.lessonProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = onContainer,
                        trackColor = onContainer.copy(alpha = 0.15f),
                        drawStopIndicator = {}
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (state.nextLessonStatus == DashboardViewModel.LessonStatus.Break) "Lekcja za ${state.minutesLeft} min"
                        else "Zostało ${state.minutesLeft} min",
                        style = MaterialTheme.typography.labelLarge, color = onContainer
                    )
                }
                state.countdownMinutes?.let { mins ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = onContainer.copy(alpha = 0.12f)
                    ) {
                        Text("Za $mins min",
                            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge, color = onContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonNumberBadge(number: Int, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.12f)) {
        Text("$number", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun SubstitutionRow(
    s: Substitution,
    modifier: Modifier = Modifier.semantics(mergeDescendants = true) {},
    onClick: () -> Unit = {}
) {
    val fmt = DateTimeFormatter.ofPattern("dd.MM")
    ElektronCard(modifier = modifier, onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.lessonNumber}", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(s.date.format(fmt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // Nagłówek: informacja szkoły, a bez niej nauczyciel zastępujący; pod nim sala albo nauczyciel.
                Text(SubstitutionDisplay.headline(s, infoFirst = true), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                SubstitutionDisplay.place(s, infoFirst = true)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val subject = LocalPersonalization.current.subjectName(s.originalSubject)
                Text("Za: " + listOfNotNull(subject, s.originalTeacher).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SubstitutionDisplay.notes(s)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    ElektronCard {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnnouncementRow(a: Announcement, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("d.MM.yyyy")
    val date = a.publishedAt.atZone(ZoneId.systemDefault()).toLocalDate().format(fmt)
    ElektronCard(modifier = modifier, onClick = onClick) {
        pl.zse.bydgoszcz.elektron.presentation.common.SafeUrls.schoolImage(a.coverImageUrl)?.let { url ->
            // Pełna szerokość, proporcje 16:9, przycięcie — dawniej ContentScale.Fit
            // zostawiał puste pasy po bokach obrazka.
            // Łagodne pojawienie się obrazka + tło zamiast pustego miejsca podczas wczytywania.
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(250).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
        Column(Modifier.padding(16.dp)) {
            Text(date, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            a.excerpt?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UpcomingEventsCard(
    events: List<pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent>,
    totalCount: Int = events.size,
    onOpenDay: (java.time.LocalDate) -> Unit,
    onOpenAll: () -> Unit = {}
) {
    // Zawsze pokazuj sprawdziany, prace klasowe i kartkówki w pierwszej kolejności,
    // żeby nie zostały wypchnięte przez dużą liczbę zadań domowych:
    val shownEvents = remember(events) {
        val examsAndQuizzes = events.filter {
            it.type == pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType.EXAM ||
                it.type == pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType.QUIZ
        }.take(6)
        val remainingSlots = (8 - examsAndQuizzes.size).coerceAtLeast(2)
        val others = events.filter {
            it.type != pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType.EXAM &&
                it.type != pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType.QUIZ
        }.take(remainingSlots)
        (examsAndQuizzes + others).distinctBy { it.id }.sortedBy { it.date }.take(8)
    }

    ElektronCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            val plLocale = java.util.Locale("pl", "PL")
            val dateFmt = DateTimeFormatter.ofPattern("EEEE, dd.MM", plLocale)
            val today = java.time.LocalDate.now()

            shownEvents.forEachIndexed { index, ev ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                val isToday = ev.date == today
                val isTomorrow = ev.date == today.plusDays(1)
                val dayLabel = when {
                    isToday -> "Dziś"
                    isTomorrow -> "Jutro"
                    else -> ev.date.format(dateFmt).replaceFirstChar { it.titlecase(plLocale) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().pressable { onOpenDay(ev.date) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val evColor = if (ev.type == pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType.EXAM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    val icon = if (ev.type == pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType.EXAM) "📝" else if (ev.type == pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType.QUIZ) "📄" else "📌"

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$icon ${ev.badgeLabel()}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = evColor
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                dayLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            ev.subject?.takeIf { it.isNotBlank() } ?: ev.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ev.description?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    ev.timeFrom?.let { time ->
                        Text(
                            time.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable { onOpenAll() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pokaż wszystkie sprawdziany, prace klasowe i zadania",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "($totalCount) ›",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DutyCard(
    duty: pl.zse.bydgoszcz.elektron.domain.model.ClassroomDuty,
    onConfigure: () -> Unit
) {
    val isToday = duty.isDutyToday
    val containerBg = if (isToday) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isToday) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    ElektronCard(containerColor = containerBg) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isToday) "🧹 Dzisiaj jesteś dyżurnym!" else "🧹 Dyżurny klasowy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                TextButton(onClick = onConfigure) {
                    Text(if (duty.studentNumber == null) "Ustaw numer" else "Zmień #${duty.studentNumber}")
                }
            }

            if (isToday) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pamiętaj o obowiązkach dyżurnego:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                duty.duties.forEach { dutyText ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "• $dutyText",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.9f)
                    )
                }
            } else if (duty.studentNumber != null) {
                val statusText = when {
                    duty.isDutyThisWeek -> "Twoja kolej w tym tygodniu!"
                    duty.daysUntilDuty != null -> "Twój kolejny dyżur za ok. ${duty.daysUntilDuty} dni."
                    else -> "Aktualni dyżurni: #${duty.currentDutyNumber1} i #${duty.currentDutyNumber2}"
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
                Text(
                    "Aktualni dyżurni w tym tygodniu: #${duty.currentDutyNumber1} oraz #${duty.currentDutyNumber2}",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.75f)
                )
            } else {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Ustaw swój numer w dzienniku, aby wiedzieć kiedy przypada Twój dyżur w klasie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun AttendanceGuardianCard(
    attendance: pl.zse.bydgoszcz.elektron.domain.model.OverallAttendance,
    atRiskCount: Int,
    onClick: () -> Unit
) {
    val pct = attendance.percentage
    val color = when {
        pct < 50f -> MaterialTheme.colorScheme.error
        pct < 65f -> Color(0xFFF57C00)
        else -> Color(0xFF2E7D32)
    }

    ElektronCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Strażnik Frekwencji",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    String.format(java.util.Locale.US, "%.1f%%", pct),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (pct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                drawStopIndicator = {}
            )

            Spacer(Modifier.height(8.dp))
            val safeHours = attendance.maxMissableHours(0.50f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (atRiskCount > 0) {
                    Text(
                        "⚠️ Zagrożone przedmioty: $atRiskCount (<50%)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Dopuszczalne opuszczenia: $safeHours h",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Szczegóły ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun GradesSummaryCard(
    overallAverage: Double?,
    subjectsCount: Int,
    gradesCount: Int,
    onClick: () -> Unit
) {
    ElektronCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🎓 Oceny i średnia (eduVULCAN)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = overallAverage?.let { String.format(java.util.Locale.US, "Śr: %.2f", it) } ?: "Importuj",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (gradesCount > 0) {
                        "Zaimportowano $gradesCount ocen z $subjectsCount przedmiotów"
                    } else {
                        "Dotknij, aby zaimportować oceny z dziennika eduVULCAN"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Otwórz ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TodayActivitiesCard(
    activities: List<pl.zse.bydgoszcz.elektron.domain.model.UserActivity>,
    onToggle: (String, Boolean) -> Unit,
    onOpenTimetable: () -> Unit
) {
    ElektronCard(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onOpenTimetable
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Twoje plany na dziś (${activities.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Plan lekcji ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(6.dp))
            activities.take(3).forEach { act ->
                Spacer(Modifier.height(3.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = act.isCompleted,
                        onCheckedChange = { onToggle(act.id, it) },
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${act.category.icon} ${act.title}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (act.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        color = if (act.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    act.timeRangeText?.let { time ->
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DutyConfigDialog(
    initialNumber: Int?,
    initialSize: Int,
    onDismiss: () -> Unit,
    onSave: (Int?, Int) -> Unit
) {
    var numberStr by remember { mutableStateOf(initialNumber?.toString().orEmpty()) }
    var sizeStr by remember { mutableStateOf(initialSize.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ustawienia dyżurnego klasowego") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = numberStr,
                    onValueChange = { numberStr = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Twój numer w dzienniku") },
                    placeholder = { Text("np. 14") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sizeStr,
                    onValueChange = { sizeStr = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Liczba uczniów w klasie") },
                    placeholder = { Text("30") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val num = numberStr.toIntOrNull()
                val size = sizeStr.toIntOrNull() ?: 30
                onSave(num, size)
            }) {
                Text("Zapisz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anuluj")
            }
        }
    )
}

