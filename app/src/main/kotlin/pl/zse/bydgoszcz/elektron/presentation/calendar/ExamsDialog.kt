package pl.zse.bydgoszcz.elektron.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class EventCategoryFilter(val label: String) {
    ALL("Wszystkie"),
    EXAMS("Sprawdziany i prace klasowe"),
    QUIZZES("Kartkówki"),
    HOMEWORK("Zadania domowe")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsDialog(
    onDismiss: () -> Unit,
    onOpenDay: ((LocalDate) -> Unit)? = null,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val semesterEvents by viewModel.semesterEvents.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val vulcanAccount by viewModel.vulcanAccount.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val lastSyncError by viewModel.lastSyncError.collectAsStateWithLifecycle()

    var selectedFilter by rememberSaveable { mutableStateOf(EventCategoryFilter.ALL) }
    var showPastEvents by rememberSaveable { mutableStateOf(false) }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }

    val today = LocalDate.now()

    val timeFilteredEvents = remember(semesterEvents, showPastEvents, today) {
        if (showPastEvents) {
            semesterEvents.sortedBy { it.date }
        } else {
            semesterEvents.filter { !it.date.isBefore(today) }.sortedBy { it.date }
        }
    }

    val examsCount = remember(timeFilteredEvents) {
        timeFilteredEvents.count { it.type == SchoolEventType.EXAM }
    }
    val quizzesCount = remember(timeFilteredEvents) {
        timeFilteredEvents.count { it.type == SchoolEventType.QUIZ }
    }
    val homeworkCount = remember(timeFilteredEvents) {
        timeFilteredEvents.count { it.type == SchoolEventType.HOMEWORK }
    }

    val displayedEvents = remember(timeFilteredEvents, selectedFilter) {
        when (selectedFilter) {
            EventCategoryFilter.ALL -> timeFilteredEvents
            EventCategoryFilter.EXAMS -> timeFilteredEvents.filter { it.type == SchoolEventType.EXAM }
            EventCategoryFilter.QUIZZES -> timeFilteredEvents.filter { it.type == SchoolEventType.QUIZ }
            EventCategoryFilter.HOMEWORK -> timeFilteredEvents.filter { it.type == SchoolEventType.HOMEWORK }
        }
    }

    if (showLoginDialog) {
        EduVulcanLoginDialog(
            onDismiss = { showLoginDialog = false },
            onSessionConnected = { session ->
                showLoginDialog = false
                viewModel.connectEduVulcanSession(session)
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "Sprawdziany i terminarz",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (vulcanAccount != null) "eduVULCAN • ${vulcanAccount?.pupilName}"
                                else "Niepołączono z dziennikiem eduVULCAN",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Zamknij")
                        }
                    },
                    actions = {
                        if (vulcanAccount != null) {
                            IconButton(
                                onClick = { viewModel.sync() },
                                enabled = !isSyncing
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Synchronizuj sprawdziany i zadania"
                                    )
                                }
                            }
                        } else {
                            TextButton(onClick = { showLoginDialog = true }) {
                                Text("Zaloguj")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Pasek statystyk
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatBadge(
                        modifier = Modifier.weight(1f),
                        icon = "📝",
                        label = "Sprawdziany / Prace kl.",
                        count = examsCount,
                        highlight = examsCount > 0,
                        isErrorTint = true
                    )
                    StatBadge(
                        modifier = Modifier.weight(1f),
                        icon = "📄",
                        label = "Kartkówki",
                        count = quizzesCount,
                        highlight = quizzesCount > 0,
                        isErrorTint = false
                    )
                    StatBadge(
                        modifier = Modifier.weight(1f),
                        icon = "📌",
                        label = "Zadania domowe",
                        count = homeworkCount,
                        highlight = false,
                        isErrorTint = false
                    )
                }

                // Filtry kategorii + przełącznik "Pokaż też minione"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EventCategoryFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label) }
                        )
                    }
                    FilterChip(
                        selected = showPastEvents,
                        onClick = { showPastEvents = !showPastEvents },
                        label = { Text(if (showPastEvents) "Cały semestr" else "Tylko nadchodzące") }
                    )
                }

                userMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.consumeUserMessage() }) {
                                Text("OK")
                            }
                        }
                    }
                }

                lastSyncError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

                if (displayedEvents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (!showPastEvents && semesterEvents.isNotEmpty()) {
                                    "Brak nadchodzących wpisów w tej kategorii."
                                } else {
                                    "Brak zaimportowanych wpisów w tej kategorii."
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!showPastEvents && semesterEvents.isNotEmpty()) {
                                TextButton(onClick = { showPastEvents = true }) {
                                    Text("Pokaż wszystkie wpisy z semestru (${semesterEvents.size})")
                                }
                            } else if (vulcanAccount != null) {
                                TextButton(
                                    onClick = { viewModel.sync() },
                                    enabled = !isSyncing
                                ) {
                                    Text("Synchronizuj teraz z eduVULCAN")
                                }
                            } else {
                                TextButton(onClick = { showLoginDialog = true }) {
                                    Text("Zaloguj się do eduVULCAN")
                                }
                            }
                        }
                    }
                } else {
                    val plLocale = Locale("pl", "PL")
                    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", plLocale) }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayedEvents, key = { it.id }) { ev ->
                            ExamEventItemCard(
                                event = ev,
                                today = today,
                                dateFmt = dateFmt,
                                plLocale = plLocale,
                                onClick = onOpenDay?.let { openDay ->
                                    {
                                        onDismiss()
                                        openDay(ev.date)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(
    modifier: Modifier = Modifier,
    icon: String,
    label: String,
    count: Int,
    highlight: Boolean,
    isErrorTint: Boolean
) {
    val bgColor = when {
        highlight && isErrorTint -> MaterialTheme.colorScheme.errorContainer
        highlight -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        highlight && isErrorTint -> MaterialTheme.colorScheme.onErrorContainer
        highlight -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$icon $count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExamEventItemCard(
    event: SchoolEvent,
    today: LocalDate,
    dateFmt: DateTimeFormatter,
    plLocale: Locale,
    onClick: (() -> Unit)?
) {
    val isExam = event.type == SchoolEventType.EXAM
    val isQuiz = event.type == SchoolEventType.QUIZ
    val isPast = event.date.isBefore(today)

    val badgeColor = when {
        isExam -> MaterialTheme.colorScheme.error
        isQuiz -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val icon = when (event.type) {
        SchoolEventType.EXAM -> "📝"
        SchoolEventType.QUIZ -> "📄"
        SchoolEventType.HOMEWORK -> "📌"
        SchoolEventType.OTHER -> "📅"
    }

    val dayPrefix = when (event.date) {
        today -> "Dzisiaj • "
        today.plusDays(1) -> "Jutro • "
        today.minusDays(1) -> "Wczoraj • "
        else -> ""
    }
    val formattedDate = dayPrefix + event.date.format(dateFmt).replaceFirstChar { it.titlecase(plLocale) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isPast) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = badgeColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "$icon ${event.badgeLabel()}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (event.date == today) badgeColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = event.subject?.takeIf { it.isNotBlank() } ?: event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            event.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
