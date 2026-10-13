package pl.zse.bydgoszcz.elektron.presentation.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.zse.bydgoszcz.elektron.domain.model.DashboardCardItem
import pl.zse.bydgoszcz.elektron.domain.model.DashboardLayoutUtils

/**
 * Dialog do personalizacji układu strony głównej:
 * - przełączanie widoczności kart (Switch),
 * - zmiana kolejności (strzałki w górę / w dół),
 * - przywracanie domyślnego układu.
 */
@Composable
fun DashboardLayoutDialog(
    currentOrder: List<DashboardCardItem>,
    onDismiss: () -> Unit,
    onSave: (List<DashboardCardItem>) -> Unit
) {
    var workingOrder by remember(currentOrder) {
        mutableStateOf(DashboardLayoutUtils.decode(DashboardLayoutUtils.encode(currentOrder)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Dostosuj stronę główną") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Włączaj i wyłączaj karty oraz ustawiaj ich kolejność na ekranie startowym.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                workingOrder.forEachIndexed { index, item ->
                    DashboardCardRow(
                        item = item,
                        isFirst = index == 0,
                        isLast = index == workingOrder.lastIndex,
                        onToggleVisibility = {
                            workingOrder = workingOrder.toMutableList().also { list ->
                                list[index] = list[index].copy(isVisible = !list[index].isVisible)
                            }
                        },
                        onMoveUp = {
                            if (index > 0) {
                                workingOrder = workingOrder.toMutableList().also { list ->
                                    val tmp = list[index - 1]
                                    list[index - 1] = list[index]
                                    list[index] = tmp
                                }
                            }
                        },
                        onMoveDown = {
                            if (index < workingOrder.lastIndex) {
                                workingOrder = workingOrder.toMutableList().also { list ->
                                    val tmp = list[index + 1]
                                    list[index + 1] = list[index]
                                    list[index] = tmp
                                }
                            }
                        }
                    )
                    if (index != workingOrder.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(workingOrder) }) {
                Text(text = "Zapisz")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        workingOrder = DashboardLayoutUtils.defaultOrder()
                    }
                ) {
                    Text(text = "Domyślne")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(text = "Anuluj")
                }
            }
        }
    )
}

@Composable
private fun DashboardCardRow(
    item: DashboardCardItem,
    isFirst: Boolean,
    isLast: Boolean,
    onToggleVisibility: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.id.defaultTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onMoveUp,
            enabled = !isFirst
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = "Przenieś w górę",
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = onMoveDown,
            enabled = !isLast
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = "Przenieś w dół",
                modifier = Modifier.size(20.dp)
            )
        }

        Switch(
            checked = item.isVisible,
            onCheckedChange = { onToggleVisibility() }
        )
    }
}
