package pl.zse.bydgoszcz.elektron.presentation.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings
import pl.zse.bydgoszcz.elektron.domain.util.AppClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CountdownConfigDialog(
    initialSettings: CountdownSettings,
    onDismiss: () -> Unit,
    onSave: (CountdownSettings) -> Unit
) {
    var enabled by remember { mutableStateOf(initialSettings.enabled) }
    var title by remember { mutableStateOf(initialSettings.title) }
    var targetDate by remember { mutableStateOf(initialSettings.targetDate) }
    var showDatePicker by remember { mutableStateOf(false) }

    val presets = remember { CountdownSettings.getPresets(AppClock.today()) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("pl", "PL")) }

    val previewSettings = remember(enabled, title, targetDate) {
        CountdownSettings(enabled = enabled, title = title.trim(), targetDate = targetDate)
    }

    if (showDatePicker) {
        val initialEpochMillis = targetDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialEpochMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        targetDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) {
                    Text("Zatwierdź")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Anuluj")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Odliczanie na stronie głównej")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Przełącznik włączenia
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { enabled = !enabled }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pokaż odliczanie", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Karta na stronie głównej odliczająca dni",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                if (enabled) {
                    Text(
                        "Szybkie szablony:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.forEach { (presetTitle, presetDate) ->
                            val isSelected = title == presetTitle && targetDate == presetDate
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    title = presetTitle
                                    targetDate = presetDate
                                },
                                label = { Text(presetTitle) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Tytuł wydarzenia") },
                        placeholder = { Text("np. Wakacje, 18-tka, Matura") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedCard(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    "Data docelowa",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    targetDate.format(dateFormatter),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Icon(
                                Icons.Filled.CalendarToday,
                                contentDescription = "Wybierz datę",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Podgląd wyniku
                    val totalDays = previewSettings.daysRemaining
                    val schoolDays = previewSettings.schoolDaysRemaining
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Podgląd na żywo:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (totalDays > 0) "Za ${CountdownSettings.formatDays(totalDays)}" else "To już dziś! 🎉",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (totalDays > 0) {
                                Text(
                                    "W tym ${CountdownSettings.formatSchoolDays(schoolDays)} (pon-pt)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        CountdownSettings(
                            enabled = enabled,
                            title = title.ifBlank { "Wydarzenie" }.trim(),
                            targetDate = targetDate
                        )
                    )
                }
            ) {
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
