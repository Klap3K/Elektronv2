package pl.zse.bydgoszcz.elektron.presentation.grades

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.zse.bydgoszcz.elektron.domain.model.GradeCalculator
import pl.zse.bydgoszcz.elektron.domain.model.StudentGrade
import pl.zse.bydgoszcz.elektron.domain.model.SubjectGradesSummary
import pl.zse.bydgoszcz.elektron.domain.model.VirtualGrade
import pl.zse.bydgoszcz.elektron.presentation.calendar.EduVulcanLoginDialog
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradesDialog(
    onDismiss: () -> Unit,
    viewModel: GradesViewModel = hiltViewModel()
) {
    val summaries by viewModel.subjectSummaries.collectAsStateWithLifecycle()
    val overallAvg by viewModel.overallAverage.collectAsStateWithLifecycle()
    val virtualGrades by viewModel.virtualGrades.collectAsStateWithLifecycle()
    val overallSimAvg by viewModel.overallSimulatedAverage.collectAsStateWithLifecycle()
    val availablePeriods by viewModel.availablePeriods.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val vulcanAccount by viewModel.vulcanAccount.collectAsStateWithLifecycle()

    var showEduVulcanLogin by remember { mutableStateOf(false) }
    var showAddGradeDialog by remember { mutableStateOf(false) }
    var expandedSubject by remember { mutableStateOf<String?>(null) }

    val activePeriod = selectedPeriod ?: summaries.firstOrNull()?.periodNumber ?: availablePeriods.lastOrNull() ?: 1

    if (showEduVulcanLogin) {
        EduVulcanLoginDialog(
            onDismiss = { showEduVulcanLogin = false },
            onSessionConnected = { session ->
                showEduVulcanLogin = false
                viewModel.connectAndImportEduVulcan(session)
            }
        )
    }

    if (showAddGradeDialog) {
        AddManualGradeDialog(
            existingSubjects = summaries.map { it.subject },
            onDismiss = { showAddGradeDialog = false },
            onAdd = { subj, valStr, weight, cat, desc ->
                viewModel.addManualGrade(subj, valStr, weight, cat, desc)
                showAddGradeDialog = false
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Nagłówek
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Grade,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Oceny i średnia",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (vulcanAccount != null) {
                                    "Połączono z eduVULCAN (${vulcanAccount!!.pupilName})"
                                } else {
                                    "Importuj z eduVULCAN lub dodaj własne oceny"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Zamknij")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Karta średniej ogólnej + przyciski importu z eduVULCAN
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Średnia ze wszystkich przedmiotów",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = overallAvg?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (overallSimAvg != null && overallSimAvg != overallAvg) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "➔",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = String.format(Locale.US, "%.2f", overallSimAvg),
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                                if (virtualGrades.isNotEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Text(
                                            text = "✨ Symulacja: ${virtualGrades.size} wirtualnych ocen",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Wyczyść wszystko",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { viewModel.clearVirtualGrades() }
                                        )
                                    }
                                }
                            }

                            if (availablePeriods.size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    availablePeriods.forEach { pNum ->
                                        FilterChip(
                                            selected = activePeriod == pNum,
                                            onClick = { viewModel.selectPeriod(pNum) },
                                            label = { Text("Semestr $pNum") }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (vulcanAccount != null) {
                                        viewModel.importFromEduVulcan()
                                    } else {
                                        showEduVulcanLogin = true
                                    }
                                },
                                enabled = !isSyncing,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (vulcanAccount != null) "Importuj z eduVULCAN" else "Zaloguj i importuj",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = { showAddGradeDialog = true }
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Dodaj")
                            }
                        }

                        if (statusMessage != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = statusMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Lista przedmiotów i ocen
                if (summaries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Brak zaimportowanych ocen",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Kliknij „Importuj z eduVULCAN” powyżej, aby pobrać swoje oceny cząstkowe i średnie bezpośrednio z dziennika.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(summaries, key = { "${it.periodNumber}_${it.subject}" }) { summary ->
                            val isExpanded = expandedSubject == summary.subject
                            val subjectVirtuals = virtualGrades.filter { it.subject.equals(summary.subject, ignoreCase = true) }
                            SubjectGradesRow(
                                summary = summary,
                                virtualGrades = subjectVirtuals,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    expandedSubject = if (isExpanded) null else summary.subject
                                },
                                onDeleteGrade = viewModel::deleteGrade,
                                onAddVirtualGrade = { valStr, weight ->
                                    viewModel.addVirtualGrade(summary.subject, valStr, weight)
                                },
                                onRemoveVirtualGrade = viewModel::removeVirtualGrade,
                                onClearVirtualGrades = {
                                    viewModel.clearVirtualGrades(summary.subject)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectGradesRow(
    summary: SubjectGradesSummary,
    virtualGrades: List<VirtualGrade>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onDeleteGrade: (String) -> Unit,
    onAddVirtualGrade: (value: String, weight: Double) -> Unit,
    onRemoveVirtualGrade: (String) -> Unit,
    onClearVirtualGrades: () -> Unit
) {
    val simulatedAvg = if (virtualGrades.isNotEmpty()) {
        GradeCalculator.calculateWeightedAverageWithVirtual(summary.grades, virtualGrades)
    } else null

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggleExpand)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.subject,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val metaParts = buildList {
                        summary.proposedGrade?.let { add("Proponowana: $it") }
                        summary.finalGrade?.let { add("Końcowa: $it") }
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val displayAvg = simulatedAvg ?: summary.average
                    if (displayAvg != null) {
                        val avgColor = when {
                            displayAvg >= 4.75 -> Color(0xFF2E7D32)
                            displayAvg >= 3.5 -> MaterialTheme.colorScheme.primary
                            displayAvg >= 2.0 -> Color(0xFFEF6C00)
                            else -> MaterialTheme.colorScheme.error
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = avgColor.copy(alpha = 0.15f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                if (simulatedAvg != null && summary.average != null && simulatedAvg != summary.average) {
                                    Text(
                                        text = String.format(Locale.US, "%.2f", summary.average),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "➔",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = String.format(Locale.US, "%.2f", simulatedAvg),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = avgColor
                                    )
                                } else {
                                    Text(
                                        text = String.format(Locale.US, "Śr: %.2f", displayAvg),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = avgColor
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val totalGradesCount = summary.grades.size + virtualGrades.size
            if (totalGradesCount > 0) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    summary.grades.forEach { g ->
                        GradeBadgeChip(g)
                    }
                    virtualGrades.forEach { vg ->
                        VirtualGradeBadgeChip(
                            virtualGrade = vg,
                            onRemove = { onRemoveVirtualGrade(vg.id) }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Symulator ocen "Co jeśli"
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "✨ Symulator „Co jeśli”",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (virtualGrades.isNotEmpty()) {
                                    Text(
                                        text = "Resetuj symulację",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clickable(onClick = onClearVirtualGrades)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Wybierz wagę i dotknij ocenę, aby zasymulować wpływ na średnią:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))

                            var selectedSimWeight by remember { mutableStateOf(1.0) }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Waga:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                listOf(1.0, 2.0, 3.0, 4.0).forEach { w ->
                                    val isSelected = selectedSimWeight == w
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier.clickable { selectedSimWeight = w }
                                    ) {
                                        Text(
                                            text = "${w.toInt()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("6", "5", "4", "3", "2", "1").forEach { gradeVal ->
                                    Button(
                                        onClick = { onAddVirtualGrade(gradeVal, selectedSimWeight) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                    ) {
                                        Text(
                                            text = "+$gradeVal",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (summary.grades.isNotEmpty() || virtualGrades.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Szczegóły ocen:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))

                        // Oceny wirtualne
                        virtualGrades.forEach { vg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                VirtualGradeBadgeChip(
                                    virtualGrade = vg,
                                    onRemove = { onRemoveVirtualGrade(vg.id) }
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Ocena symulowana („Co jeśli”)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        text = "Waga: ${vg.weight.toInt().takeIf { it.toDouble() == vg.weight } ?: vg.weight}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveVirtualGrade(vg.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Usuń symulację",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Oceny rzeczywiste
                        summary.grades.forEach { g ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GradeBadgeChip(g)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val title = listOfNotNull(g.category, g.description)
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                        .joinToString(": ")
                                        .ifBlank { "Ocena cząstkowa" }
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val sub = buildList {
                                        if (g.weight > 0.0) add("Waga: ${g.weight.toInt().takeIf { it.toDouble() == g.weight } ?: g.weight}")
                                        g.dateText?.takeIf { it.isNotBlank() }?.let { add(it) }
                                        g.teacher?.takeIf { it.isNotBlank() }?.let { add(it) }
                                        if (g.isSuperseded) add("Poprawiona")
                                    }.joinToString(" • ")
                                    if (sub.isNotBlank()) {
                                        Text(
                                            text = sub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (g.id.startsWith("manual_")) {
                                    IconButton(
                                        onClick = { onDeleteGrade(g.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Usuń ocenę",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualGradeBadgeChip(
    virtualGrade: VirtualGrade,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        modifier = Modifier.clickable(onClick = onRemove)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "✨ ${virtualGrade.value}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            val wInt = virtualGrade.weight.toInt()
            val wStr = if (wInt.toDouble() == virtualGrade.weight) "$wInt" else "${virtualGrade.weight}"
            Text(
                text = " [w:$wStr]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Usuń symulację",
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun GradeBadgeChip(grade: StudentGrade) {
    val num = grade.numericValue
    val bgColor = when {
        grade.isSuperseded -> MaterialTheme.colorScheme.surfaceVariant
        num == null -> MaterialTheme.colorScheme.secondaryContainer
        num >= 5.0 -> Color(0xFF2E7D32).copy(alpha = 0.18f)
        num >= 4.0 -> Color(0xFF1565C0).copy(alpha = 0.18f)
        num >= 3.0 -> Color(0xFFF9A825).copy(alpha = 0.22f)
        num >= 2.0 -> Color(0xFFEF6C00).copy(alpha = 0.20f)
        else -> Color(0xFFC62828).copy(alpha = 0.20f)
    }
    val textColor = when {
        grade.isSuperseded -> MaterialTheme.colorScheme.onSurfaceVariant
        num == null -> MaterialTheme.colorScheme.onSecondaryContainer
        num >= 5.0 -> Color(0xFF2E7D32)
        num >= 4.0 -> Color(0xFF1565C0)
        num >= 3.0 -> Color(0xFFF57F17)
        num >= 2.0 -> Color(0xFFE65100)
        else -> Color(0xFFC62828)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = grade.value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textDecoration = if (grade.isSuperseded) TextDecoration.LineThrough else TextDecoration.None
        )
    }
}

@Composable
private fun AddManualGradeDialog(
    existingSubjects: List<String>,
    onDismiss: () -> Unit,
    onAdd: (subject: String, value: String, weight: Double, category: String?, description: String?) -> Unit
) {
    var subject by remember { mutableStateOf(existingSubjects.firstOrNull().orEmpty()) }
    var gradeValue by remember { mutableStateOf("5") }
    var weightStr by remember { mutableStateOf("1") }
    var category by remember { mutableStateOf("Sprawdzian") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dodaj ocenę / symuluj średnią") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Przedmiot") },
                    placeholder = { Text("np. Matematyka") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = gradeValue,
                        onValueChange = { gradeValue = it },
                        label = { Text("Ocena") },
                        placeholder = { Text("np. 5, 4+, 3-") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weightStr,
                        onValueChange = { weightStr = it },
                        label = { Text("Waga") },
                        placeholder = { Text("np. 3") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Kategoria") },
                    placeholder = { Text("np. Sprawdzian, Kartkówka, Odpowiedź") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Opis (opcjonalnie)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = subject.isNotBlank() && gradeValue.isNotBlank(),
                onClick = {
                    val w = weightStr.replace(',', '.').toDoubleOrNull() ?: 1.0
                    onAdd(subject, gradeValue, w, category, description)
                }
            ) {
                Text("Dodaj")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anuluj")
            }
        }
    )
}
