package pl.zse.bydgoszcz.elektron.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.presentation.common.GroupedSection
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
    onRegisterVulcan: (token: String, symbol: String, pin: String) -> Unit,
    onRegisterVulcanRaw: (raw: String) -> Unit,
    onDisconnectVulcan: () -> Unit,
    onSetUrl: (String?) -> Unit,
    onSync: () -> Unit,
    onClear: () -> Unit
) {
    var showConnectDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }

    GroupedSection(
        title = "Terminarz i sprawdziany (VULCAN)",
        icon = Icons.Outlined.CalendarMonth,
        footer = "Połącz aplikację z Dziennikiem VULCAN za pomocą danych z zakładki 'Dostęp mobilny'. Sprawdziany, kartkówki i prace klasowe pojawią się bezpośrednio na kafelkach lekcji oraz w planie zajęć."
    ) {
        val isConnected = vulcanAccount != null

        if (isConnected) {
            ActionRow(
                label = "Konto: ${vulcanAccount.pupilName}",
                supporting = "${vulcanAccount.schoolName} (${vulcanAccount.symbol})",
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

            val syncLabel = if (isSyncing) "Trwa synchronizacja…" else "Synchronizuj sprawdziany"
            val lastSyncFormatted = lastSyncEpochSeconds?.let {
                val instant = Instant.ofEpochSecond(it)
                val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault())
                "Ostatnio: " + formatter.format(instant)
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
                label = "Odłącz konto VULCAN",
                destructive = true,
                onClick = onDisconnectVulcan
            )
        } else {
            ActionRow(
                label = "Połącz z VULCAN (Token, Symbol, PIN)",
                supporting = "Kliknij, aby skonfigurować dostęp mobilny",
                trailingChevron = true,
                onClick = { showConnectDialog = true }
            )

            RowDivider()
            ActionRow(
                label = "Jak uzyskać Token i PIN? (Poradnik)",
                supporting = "Gdzie znaleźć kod w eduVULCAN / UONET+",
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = "Pomoc",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { showHelpDialog = true }
            )
        }

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

    // --- Dialog łączenia konta VULCAN ---
    if (showConnectDialog) {
        var tokenInput by remember { mutableStateOf("") }
        var symbolInput by remember { mutableStateOf("bydgoszcz") }
        var pinInput by remember { mutableStateOf("") }
        var rawQrInput by remember { mutableStateOf("") }
        var isQrMode by remember { mutableStateOf(false) }
        var validationError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isRegistering) showConnectDialog = false },
            title = { Text("Logowanie do Dziennika VULCAN") },
            text = {
                Column {
                    Text(
                        "Wprowadź dane z zakładki 'Dostęp mobilny' w przeglądarce eduVULCAN / UONET+:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))

                    if (!isQrMode) {
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("Token") },
                            placeholder = { Text("np. FK1000... lub C123456") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = symbolInput,
                            onValueChange = { symbolInput = it },
                            label = { Text("Symbol") },
                            placeholder = { Text("np. bydgoszcz") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it },
                            label = { Text("PIN") },
                            placeholder = { Text("6-cyfrowy kod, np. 123456") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = rawQrInput,
                            onValueChange = {
                                rawQrInput = it
                                validationError = null
                            },
                            label = { Text("Kod z QR lub JSON") },
                            placeholder = { Text("Wklej zawartość kodu QR lub JSON...") },
                            isError = validationError != null,
                            supportingText = validationError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                isQrMode = !isQrMode
                                validationError = null
                            }
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isQrMode) "Wprowadź pola ręcznie" else "Wklej kod QR / JSON")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isRegistering && (
                        (isQrMode && rawQrInput.isNotBlank()) ||
                        (!isQrMode && tokenInput.isNotBlank() && symbolInput.isNotBlank() && pinInput.isNotBlank())
                    ),
                    onClick = {
                        if (isQrMode) {
                            val parsed = pl.zse.bydgoszcz.elektron.domain.model.VulcanRegistrationInput.parseOrNull(rawQrInput.trim())
                            if (parsed == null) {
                                validationError = "Nie rozpoznano formatu kodu QR. Upewnij się, że kod zawiera Token, Symbol i PIN."
                                return@TextButton
                            }
                            onRegisterVulcanRaw(rawQrInput.trim())
                        } else {
                            onRegisterVulcan(tokenInput.trim(), symbolInput.trim(), pinInput.trim())
                        }
                        showConnectDialog = false
                    }
                ) {
                    if (isRegistering) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Połącz")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRegistering,
                    onClick = { showConnectDialog = false }
                ) {
                    Text("Anuluj")
                }
            }
        )
    }

    // --- Dialog poradnika / pomocy ---
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Skąd wziąć Token, Symbol i PIN?") },
            text = {
                Column {
                    Text("Kroki w przeglądarce:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("1. Zaloguj się na stronę eduVULCAN (lub szkolny dziennik UONET+).")
                    Spacer(Modifier.height(4.dp))
                    Text("2. Wejdź w menu ucznia i kliknij zakładkę Dostęp mobilny (lub 'Zarejestruj urządzenie').")
                    Spacer(Modifier.height(4.dp))
                    Text("3. Kliknij 'Nowe urządzenie' / 'Zarejestruj urządzenie'.")
                    Spacer(Modifier.height(4.dp))
                    Text("4. Wygeneruje się kod QR. Pod nim lub obok niego widoczne będą pola:")
                    Text("   • Token (długi ciąg znaków)", fontWeight = FontWeight.Medium)
                    Text("   • Symbol (np. bydgoszcz)", fontWeight = FontWeight.Medium)
                    Text("   • PIN (6-cyfrowy kod numeryczny)", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Wskazówka: Nigdy nie musisz podawać hasła głównego do konta. Aplikacja generuje bezpieczny certyfikat urządzenia.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showHelpDialog = false
                    showConnectDialog = true
                }) {
                    Text("Wpisz dane")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHelpDialog = false }) {
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
