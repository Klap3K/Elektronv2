package pl.zse.bydgoszcz.elektron.presentation.attendance

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.zse.bydgoszcz.elektron.domain.model.OverallAttendance
import pl.zse.bydgoszcz.elektron.domain.model.SubjectAttendance
import java.util.Locale

@Composable
fun AttendanceGuardianDialog(
    onDismiss: () -> Unit,
    viewModel: AttendanceViewModel = hiltViewModel()
) {
    val overall by viewModel.overallAttendance.collectAsStateWithLifecycle()
    val simulation by viewModel.simulationState.collectAsStateWithLifecycle()

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
                    OverallCard(data, simulation)

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
                            SubjectRow(subj, simulation.simulatedMissedHours)
                        }
                    }
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
private fun OverallCard(data: OverallAttendance, simulation: AttendanceViewModel.SimulationState) {
    val simulated = simulation.simulatedMissedHours > 0
    val displayPct = if (simulated) simulation.simulatedOverallPercentage ?: data.percentage else data.percentage

    val color = when {
        displayPct < 50f -> MaterialTheme.colorScheme.error
        displayPct < 65f -> Color(0xFFF57C00) // Ostrzegawczy pomarańczowy
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
                progress = { (displayPct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = color,
                trackColor = color.copy(alpha = 0.2f),
                drawStopIndicator = {}
            )

            Spacer(Modifier.height(8.dp))
            val safeMiss = data.maxMissableHours(0.50f)
            val recover = data.hoursNeededToRecover(0.50f)

            if (data.percentage >= 50f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Możesz opuścić jeszcze $safeMiss godz. przed progiem 50%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Zagrożenie: musisz być obecny na $recover godz., by osiągnąć 50%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (simulated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Symulacja: +${simulation.simulatedMissedHours} opuszczonych godzin (spadek o ${String.format(Locale.US, "%.1f%%", data.percentage - displayPct)})",
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(0, 1, 2, 4, 8).forEach { hours ->
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
private fun SubjectRow(subj: SubjectAttendance, simulatedMissedHours: Int) {
    val displayPct = if (simulatedMissedHours > 0) subj.simulate(simulatedAbsentHours = simulatedMissedHours) else subj.percentage
    val color = when {
        displayPct < 50f -> MaterialTheme.colorScheme.error
        displayPct < 65f -> Color(0xFFF57C00)
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
                progress = { (displayPct / 100f).coerceIn(0f, 1f) },
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Obecności: ${subj.presentCount} / ${subj.totalHours} h",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val missable = subj.maxMissableHours(0.50f)
                val needed = subj.hoursNeededToRecover(0.50f)
                val limitText = if (displayPct >= 50f) "Zapas: $missable h" else "Do 50%: +$needed h"
                Text(
                    limitText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (displayPct >= 50f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
