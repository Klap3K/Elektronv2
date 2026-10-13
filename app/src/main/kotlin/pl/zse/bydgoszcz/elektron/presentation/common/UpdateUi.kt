package pl.zse.bydgoszcz.elektron.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import pl.zse.bydgoszcz.elektron.domain.repository.AppUpdate
import pl.zse.bydgoszcz.elektron.domain.repository.InstallState
import pl.zse.bydgoszcz.elektron.domain.repository.UpdateInstaller
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class UpdateViewModel @Inject constructor(private val installer: UpdateInstaller) : ViewModel() {
    val state = installer.state

    /** Aktualizacja w aplikacji albo (F-Droid, wydanie bez APK) otwarcie strony wydania. */
    fun update(update: AppUpdate, openPage: () -> Unit) {
        if (installer.supported && update.apkUrl != null) installer.start(update) else openPage()
    }

    fun install() = installer.install()
    fun onResume() = installer.resumeIfPermitted()
}

/**
 * Przyciski i postęp aktualizacji: "Aktualizuj" -> pasek pobierania -> instalator systemu.
 * [onLater] - przycisk "Później" (baner na stronie głównej), null - bez niego.
 */
@Composable
fun UpdateActions(
    update: AppUpdate,
    onLater: (() -> Unit)?,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    viewModel: UpdateViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openPage: () -> Unit = { SafeUrls.open(ctx, update.pageUrl) }

    // Powrót z ustawień "Instalowanie nieznanych aplikacji" - dokończ instalację.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) viewModel.onResume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxWidth()) {
        when (val st = state) {
            is InstallState.Downloading -> {
                val p = st.progress
                Text(
                    if (p != null) "Pobieranie wersji ${st.versionName}… ${(p * 100).roundToInt()}%"
                    else "Pobieranie wersji ${st.versionName}…",
                    style = MaterialTheme.typography.bodyMedium, color = contentColor
                )
                Spacer(Modifier.height(8.dp))
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is InstallState.NeedsPermission -> {
                Text("Zezwól aplikacji Elektron-Vulcan na instalowanie aplikacji, a potem wróć tutaj.",
                    style = MaterialTheme.typography.bodyMedium, color = contentColor)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = viewModel::install) { Text("Zainstaluj") }
            }
            is InstallState.ReadyToInstall -> {
                Text("Wersja ${st.versionName} pobrana i sprawdzona.",
                    style = MaterialTheme.typography.bodyMedium, color = contentColor)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = viewModel::install) { Text("Zainstaluj") }
            }
            is InstallState.Failed -> {
                Text(st.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { viewModel.update(update, openPage) }) { Text("Spróbuj ponownie") }
                    TextButton(onClick = openPage) { Text("Strona wydania") }
                }
            }
            InstallState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { viewModel.update(update, openPage) }) {
                    Text(if (update.apkUrl != null) "Aktualizuj" else "Pobierz")
                }
                if (onLater != null) TextButton(onClick = onLater) { Text("Później") }
            }
        }
    }
}
