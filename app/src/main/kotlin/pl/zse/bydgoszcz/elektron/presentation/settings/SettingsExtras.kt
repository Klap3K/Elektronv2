package pl.zse.bydgoszcz.elektron.presentation.settings

import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.Slider
import pl.zse.bydgoszcz.elektron.domain.model.SchoolClass
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.zse.bydgoszcz.elektron.domain.model.LessonReminders
import pl.zse.bydgoszcz.elektron.domain.model.QuietHours
import pl.zse.bydgoszcz.elektron.domain.model.ReminderMode
import pl.zse.bydgoszcz.elektron.domain.model.ReminderSettings
import pl.zse.bydgoszcz.elektron.domain.model.StartScreen
import pl.zse.bydgoszcz.elektron.domain.model.WidgetLook
import pl.zse.bydgoszcz.elektron.presentation.common.GroupedSection
import pl.zse.bydgoszcz.elektron.presentation.common.pressable
import pl.zse.bydgoszcz.elektron.presentation.common.BackgroundWork
import pl.zse.bydgoszcz.elektron.presentation.common.SafeUrls
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import pl.zse.bydgoszcz.elektron.work.LessonReminderScheduler
import java.time.LocalTime

// Sekcje Ustawień dodane w 0.6: ekran startowy, przypomnienia, ciche godziny, widżety.

private val START_OPTIONS = listOf(
    StartScreen.SMART to "Automatycznie",
    StartScreen.DASHBOARD to "Strona główna",
    StartScreen.TIMETABLE to "Plan lekcji",
    StartScreen.SUBSTITUTIONS to "Zastępstwa"
)

private val REMINDER_OPTIONS = listOf(
    ReminderMode.OFF to "Wyłączone",
    ReminderMode.FIRST to "Przed pierwszą lekcją",
    ReminderMode.EVERY to "Przed każdą lekcją"
)

/** Strona główna: ekran startowy aplikacji i karty widoczne na Starcie. */
@Composable
internal fun HomeSection(current: StartScreen, transitTab: Boolean, onChange: (StartScreen) -> Unit, cards: @Composable () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val options = START_OPTIONS + if (transitTab) listOf(StartScreen.TRANSIT to "Odjazdy") else emptyList()
    // Odjazdy wybrane, a zakładka wyłączona - aplikacja otworzy stronę główną.
    val shown = if (current == StartScreen.TRANSIT && !transitTab) "Strona główna" else options.firstOrNull { it.first == current }?.second ?: "Automatycznie"
    GroupedSection("Strona główna", icon = Icons.Outlined.Home) {
        ValueRow("Ekran startowy", shown,
            supporting = if (current == StartScreen.SMART) "W godzinach lekcji plan, poza nimi strona główna" else null) { choosing = true }
        RowDivider()
        cards()
    }
    if (choosing) {
        ChoiceDialog("Ekran startowy", options, current,
            onSelect = { onChange(it); choosing = false }, onDismiss = { choosing = false })
    }
}

/**
 * Odjazdy: zakładka i karta na stronie głównej są niezależne i domyślnie wyłączone;
 * przystanki można wybrać tutaj, bez otwierania zakładki.
 */
@Composable
internal fun TransitSection(
    settings: pl.zse.bydgoszcz.elektron.presentation.transit.TransitViewModel.Settings,
    saving: Boolean, error: String?,
    onTab: (Boolean) -> Unit, onCard: (Boolean) -> Unit, onTransfers: (Boolean) -> Unit,
    onPick: (origin: Boolean) -> Unit
) {
    val prefs = settings.preferences
    val ready = settings.ready && !settings.failed && !saving
    GroupedSection("Odjazdy", icon = Icons.Outlined.DirectionsBus,
        footer = if (prefs.showOnDashboard) "Karta pojawia się od początku ostatniej lekcji do godziny po zajęciach." else null) {
        SwitchRow("Zakładka Odjazdy", prefs.visible, enabled = ready, supporting = "Na dolnym pasku", onChange = onTab)
        RowDivider()
        SwitchRow("Karta na stronie głównej", prefs.showOnDashboard, enabled = ready, supporting = "Najbliższe połączenie po lekcjach", onChange = onCard)
        if (prefs.visible || prefs.showOnDashboard) {
            RowDivider()
            ValueRow("Dokąd", prefs.destination?.name ?: "Wybierz", enabled = ready) { onPick(false) }
            RowDivider()
            ValueRow("Odjazd z", prefs.preferredOrigin?.name ?: "Najbliższy", enabled = ready) { onPick(true) }
            RowDivider()
            SwitchRow("Połączenia z przesiadką", prefs.allowTransfers, enabled = ready, onChange = onTransfers)
        }
        if (settings.failed) Text("Nie udało się odczytać ustawień Odjazdów.", modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Powiadomienia o nowościach i ciche godziny w jednym miejscu. */
@Composable
internal fun NotificationsSection(
    substitutions: Boolean, announcements: Boolean, quiet: QuietHours,
    autoDnd: Boolean = false,
    onSubs: (Boolean) -> Unit, onAnnouncements: (Boolean) -> Unit, onQuiet: (QuietHours) -> Unit,
    onAutoDnd: (Boolean) -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dndGranted by remember { mutableStateOf(pl.zse.bydgoszcz.elektron.service.AutoDndManager.isDndPermissionGranted(ctx)) }
    OnResume { dndGranted = pl.zse.bydgoszcz.elektron.service.AutoDndManager.isDndPermissionGranted(ctx) }
    var picking by remember { mutableStateOf<Boolean?>(null) }   // true = od, false = do
    GroupedSection("Powiadomienia i dźwięk", icon = androidx.compose.material.icons.Icons.Outlined.Notifications) {
        SystemNotificationStatus()
        SwitchRow("Nowe zastępstwa", substitutions, supporting = "Zmiany w planie Twojej klasy", onChange = onSubs)
        RowDivider()
        SwitchRow("Nowe ogłoszenia", announcements, supporting = "Wpisy na stronie szkoły", onChange = onAnnouncements)
        RowDivider()
        SwitchRow(
            "Wyciszanie na lekcjach (Auto DND)",
            autoDnd,
            supporting = "Automatycznie wycisza telefon w trakcie lekcji i przywraca dźwięk na przerwach",
            onChange = onAutoDnd
        )
        if (autoDnd && !dndGranted) {
            RowDivider()
            ActionRow(
                "Zezwól na sterowanie trybem Nie Przeszkadzać",
                supporting = "Dotknij, aby nadać uprawnienie w ustawieniach systemu",
                trailingChevron = true
            ) {
                pl.zse.bydgoszcz.elektron.service.AutoDndManager.openDndSettings(ctx)
            }
        }
        RowDivider()
        SwitchRow("Ciche godziny", quiet.enabled, supporting = "Bez dźwięku i wibracji - nic nie ginie") { onQuiet(quiet.copy(enabled = it)) }
        if (quiet.enabled) {
            RowDivider()
            ValueRow("Od", quiet.from.toString()) { picking = true }
            RowDivider()
            ValueRow("Do", quiet.to.toString()) { picking = false }
        }
    }
    picking?.let { from ->
        TimeDialog(
            title = if (from) "Ciche godziny od" else "Ciche godziny do",
            initial = if (from) quiet.from else quiet.to,
            onPick = { t -> onQuiet(if (from) quiet.copy(from = t) else quiet.copy(to = t)); picking = null },
            onDismiss = { picking = null }
        )
    }
}

/** Praca w tle: stan sprawdzany przy każdym powrocie na ekran (zmiana w ustawieniach systemu). */
@Composable
internal fun BackgroundSection() {
    val ctx = LocalContext.current
    var unrestricted by remember { mutableStateOf(BackgroundWork.isUnrestricted(ctx)) }
    OnResume { unrestricted = BackgroundWork.isUnrestricted(ctx) }
    GroupedSection(
        "Działanie w tle",
        icon = Icons.Outlined.BatteryChargingFull,
        footer = if (unrestricted) null
        else "Telefon może usypiać aplikację w tle - widżety przestają się wtedy odświeżać, " +
            "a powiadomienia przychodzą z opóźnieniem. Ustaw eLektron na „Bez ograniczeń”."
    ) {
        if (unrestricted) {
            ValueRow("Optymalizacja baterii", "Wyłączona", supporting = "Widżety i powiadomienia działają bez ograniczeń", onClick = null)
        } else {
            ActionRow("Wyłącz optymalizację baterii", trailingChevron = true) { BackgroundWork.openSettings(ctx) }
            RowDivider()
            ActionRow("Poradnik dla Twojego telefonu", trailingIcon = true) { SafeUrls.open(ctx, BackgroundWork.GUIDE_URL) }
        }
    }
}

@Composable
internal fun RemindersSection(current: ReminderSettings, status: LessonReminderScheduler.Status,
    onResume: () -> Unit, onChange: (ReminderSettings) -> Unit) {
    val ctx = LocalContext.current
    var choosingMode by remember { mutableStateOf(false) }
    var choosingMinutes by remember { mutableStateOf(false) }
    // Zgoda na dokładne alarmy i na powiadomienia - sprawdzane przy każdym powrocie z ustawień systemu.
    var exact by remember { mutableStateOf(LessonReminderScheduler.canUseExactAlarms(ctx)) }
    var notificationsOn by remember { mutableStateOf(reminderNotificationsEnabled(ctx)) }
    OnResume {
        notificationsOn = reminderNotificationsEnabled(ctx)
        onResume()
        exact = LessonReminderScheduler.canUseExactAlarms(ctx)
    }

    val enabled = current.mode != ReminderMode.OFF
    GroupedSection("Przypomnienia o lekcjach", icon = Icons.Outlined.Alarm) {
        ValueRow("Przypomnienia", REMINDER_OPTIONS.first { it.first == current.mode }.second,
            supporting = if (!enabled) "Powiadomienie z salą i informacją o zastępstwie" else null) { choosingMode = true }
        if (enabled) {
            RowDivider()
            ValueRow("Ile wcześniej", "${current.minutesBefore} min") { choosingMinutes = true }
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(when {
                    !notificationsOn -> "Powiadomienia zablokowane w systemie"
                    status.error -> "Nie udało się ustawić przypomnienia"
                    !status.ready -> "Sprawdzam…"
                    status.at != null -> "Najbliższe: ${status.at.format(java.time.format.DateTimeFormatter.ofPattern("d.MM, HH:mm"))}"
                    else -> "Brak nadchodzącej lekcji w zapisanym planie na najbliższe 7 dni"
                }, style = MaterialTheme.typography.bodyMedium)
                if (notificationsOn && !status.error && status.at != null) {
                    status.lesson?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (notificationsOn && !exact) {
                    Text("System może opóźnić przypomnienie bez zgody na dokładne alarmy.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!notificationsOn) {
                RowDivider()
                ActionRow("Włącz powiadomienia", trailingChevron = true) { openNotificationSettings(ctx) }
            }
            if (notificationsOn && !exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RowDivider()
                ActionRow("Zezwól na alarmy o czasie", trailingChevron = true) { openExactAlarmSettings(ctx) }
            }
        }
    }
    if (choosingMode) {
        ChoiceDialog("Przypomnienia", REMINDER_OPTIONS, current.mode,
            onSelect = { onChange(current.copy(mode = it)); choosingMode = false },
            onDismiss = { choosingMode = false })
    }
    if (choosingMinutes) {
        ChoiceDialog("Ile wcześniej", LessonReminders.MINUTE_OPTIONS.map { it to "$it min przed lekcją" }, current.minutesBefore,
            onSelect = { onChange(current.copy(minutesBefore = it)); choosingMinutes = false },
            onDismiss = { choosingMinutes = false })
    }
}

@Composable
internal fun WidgetsSection(current: WidgetLook, onChange: (WidgetLook) -> Unit) {
    GroupedSection("Widżety", icon = Icons.Outlined.Widgets, footer = "Dotyczy wszystkich widżetów eLektronu na ekranie głównym. W motywie Auto przy przezroczystości powyżej 40% kolor tekstu dopasowuje się do tapety (jasna tapeta - ciemny tekst).") {
        // Suwak pokazuje przezroczystość (0% = pełne tło, 100% = bez tła); zapisujemy krycie.
        // Widżety odświeżają się po puszczeniu suwaka, nie przy każdym ruchu palca.
        var transparency by remember(current.opacity) { mutableFloatStateOf((100 - current.opacity).toFloat()) }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Przezroczystość tła", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${transparency.roundToInt()}%", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = transparency,
            onValueChange = { transparency = (it / 5f).roundToInt() * 5f },
            onValueChangeFinished = { onChange(current.copy(opacity = 100 - transparency.roundToInt())) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .semantics { contentDescription = "Przezroczystość tła widżetów" }
        )
        RowDivider()
        SwitchRow("Pokazuj salę", current.showRoom) { onChange(current.copy(showRoom = it)) }
        RowDivider()
        SwitchRow("Pokazuj nauczyciela", current.showTeacher) { onChange(current.copy(showTeacher = it)) }
    }
}

/**
 * Karta klasy na górze Ustawień: od niej zależy wszystko inne. Wybór klasy w oknie (bez
 * przewijanej listy wewnątrz Ustawień), zmiana dopiero po potwierdzeniu.
 */
@Composable
internal fun ClassCard(classes: List<SchoolClass>, selectedId: String?, onSelect: (String) -> Unit, onOpenGroups: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var choosing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val current = classes.firstOrNull { it.id == selectedId }
    GroupedSection(null) {
        Row(
            Modifier.fillMaxWidth().pressable(enabled = classes.isNotEmpty(), onClickLabel = "Zmień klasę") { choosing = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                Text(current?.shortName ?: "?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(current?.let { "Klasa ${it.shortName}" } ?: "Wybierz klasę", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        classes.isEmpty() -> "Brak listy klas - pociągnij w dół na stronie głównej"
                        current != null && current.fullName != current.shortName -> current.fullName
                        else -> "Plan, zastępstwa i powiadomienia tej klasy"
                    },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2
                )
            }
            if (classes.isNotEmpty()) Text("Zmień", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
        RowDivider()
        ActionRow("Grupy zajęciowe", supporting = "Tylko Twoje lekcje w planie i na stronie głównej", trailingChevron = true, onClick = onOpenGroups)
    }
    // Zmiana klasy pobiera dane od nowa i otwiera krok grup bez cofania - jedno przypadkowe
    // dotknięcie na liście było dawniej od razu zmianą klasy.
    // Id, nie obiekt - okno potwierdzenia przetrwa obrót ekranu.
    var pendingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pending = classes.firstOrNull { it.id == pendingId }
    if (choosing) {
        ChoiceDialog("Twoja klasa", classes.map { it.id to it.fullName }, selectedId ?: "",
            onSelect = { id ->
                choosing = false
                if (id != selectedId) pendingId = id
            }, onDismiss = { choosing = false })
    }
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingId = null },
            title = { Text("Zmienić klasę na ${target.fullName}?") },
            text = { Text("Aplikacja pobierze plan i zastępstwa tej klasy i poprosi o wybór grup. Grupy obecnej klasy zostaną zapamiętane.") },
            confirmButton = { TextButton(onClick = { pendingId = null; onSelect(target.id) }) { Text("Zmień klasę") } },
            dismissButton = { TextButton(onClick = { pendingId = null }) { Text("Anuluj") } }
        )
    }
}

/** Wiersz "etykieta ... wartość" otwierający wybór ([onClick] null - tylko informacja). */
@Composable
internal fun ValueRow(label: String, value: String, supporting: String? = null, enabled: Boolean = true, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
        }
        // Wartość przy prawej krawędzi, tuż przed strzałką (dawniej dwie wagi dzieliły wiersz na pół
        // i krótka wartość, np. "Stabilne", wisiała w środku). Długa nazwa przystanku zawija się.
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 180.dp), maxLines = 2)
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp).size(22.dp))
    }
}

@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Długie listy (klasy) przewijają się wewnątrz okna, od razu przy zaznaczonej pozycji.
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (options.indexOfFirst { it.first == selected } - 2).coerceAtLeast(0)
            )
            LazyColumn(Modifier.selectableGroup(), state = listState) {
                items(options) { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(title: String, initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        // TimeInput zamiast tarczy zegara - tarcza nie mieści się w oknie na wąskich ekranach.
        text = { TimeInput(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@Composable
internal fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)   // bez tego obserwator wołałby blok z pierwszym stanem
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

internal fun reminderNotificationsEnabled(ctx: Context): Boolean =
    NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
        (ctx.getSystemService(android.app.NotificationManager::class.java)
            ?.getNotificationChannel(pl.zse.bydgoszcz.elektron.work.LocalNotificationSink.CHANNEL_REMINDERS)
            ?.importance != android.app.NotificationManager.IMPORTANCE_NONE)

/** Systemowe ustawienia powiadomień eLektronu. */
internal fun openNotificationSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
        .onFailure {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
}

/** Android 12+: systemowa zgoda "Alarmy i przypomnienia" dla eLektronu. */
private fun openExactAlarmSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
