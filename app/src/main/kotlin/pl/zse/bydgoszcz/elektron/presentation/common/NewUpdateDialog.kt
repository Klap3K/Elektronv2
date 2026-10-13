package pl.zse.bydgoszcz.elektron.presentation.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate

/**
 * Globalne okno dialogowe informujące o dostępności nowej aktualizacji aplikacji Elektron-Vulcan.
 * Wyświetlane na pierwszym planie po wykryciu nowszej wersji w tle, z możliwością
 * natychmiastowej instalacji jednym kliknięciem (z paskiem postępu pobierania APK)
 * lub odłożenia na później.
 */
@Composable
fun NewUpdateDialog(
    update: AppUpdate,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Nowa aktualizacja!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Wersja ${update.versionName}${if (update.prerelease) " (Beta)" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "Dostępna jest nowa wersja aplikacji Elektron-Vulcan. Pobierz i zainstaluj ją, aby korzystać z najnowszych funkcji i usprawnień.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (update.sizeBytes > 0) {
                    Spacer(Modifier.height(8.dp))
                    val sizeMb = String.format(java.util.Locale.US, "%.1f", update.sizeBytes / (1024.0 * 1024.0))
                    Text(
                        text = "Rozmiar pobierania: ~$sizeMb MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
                UpdateActions(
                    update = update,
                    onLater = onDismiss,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {}
    )
}
