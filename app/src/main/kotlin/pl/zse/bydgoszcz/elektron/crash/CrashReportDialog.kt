package pl.zse.bydgoszcz.elektron.crash

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Okno po awarii aplikacji (raport z poprzedniego uruchomienia). */
@Composable
fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    ReportDialog(
        title = "Aplikacja nieoczekiwanie się zamknęła",
        intro = "Wyślij raport - pomoże naprawić ten błąd. Napisz w wiadomości, co robiłeś/aś tuż przed awarią.",
        report = report,
        issueUrl = CrashReport.issueUrl(report),
        onDismiss = onDismiss
    )
}

/**
 * Raport z surowymi logami do skopiowania, wysłania e-mailem na adres dewelopera albo
 * zgłoszenia na GitHubie. Wspólny dla awarii i "Zgłoś problem" w Ustawieniach.
 */
@Composable
fun ReportDialog(
    title: String,
    intro: String,
    report: String,
    issueUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var info by remember { mutableStateOf<String?>(null) }
    val copy = {
        clipboard.setText(AnnotatedString(report))
        info = "Skopiowano raport do schowka."
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            // Całe okno przewijane - na małym ekranie / w poziomie przyciski nie mogą uciec poza ekran.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(intro, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                // Surowy raport: przewijany, do zaznaczenia i skopiowania.
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    SelectionContainer {
                        Text(
                            report,
                            modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            softWrap = false
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    info ?: "Raport zawiera wersję aplikacji, model telefonu, opis błędu i ostatnie logi " +
                        "aplikacji - bez danych osobowych. Nic nie zostanie wysłane bez Twojej zgody. " +
                        "Przy zgłoszeniu e-mailem raport kopiuje się do schowka - wklej go w treści " +
                        "wiadomości. Zgłoszenie na GitHubie jest publiczne.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Raport do schowka, a w wiadomości prośba o wklejenie - pełne logi w treści
                    // e-maila (mailto / EXTRA_TEXT) programy pocztowe ucinały.
                    Button(
                        onClick = {
                            copy()
                            if (openEmail(context, CrashReport.emailSubject(report))) onDismiss()
                            else info = "Brak aplikacji pocztowej - raport skopiowano. Wyślij go na ${CrashReport.CONTACT_EMAIL}."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Zgłoś przez e-mail") }
                    OutlinedButton(
                        onClick = { if (openUrl(context, issueUrl)) onDismiss() else info = "Nie udało się otworzyć przeglądarki." },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Zgłoś na GitHubie") }
                    OutlinedButton(onClick = copy, modifier = Modifier.fillMaxWidth()) { Text("Kopiuj logi") }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Zamknij") }
                }
            }
        }
    }
}

/** Treść e-maila ze zgłoszeniem - raport jest w schowku (skopiowany przed otwarciem poczty). */
internal const val EMAIL_BODY =
    "Opisz, co się stało i co robiłeś/aś tuż przed tym:\n\n\n\n" +
        "--- Raport techniczny ---\n" +
        "Logi aplikacji zostały automatycznie skopiowane do schowka - wklej je tutaj (przytrzymaj palec i wybierz Wklej).\n"

/** Nowa wiadomość do dewelopera: adres, temat i prośba o wklejenie raportu ze schowka. */
fun openEmail(context: Context, subject: String): Boolean {
    val body = EMAIL_BODY
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(CrashReport.mailtoUri(subject, body)))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReport.CONTACT_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent); true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Pusta wiadomość do dewelopera (adres kontaktowy w "O aplikacji"). */
fun openContactEmail(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${CrashReport.CONTACT_EMAIL}?subject=${Uri.encode("Elektron-Vulcan")}"))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReport.CONTACT_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, "Elektron-Vulcan")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent); true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: ActivityNotFoundException) {
    false
}
