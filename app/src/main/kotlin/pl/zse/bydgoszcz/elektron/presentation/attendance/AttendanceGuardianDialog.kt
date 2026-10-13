package pl.zse.bydgoszcz.elektron.presentation.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.zse.bydgoszcz.elektron.domain.model.OverallAttendance
import pl.zse.bydgoszcz.elektron.domain.model.SubjectAttendance
import pl.zse.bydgoszcz.elektron.presentation.calendar.EduVulcanLoginDialog
import java.util.Locale

@Composable
fun AttendanceGuardianDialog(
    onDismiss: () -> Unit,
    viewModel: AttendanceViewModel = hiltViewModel()
) {
    val overall by viewModel.overallAttendance.collectAsStateWithLifecycle()
    val simulation by viewModel.simulationState.collectAsStateWithLifecycle()
    val targetThreshold by viewModel.targetThresholdPercent.collectAsStateWithLifecycle()
    val vulcanAccount by viewModel.vulcanAccount.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    var showLoginDialog by remember { mutableStateOf(false) }

    if (showLoginDialog) {
        EduVulcanLoginDialog(
            onDismiss = { showLoginDialog = false },
            onSessionConnected = { session ->
                showLoginDialog = false
                viewModel.connectEduVulcan(session)
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Strażnik Frekwencji", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            if (overall == null) {
                Text("Ładowanie statystyk frekwencji…")
            } else {
                val data = overall!!
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Karta ogólnej frekwencji
                    OverallCard(data, simulation, targetThreshold)

                    // Konfiguracja docelowego progu
                    TargetThresholdSection(
                        currentTarget = targetThreshold,
                        onTargetChange = { viewModel.setTargetThresholdPercent(it) }
                    )

                    // Symulator opuszczeń
                    SimulatorSection(
                        currentMissed = simulation.simulatedMissedHours,
                        onMissedChange = { viewModel.setSimulatedMissed(it) }
                    )

                    Text(
                        "Przedmioty (${data.subjects.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Lista przedmiotów
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(data.subjects, key = { it.subject }) { subj ->
                            SubjectRow(subj, simulation.simulatedMissedHours, targetThreshold)
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (vulcanAccount != null) {
                TextButton(
                    enabled = !isSyncing,
                    onClick = { viewModel.refresh() }
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Odśwież z eduVULCAN")
                }
            } else {
                TextButton(
                    enabled = !isSyncing,
                    onClick = { showLoginDialog = true }
                ) {
                    Text("Zaloguj do eduVULCAN")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Zamknij")
            }
        }
    )
}

@Composable
private fun TargetThresholdSection(
    currentTarget: Int,
    onTargetChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Twój cel frekwencji:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$currentTarget%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(50, 60, 70, 75, 80, 85, 90).forEach { pct ->
                val label = if (pct == 50) "50% (min)" else if (pct == 75) "75% (zalecany)" else "$pct%"
                FilterChip(
                    selected = currentTarget == pct,
                    onClick = { onTargetChange(pct) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
    }
}

@Composable
private fun OverallCard(
    data: OverallAttendance,
    simulation: AttendanceViewModel.SimulationState,
    targetThreshold: Int
) {
    val simulated = simulation.simulatedMissedHours > 0
    val displayPct = if (simulated) (simulation.simulatedOverallPercentage ?: data.percentage).toDouble() else data.percentage.toDouble()

    val balance = AttendanceThresholdCalculator.calculateBalance(
        presentHours = data.presentCount,
        absentHours = data.absentCount + simulation.simulatedMissedHours,
        targetThresholdPercent = targetThreshold
    )

    val color = when {
        balance.isCritical -> MaterialTheme.colorScheme.error
        balance.isBelowTarget -> Color(0xFFF57C00) // Poniżej celu
        else -> Color(0xFF2E7D32) // Bezpieczny zielony
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Ogólna frekwencja",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    String.format(Locale.US, "%.1f%%", displayPct),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }

            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (displayPct / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                drawStopIndicator = {}
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    balance.isCritical -> Icons.Filled.Warning
                    balance.isBelowTarget -> Icons.Filled.Info
                    else -> Icons.Filled.CheckCircle
                }
                val iconTint = when {
                    balance.isCritical -> MaterialTheme.colorScheme.error
                    balance.isBelowTarget -> Color(0xFFF57C00)
                    else -> Color(0xFF2E7D32)
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    balance.fullSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (balance.isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = if (balance.isCritical) FontWeight.Bold else FontWeight.Medium
                )
            }

            if (simulated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Symulacja: +${simulation.simulatedMissedHours} opuszczonych godzin (spadek o ${String.format(Locale.US, "%.1f%%", data.percentage - displayPct.toFloat())})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SimulatorSection(
    currentMissed: Int,
    onMissedChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Symulator: co jeśli opuszczę…",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(0, 1, 2, 4, 8, 12).forEach { hours ->
                val label = if (hours == 0) "Reset" else "+$hours h"
                FilterChip(
                    selected = currentMissed == hours,
                    onClick = { onMissedChange(hours) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
    }
}

@Composable
private fun SubjectRow(
    subj: SubjectAttendance,
    simulatedMissedHours: Int,
    targetThreshold: Int
) {
    val simulatedAbsent = subj.absentCount + simulatedMissedHours
    val balance = AttendanceThresholdCalculator.calculateBalance(
        presentHours = subj.presentCount,
        absentHours = simulatedAbsent,
        targetThresholdPercent = targetThreshold
    )
    val displayPct = balance.currentPercentage
    val color = when {
        balance.isCritical -> MaterialTheme.colorScheme.error
        balance.isBelowTarget -> Color(0xFFF57C00)
        else -> Color(0xFF2E7D32)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    subj.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    String.format(Locale.US, "%.1f%%", displayPct),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }

            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (displayPct / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                drawStopIndicator = {}
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Obecności: ${subj.presentCount} / ${subj.presentCount + simulatedAbsent} h",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    balance.shortSummary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        balance.isCritical -> MaterialTheme.colorScheme.error
                        balance.isBelowTarget -> Color(0xFFF57C00)
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}
