package pl.zse.bydgoszcz.elektron.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.presentation.calendar.EduVulcanLoginDialog
import pl.zse.bydgoszcz.elektron.presentation.common.GroupedSection
import pl.zse.bydgoszcz.elektron.service.EduVulcanAppBridge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CalendarSection(
    vulcanAccount: VulcanAccount?,
    isRegistering: Boolean,
    calendarUrl: String?,
    isSyncing: Boolean,
    lastSyncEpochSeconds: Long?,
    lastSyncError: String?,
    onConnectEduVulcan: (EduVulcanSession) -> Unit,
    onDisconnectVulcan: () -> Unit,
    onSetUrl: (String?) -> Unit,
    onSync: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showEduVulcanLoginDialog by remember { mutableStateOf(false) }
    var showAppBridgeDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }

    var isAppInstalled by remember { mutableStateOf(EduVulcanAppBridge.isEduVulcanAppInstalled(context)) }
    var isBridgeEnabled by remember { mutableStateOf(EduVulcanAppBridge.isNotificationBridgeEnabled(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAppInstalled = EduVulcanAppBridge.isEduVulcanAppInstalled(context)
                isBridgeEnabled = EduVulcanAppBridge.isNotificationBridgeEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GroupedSection(
        title = "Dziennik eduVULCAN (Sprawdziany i Frekwencja)",
        icon = Icons.Outlined.CalendarMonth,
        footer = "Zaloguj się swoim loginem i hasłem do eduVULCAN lub włącz przechwytywanie danych z zainstalowanej na telefonie aplikacji eduVULCAN. Sprawdziany, kartkówki i frekwencja trafią bezpośrednio do planu lekcji, widżetów oraz Strażnika Frekwencji."
    ) {
        val isConnected = vulcanAccount != null

        if (isConnected) {
            val loginInfo = vulcanAccount.loginUsername.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
            ActionRow(
                label = "Uczeń: ${vulcanAccount.pupilName}",
                supporting = "${vulcanAccount.schoolName} (${vulcanAccount.symbol})$loginInfo",
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "Połączono",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )

            RowDivider()

            val syncLabel = if (isSyncing) "Trwa synchronizacja z eduVULCAN…" else "Synchronizuj sprawdziany i frekwencję"
            val lastSyncFormatted = lastSyncEpochSeconds?.let {
                val instant = Instant.ofEpochSecond(it)
                val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault())
                "Ostatnia synchronizacja: " + formatter.format(instant)
            }

            ActionRow(
                label = syncLabel,
                supporting = lastSyncFormatted,
                enabled = !isSyncing,
                trailing = if (isSyncing) {
                    @Composable { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else null,
                onClick = onSync
            )

            RowDivider()
            ActionRow(
                label = "Zmień konto / zaloguj ponownie",
                trailingChevron = true,
                onClick = { showEduVulcanLoginDialog = true }
            )

            RowDivider()
            ActionRow(
                label = "Odłącz konto eduVULCAN",
                destructive = true,
                onClick = onDisconnectVulcan
            )
        } else {
            ActionRow(
                label = if (isRegistering) "Łączenie z kontem eduVULCAN…" else "Zaloguj się do eduVULCAN (Login i Hasło)",
                supporting = "Pobierz sprawdziany, kartkówki, zadania domowe i frekwencję",
                enabled = !isRegistering,
                trailingChevron = !isRegistering,
                trailing = if (isRegistering) {
                    @Composable { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else null,
                onClick = { showEduVulcanLoginDialog = true }
            )
        }

        // Integracja z zainstalowaną aplikacją eduVULCAN na urządzeniu
        RowDivider()
        val bridgeSupporting = when {
            isBridgeEnabled && isAppInstalled -> "Aktywne • aplikacja eduVULCAN wykryta na urządzeniu"
            isBridgeEnabled -> "Aktywne • nasłuchiwanie powiadomień z aplikacji eduVULCAN"
            isAppInstalled -> "Wykryto aplikację eduVULCAN na telefonie – kliknij, aby połączyć"
            else -> "Zaciągaj nowe wpisy z zalogowanej aplikacji eduVULCAN na urządzeniu"
        }

        ActionRow(
            label = "Zaciągaj dane z aplikacji eduVULCAN na telefonie",
            supporting = bridgeSupporting,
            trailing = {
                Icon(
                    imageVector = if (isBridgeEnabled) Icons.Outlined.NotificationsActive else Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = if (isBridgeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = { showAppBridgeDialog = true }
        )

        // Sekcja alternatywna iCal
        RowDivider()
        val hasUrl = !calendarUrl.isNullOrBlank()
        val urlDisplay = if (hasUrl) {
            val short = calendarUrl.removePrefix("https://").removePrefix("http://").removePrefix("webcal://")
            if (short.length > 35) short.take(32) + "…" else short
        } else {
            "Opcjonalny link subskrypcyjny (.ics)"
        }

        ActionRow(
            label = "Alternatywny link kalendarza iCal",
            supporting = urlDisplay,
            trailingChevron = true,
            onClick = { showUrlDialog = true }
        )

        lastSyncError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        if (hasUrl && !isConnected) {
            RowDivider()
            ActionRow(
                label = "Wyczyść konfigurację i wydarzenia",
                destructive = true,
                onClick = onClear
            )
        }
    }

    // --- Dialog logowania hasłem do eduVULCAN ---
    if (showEduVulcanLoginDialog) {
        EduVulcanLoginDialog(
            onDismiss = { showEduVulcanLoginDialog = false },
            onSessionConnected = { session ->
                showEduVulcanLoginDialog = false
                onConnectEduVulcan(session)
            }
        )
    }

    // --- Dialog mostka z zainstalowaną aplikacją eduVULCAN na telefonie ---
    if (showAppBridgeDialog) {
        AlertDialog(
            onDismissRequest = { showAppBridgeDialog = false },
            title = { Text("Integracja z aplikacją eduVULCAN na telefonie") },
            text = {
                Column {
                    Text(
                        text = if (isAppInstalled) {
                            "✅ Wykryto zainstalowaną oficjalną aplikację eduVULCAN na Twoim telefonie."
                        } else {
                            "ℹ️ Jeśli masz na telefonie zalogowaną oficjalną aplikację eduVULCAN, Elektron-Vulcan może automatycznie przechwytywać z niej nowe wpisy."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Jak to działa?\n" +
                            "1. Włącz uprawnienie „Synchronizacja z aplikacji eduVULCAN” w ustawieniach dostępu do powiadomień.\n" +
                            "2. Gdy oficjalna aplikacja eduVULCAN wyświetli powiadomienie o nowym sprawdzianie, kartkówce, zadaniu lub nieobecności, Elektron-Vulcan automatycznie zapisze je w Twoim planie lekcji i terminarzu.\n" +
                            "3. Aby pobrać pełną historię sprawdzianów i frekwencji od razu, zaloguj się także loginem i hasłem powyżej.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isAppInstalled) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                EduVulcanAppBridge.openInstalledEduVulcanApp(context)
                            }
                        ) {
                            Text("Otwórz zainstalowaną aplikację eduVULCAN")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAppBridgeDialog = false
                        EduVulcanAppBridge.openNotificationAccessSettings(context)
                    }
                ) {
                    Text(if (isBridgeEnabled) "Ustawienia dostępu (Aktywne)" else "Włącz dostęp do powiadomień")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppBridgeDialog = false }) {
                    Text("Zamknij")
                }
            }
        )
    }

    // --- Dialog linku iCal ---
    if (showUrlDialog) {
        var inputUrl by remember { mutableStateOf(calendarUrl.orEmpty()) }

        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Subskrypcja kalendarza iCal") },
            text = {
                Column {
                    Text(
                        "Wklej link subskrypcyjny kalendarza (.ics) lub adres z zewnętrznego terminarza:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://... lub webcal://...") },
                        singleLine = false,
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUrlDialog = false
                    onSetUrl(inputUrl.trim().takeIf { it.isNotBlank() })
                }) {
                    Text("Zapisz")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Anuluj")
                }
            }
        )
    }
}
