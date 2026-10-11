package pl.zse.bydgoszcz.elektron.presentation.transit

import pl.zse.bydgoszcz.elektron.domain.util.AppClock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.zse.bydgoszcz.elektron.domain.model.*
import pl.zse.bydgoszcz.elektron.presentation.common.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val transitTime = DateTimeFormatter.ofPattern("HH:mm")
private val transitZone = ZoneId.of("Europe/Warsaw")
internal fun formatTransitTime(timeMs: Long, referenceMs: Long = AppClock.millis()): String {
    val time = Instant.ofEpochMilli(timeMs).atZone(transitZone)
    val today = Instant.ofEpochMilli(referenceMs).atZone(transitZone).toLocalDate()
    val prefix = when (time.toLocalDate()) {
        today -> ""
        today.plusDays(1) -> "jutro "
        else -> time.format(DateTimeFormatter.ofPattern("d.MM")) + " "
    }
    return prefix + time.format(transitTime)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitScreen(viewModel: TransitViewModel, onClose: (() -> Unit)? = null) {
    val state by viewModel.settings.collectAsStateWithLifecycle()
    val result by viewModel.journeys.collectAsStateWithLifecycle()
    val loading by viewModel.journeyLoading.collectAsStateWithLifecycle()
    val moreLoading by viewModel.moreLoading.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableIntStateOf(0) }
    var visibleCount by rememberSaveable(state.preferences.destination?.key, state.preferences.preferredOrigin?.key, state.preferences.allowTransfers) { mutableIntStateOf(3) }
    val error by viewModel.journeyError.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val settingsError by viewModel.settingsError.collectAsStateWithLifecycle()
    val visible = LocalScreenVisible.current
    val requestedCount by rememberUpdatedState(visibleCount)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(visible, state.preferences.destination?.key, state.preferences.preferredOrigin?.key, state.preferences.allowTransfers, owner) {
        val active = visible
        val consumer = Any()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && active) viewModel.startJourneySession(consumer, minimumCount = requestedCount)
            if (event == Lifecycle.Event.ON_STOP) viewModel.stopJourneySession(consumer)
        }
        owner.lifecycle.addObserver(observer)
        if (active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.startJourneySession(consumer, minimumCount = requestedCount)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.stopJourneySession(consumer) }
    }
    val tick by rememberNow(60_000L)
    LaunchedEffect(visible, tick, state.preferences.destination?.key, state.preferences.preferredOrigin?.key, state.preferences.allowTransfers) {
        if (visible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.refreshJourneys(requestedCount)
    }
    val nowMs = remember(tick) { AppClock.millis() }
    val matching = result?.takeIf { it.destinationKey == state.preferences.destination?.key && it.originKey == state.preferences.preferredOrigin?.key && it.allowTransfers == state.preferences.allowTransfers }
    val journeys = remember(matching, nowMs, state.preferences.preferredOrigin, state.preferences.allowTransfers) {
        TransitJourneys.upcoming(matching?.journeys.orEmpty(), nowMs, state.preferences.preferredOrigin, state.preferences.allowTransfers)
    }
    val editable = state.ready && !state.failed && !saving
    val canRefresh = !loading && !moreLoading && editable && state.preferences.destination != null
    // Planer bywa wolny (każde stanowisko to osobne zapytanie) - po kilku sekundach wyjaśnienie zamiast samego paska.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(loading) { slow = false; if (loading) { kotlinx.coroutines.delay(6_000); slow = true } }
    // Zwarty pasek zamiast dużego tytułu: trasa i pierwsze połączenia mieszczą się na jednym ekranie.
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Odjazdy", modifier = androidx.compose.ui.Modifier.semantics { heading() }) },
            navigationIcon = { if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Zamknij Odjazdy") } },
            actions = {
                if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { visibleCount = 3; viewModel.loadJourneys(force = true, minimumCount = 3) }, enabled = canRefresh) {
                    Icon(Icons.Filled.Refresh, "Odśwież połączenia")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
    },
        // Źródło tras pieszych zawsze na dole ekranu, a nie w środku krótkiej listy.
        bottomBar = {
            val context = LocalContext.current
            Text("Trasy piesze: © OpenStreetMap · FOSSGIS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().pressable { SafeUrls.open(context, "https://www.openstreetmap.org/copyright") }
                    .padding(vertical = 8.dp))
        },
        containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "route") {
                RouteCard(
                    origin = state.preferences.preferredOrigin?.name,
                    destination = state.preferences.destination?.name,
                    enabled = editable,
                    onOrigin = { viewModel.query.value = ""; picker = 2 },
                    onResetOrigin = { viewModel.selectOrigin(null) },
                    onDestination = { viewModel.query.value = ""; picker = 1 },
                    onClearDestination = viewModel::clear
                )
            }
            matching?.let { data -> item(key = "checked") {
                Text("Sprawdzono ${formatTransitTime(data.fetchedAt, nowMs)}" + if (state.preferences.allowTransfers) " · z przesiadkami" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp))
            } }
            if (state.failed) item { Text("Nie udało się odczytać ustawień Odjazdów.", color = MaterialTheme.colorScheme.error) }
            settingsError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.preferences.destination == null && !state.failed) item {
                Text("Wybierz przystanek docelowy, aby sprawdzić najbliższe połączenia ze szkoły.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) item(key = "loading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (slow) "Planer odpowiada wolniej niż zwykle - sprawdzanie wszystkich przystanków może potrwać do minuty."
                        else "Szukam połączeń…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (error != null) item { Text(error!! + if (matching != null) " Wyświetlam zapisane wyniki; rozkład mógł się zmienić." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (matching?.partialFailure == true) item {
                Text("Nie udało się sprawdzić części stanowisk. Lista może być niepełna; odśwież wyniki, aby ponowić.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!loading && error == null && matching != null && journeys.isEmpty()) item {
                Text("Nie znaleziono połączenia do wybranego przystanku w sprawdzonym przedziale. Odśwież wyniki lub wybierz inny cel.")
            }
            // Animacje: kolejne odjazdy ("Pokaż więcej", wyniki cząstkowe) pojawiają się płynnie,
            // a przycisk zjeżdża w dół zamiast przeskakiwać.
            itemsIndexed(journeys.take(visibleCount), key = { _, trip -> trip.key }) { index, trip ->
                Box(Modifier.animateItem()) {
                    JourneyCard(trip, nowMs, if (index == 0) "Najbliższy" else "")
                }
            }
            if (journeys.size > visibleCount || matching?.nextWhenMs != null) item(key = "more") {
                TextButton(onClick = {
                    visibleCount = minOf(visibleCount, journeys.size) + 3
                    if (journeys.size < visibleCount && matching?.nextWhenMs != null) viewModel.loadJourneys(more = true, minimumCount = visibleCount)
                }, enabled = !loading && !moreLoading, modifier = Modifier.fillMaxWidth().animateItem()) {
                    AnimatedVisibility(visible = moreLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Text(if (moreLoading) "Wczytywanie…" else "Pokaż więcej")
                }
            }
        }
    }
    if (picker != 0) StopPicker(viewModel, origin = picker == 2) { picker = 0 }

}

/**
 * Trasa jak w planerach (Jakdojade): skąd i dokąd w dwóch wierszach - zamiast dużej karty ustawień
 * nad wynikami. Przesiadki włącza się w Ustawieniach -> Odjazdy.
 */
@Composable
private fun RouteCard(
    origin: String?, destination: String?, enabled: Boolean,
    onOrigin: () -> Unit, onResetOrigin: () -> Unit, onDestination: () -> Unit, onClearDestination: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    ElektronCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            RoutePoint(label = "Odjazd z", value = origin ?: "Przystanek najbliżej szkoły z odjazdem",
                placeholder = origin == null, enabled = enabled, onClick = onOrigin,
                trailing = if (origin != null) ({ IconButton(onClick = onResetOrigin, enabled = enabled) { Icon(Icons.Filled.Close, "Wróć do przystanku najbliżej szkoły") } }) else null)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
            RoutePoint(label = "Dokąd", value = destination ?: "Wybierz przystanek docelowy",
                placeholder = destination == null, enabled = enabled, onClick = onDestination,
                trailing = if (destination != null) ({ IconButton(onClick = onClearDestination, enabled = enabled) { Icon(Icons.Filled.Close, "Usuń przystanek docelowy") } }) else null)
        }
    }
}

@Composable
private fun RoutePoint(label: String, value: String, placeholder: Boolean, enabled: Boolean,
    onClick: () -> Unit, trailing: (@Composable () -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().pressable(enabled = enabled, onClickLabel = "Zmień: $label", onClick = onClick)
        .heightIn(min = 56.dp).padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (placeholder) colors.onSurfaceVariant else colors.onSurface)
        }
        trailing?.invoke()
    }
}

/** Połączenie: linia i kierunek, przystanek z dojściem, a po prawej duża godzina odjazdu. */
@Composable
internal fun JourneyCard(trip: TransitJourney, nowMs: Long, label: String, onClick: (() -> Unit)? = null) {
    val first = trip.rides.first()
    val colors = MaterialTheme.colorScheme
    ElektronCard(onClick = onClick) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                RideDirection(first.line, first.direction)
                Text(first.fromName, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                Text(if (trip.walkAvailable) "Dojście ok. ${TransitTimes.duration(trip.walkMinutes.toLong())}" else if (trip.walkPending) "Sprawdzam dojście…" else "Dojście niedostępne",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            // Najważniejsze "za ile" - duże; dokładna godzina pod spodem.
            Column(horizontalAlignment = Alignment.End) {
                Text(TransitTimes.until(trip.departureMs, nowMs), style = MaterialTheme.typography.headlineSmall, color = colors.primary, maxLines = 1)
                Text(formatTransitTime(first.departureMs, nowMs), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        if (trip.transfers > 0) {
            val second = trip.rides[1]
            HorizontalDivider(color = colors.outlineVariant)
            Text("Przesiadka: ${first.toName}" + if (first.toName != second.fromName) " → ${second.fromName}" else "",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { RideDirection(second.line, second.direction) }
                Text(formatTransitTime(second.departureMs, nowMs), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            }
        }
        Text("Przyjazd ${formatTransitTime(trip.arrivalMs, nowMs)} · ${trip.rides.last().toName}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    } }
}

@Composable
private fun RideDirection(line: String, direction: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
            Text(line, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Text(direction, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
