package pl.zse.bydgoszcz.elektron.presentation.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import pl.zse.bydgoszcz.elektron.domain.model.ActivityCategory
import pl.zse.bydgoszcz.elektron.domain.model.UserActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun AddActivityDialog(
    initialDate: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onSave: (UserActivity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ActivityCategory.STUDY) }
    var selectedDate by remember { mutableStateOf(initialDate) }
    var timeFromStr by remember { mutableStateOf("") }
    var timeToStr by remember { mutableStateOf("") }
    var titleError by remember { mutableStateOf(false) }

    val dateFmt = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Dodaj własny plan / aktywność",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (it.isNotBlank()) titleError = false
                    },
                    label = { Text("Tytuł zadania / aktywności *") },
                    placeholder = { Text("np. Powtórka z matematyki, Siłownia") },
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text("Tytuł jest wymagany") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Kategoria",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActivityCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text("${cat.icon} ${cat.label}") }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Data: ${selectedDate.format(dateFmt)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = timeFromStr,
                        onValueChange = { timeFromStr = it },
                        label = { Text("Od (HH:mm)") },
                        placeholder = { Text("16:00") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.AccessTime, contentDescription = null)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = timeToStr,
                        onValueChange = { timeToStr = it },
                        label = { Text("Do (HH:mm)") },
                        placeholder = { Text("17:30") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Opis / notatki (opcjonalnie)") },
                    placeholder = { Text("np. Rozdziały 3 i 4, zadania 1-10 ze strony 52") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                        return@Button
                    }
                    val fromTime = runCatching {
                        if (timeFromStr.isNotBlank()) LocalTime.parse(timeFromStr.trim()) else null
                    }.getOrNull()
                    val toTime = runCatching {
                        if (timeToStr.isNotBlank()) LocalTime.parse(timeToStr.trim()) else null
                    }.getOrNull()

                    val activity = UserActivity(
                        id = UUID.randomUUID().toString(),
                        title = title.trim(),
                        description = description.trim().ifBlank { null },
                        category = selectedCategory,
                        date = selectedDate,
                        timeFrom = fromTime,
                        timeTo = toTime,
                        isCompleted = false,
                        createdAtEpochSeconds = System.currentTimeMillis() / 1000
                    )
                    onSave(activity)
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
