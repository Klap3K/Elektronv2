package pl.zse.bydgoszcz.elektron.presentation.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.zse.bydgoszcz.elektron.R
import pl.zse.bydgoszcz.elektron.launcher.AppIcon
import pl.zse.bydgoszcz.elektron.launcher.AppIcons

/** Wiersz "Ikona aplikacji" w sekcji Wygląd i okno wyboru z podglądem. */
@Composable
internal fun AppIconRow() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    var choosing by remember { mutableStateOf(false) }
    ValueRow("Ikona aplikacji", current.label, supporting = "Po zmianie aplikacja się zamknie") { choosing = true }
    if (choosing) AppIconDialog(current, onSelect = { icon ->
        AppIcons.set(context, icon)
        current = AppIcons.current(context)
        choosing = false
    }, onDismiss = { choosing = false })
}

@Composable
private fun AppIconDialog(selected: AppIcon, onSelect: (AppIcon) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ikona aplikacji") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    AppIcon.entries.forEach { icon ->
                        val isSelected = icon == selected
                        Column(
                            Modifier.clip(RoundedCornerShape(16.dp))
                                .selectable(isSelected, role = Role.RadioButton) { onSelect(icon) }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            IconPreview(icon, isSelected)
                            Spacer(Modifier.height(8.dp))
                            Text(icon.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                                color = if (isSelected) colors.primary else colors.onSurface)
                        }
                    }
                }
                Text("Po zmianie ikony aplikacja się zamknie - otwórz ją ponownie. Ikona może też zniknąć " +
                    "z ekranu głównego; wtedy dodaj ją ponownie z listy aplikacji.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

/** Podgląd jak na ekranie głównym: tło ikony w kółku, logo w bezpiecznej strefie (72 z 108 dp). */
@Composable
private fun IconPreview(icon: AppIcon, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val background = when (icon) {
        AppIcon.DEFAULT -> Color.White
        AppIcon.LIGHT -> Color.Black
    }
    Box(
        Modifier.size(64.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(84.dp))
    }
}
