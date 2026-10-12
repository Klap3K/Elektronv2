package pl.zse.bydgoszcz.elektron.presentation.calendar

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanWebAuthenticator
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EduVulcanLoginDialog(
    onDismiss: () -> Unit,
    onSessionConnected: (EduVulcanSession) -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var isLoggingIn by remember { mutableStateOf(false) }
    var showWebViewPreview by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Wpisz login/e-mail oraz hasło do konta eduVULCAN") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Automatyczne odpytywanie skryptu JS co 1.2s podczas trwającego logowania
    LaunchedEffect(isLoggingIn, username, password) {
        if (isLoggingIn) {
            var secondsElapsed = 0
            while (isLoggingIn) {
                delay(1200L)
                secondsElapsed++
                val js = EduVulcanWebAuthenticator.buildAutomationJs(username.trim(), password)
                webViewRef?.evaluateJavascript(js, null)
                // Jeśli po 6 sekundach logowanie nadal trwa (np. pojawiła się Captcha lub wybór ucznia),
                // automatycznie odsłaniamy podgląd strony, aby użytkownik mógł kliknąć
                if (secondsElapsed == 5 && !showWebViewPreview) {
                    showWebViewPreview = true
                    statusMessage = "Jeśli widzisz zabezpieczenie CAPTCHA lub wybór ucznia, kliknij poniżej:"
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isLoggingIn) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Logowanie do eduVULCAN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Zaloguj się swoim kontem eduVULCAN (dziennik.vulcan.edu.pl), aby automatycznie pobierać sprawdziany, kartkówki, zadania domowe oraz frekwencję.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    enabled = !isLoggingIn || showWebViewPreview,
                    label = { Text("Login lub e-mail eduVULCAN") },
                    placeholder = { Text("np. jan.kowalski@...") },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    enabled = !isLoggingIn || showWebViewPreview,
                    label = { Text("Hasło") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "Ukryj hasło" else "Pokaż hasło"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isLoggingIn) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showWebViewPreview = !showWebViewPreview }) {
                            Icon(Icons.Outlined.Web, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (showWebViewPreview) "Ukryj okno" else "Podgląd")
                        }
                    }
                }

                // Wbudowany WebView (ukryty 1dp podczas automatycznego logowania lub widoczny przy Captcha / ręcznym logowaniu)
                if (isLoggingIn) {
                    Box(
                        modifier = if (showWebViewPreview) {
                            Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        } else {
                            Modifier.size(1.dp)
                        }
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        userAgentString = EduVulcanWebAuthenticator.USER_AGENT
                                    }

                                    addJavascriptInterface(
                                        object {
                                            @JavascriptInterface
                                            fun onStatus(msg: String) {
                                                mainHandler.post {
                                                    statusMessage = msg
                                                }
                                            }

                                            @JavascriptInterface
                                            fun onLoginError(err: String) {
                                                mainHandler.post {
                                                    errorMessage = err
                                                    showWebViewPreview = true
                                                    statusMessage = "Sprawdź dane logowania lub dokończ w oknie poniżej"
                                                }
                                            }

                                            @JavascriptInterface
                                            fun onContextSuccess(domain: String, city: String, contextJson: String) {
                                                mainHandler.post {
                                                    val parsed = EduVulcanWebAuthenticator.parseContextJson(
                                                        domain = domain,
                                                        symbol = city,
                                                        contextJson = contextJson,
                                                        loginUsername = username.trim(),
                                                        loginPassword = password
                                                    )
                                                    parsed.fold(
                                                        onSuccess = { session ->
                                                            isLoggingIn = false
                                                            onSessionConnected(session)
                                                        },
                                                        onFailure = { e ->
                                                            errorMessage = e.localizedMessage ?: "Błąd odczytu danych ucznia"
                                                            showWebViewPreview = true
                                                        }
                                                    )
                                                }
                                            }
                                        },
                                        "AndroidEduVulcan"
                                    )

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val js = EduVulcanWebAuthenticator.buildAutomationJs(
                                                username.trim(),
                                                password
                                            )
                                            view?.evaluateJavascript(js, null)
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean = false
                                    }

                                    webViewRef = this
                                    loadUrl(EduVulcanWebAuthenticator.LOGIN_URL)
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (isLoggingIn) {
                                isLoggingIn = false
                                showWebViewPreview = false
                                runCatching { webViewRef?.stopLoading() }
                            } else {
                                onDismiss()
                            }
                        }
                    ) {
                        Text(if (isLoggingIn) "Przerwij" else "Anuluj")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isLoggingIn) {
                            OutlinedButton(
                                onClick = {
                                    errorMessage = null
                                    showWebViewPreview = true
                                    statusMessage = "Zaloguj się w poniższym oknie eduVULCAN:"
                                    isLoggingIn = true
                                }
                            ) {
                                Text("Okno przeglądarki")
                            }
                        }

                        Button(
                            enabled = !isLoggingIn && username.isNotBlank() && password.isNotBlank(),
                            onClick = {
                                errorMessage = null
                                showWebViewPreview = false
                                statusMessage = "Łączenie z eduvulcan.pl..."
                                isLoggingIn = true
                            }
                        ) {
                            Text("Zaloguj się")
                        }
                    }
                }
            }
        }
    }
}
