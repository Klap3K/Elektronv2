package pl.zse.bydgoszcz.elektron.presentation.calendar

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Web
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanBridge
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanWebSecurity
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanWebViewClient
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanProfileTile
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanWebAuthenticator
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun EduVulcanLoginDialog(
    onDismiss: () -> Unit,
    onSessionConnected: (EduVulcanSession) -> Unit,
    initialErrorMessage: String? = null
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var isLoggingIn by remember { mutableStateOf(false) }
    var isOpeningProfile by remember { mutableStateOf(false) }
    var showWebViewPreview by remember { mutableStateOf(false) }
    var forceShowInputs by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Wpisz login/e-mail oraz hasło do konta eduVULCAN") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var detectedProfiles by remember { mutableStateOf<List<EduVulcanProfileTile>>(emptyList()) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Automatyczne odpytywanie skryptu JS co 1.2s podczas trwającego logowania
    LaunchedEffect(isLoggingIn, username, password) {
        if (isLoggingIn) {
            var secondsElapsed = 0
            while (isLoggingIn) {
                delay(1200L)
                secondsElapsed++
                val js = EduVulcanWebAuthenticator.buildAutomationJs(
                    username = username.trim(),
                    password = password,
                    autoSelectSingleProfile = true
                )
                EduVulcanWebSecurity.evaluate(webViewRef, js)
                // Jeśli po 5 sekundach logowanie nadal trwa (np. pojawiła się Captcha lub wybór profilu),
                // automatycznie odsłaniamy podgląd strony i ukrywamy formularz, aby dać pełną przestrzeń do przewijania
                if (secondsElapsed == 4 && !showWebViewPreview && detectedProfiles.isEmpty() && !isOpeningProfile) {
                    showWebViewPreview = true
                    statusMessage = "Wybierz swój profil ucznia lub dokończ logowanie poniżej:"
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

    val inputsCollapsed = isLoggingIn && (showWebViewPreview || detectedProfiles.isNotEmpty()) && !forceShowInputs

    Dialog(
        onDismissRequest = { if (!isLoggingIn) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Logowanie do eduVULCAN",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (inputsCollapsed) {
                        TextButton(onClick = { forceShowInputs = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Dane konta")
                        }
                    }
                }

                if (!inputsCollapsed) {
                    if (!initialErrorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = initialErrorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Zaloguj się swoim kontem eduVULCAN (eduvulcan.pl), aby automatycznie pobierać oceny, sprawdziany, kartkówki i frekwencję.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            errorMessage = null
                        },
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

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
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
                }

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
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
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
                            Text(if (showWebViewPreview) "Ukryj stronę" else "Pokaż stronę")
                        }
                    }
                }

                // Natywna lista wykrytych profili ucznia / dziennika (kliknij bez konieczności przewijania strony!)
                if (isLoggingIn && detectedProfiles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Wykryte profile na koncie eduVULCAN (dotknij, aby wybrać):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(detectedProfiles, key = { it.url }) { profile ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        isOpeningProfile = true
                                        statusMessage = "Otwieranie profilu: ${profile.title}..."
                                        val quotedUrl = JSONObject.quote(profile.url)
                                        val openJs = """
                                            (function() {
                                                try {
                                                    if (typeof window.__evOpenProfileUrl === 'function') {
                                                        window.__evOpenProfileUrl($quotedUrl);
                                                    } else {
                                                        try {
                                                            sessionStorage.setItem('__evOpeningProfile', '1');
                                                            sessionStorage.setItem('__evLastTriedProfileUrl', $quotedUrl);
                                                        } catch(e) {}
                                                        window.location.assign($quotedUrl);
                                                    }
                                                } catch(e) {
                                                    window.location.assign($quotedUrl);
                                                }
                                            })();
                                        """.trimIndent()
                                        val wv = webViewRef
                                        if (wv != null) {
                                            EduVulcanWebSecurity.evaluate(wv, openJs)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AccountCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = profile.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (profile.subtitle.isNotBlank()) {
                                            Text(
                                                text = profile.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // Wbudowany WebView z pełną obsługą przewijania (requestDisallowInterceptTouchEvent + odblokowany overflow)
                if (isLoggingIn) {
                    val webViewHeight = if (detectedProfiles.isNotEmpty()) 300.dp else 440.dp
                    Box(
                        modifier = if (showWebViewPreview) {
                            Modifier
                                .fillMaxWidth()
                                .height(webViewHeight)
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
                                    isVerticalScrollBarEnabled = true
                                    isHorizontalScrollBarEnabled = true
                                    isScrollbarFadingEnabled = false

                                    // Kluczowe: zapobiega przechwytywaniu gestu przewijania przez nadrzędny Dialog Compose
                                    setOnTouchListener { v, _ ->
                                        v.parent?.requestDisallowInterceptTouchEvent(true)
                                        false
                                    }

                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)

                                    settings.apply {
                                        allowFileAccess = false
                                        allowContentAccess = false
                                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        javaScriptCanOpenWindowsAutomatically = true
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        userAgentString = EduVulcanWebAuthenticator.USER_AGENT
                                    }

                                    EduVulcanWebSecurity.installBridge(this,
                                        object : EduVulcanBridge {

                                            override fun onStatus(msg: String) {
                                                mainHandler.post {
                                                    statusMessage = msg
                                                }
                                            }


                                            override fun onProfilesFound(profilesJson: String) {
                                                mainHandler.post {
                                                    val profiles = EduVulcanWebAuthenticator.parseProfileTiles(profilesJson)
                                                    if (profiles.isNotEmpty()) {
                                                        detectedProfiles = profiles
                                                        if (profiles.size > 1) {
                                                            showWebViewPreview = true
                                                        }
                                                    }
                                                }
                                            }


                                            override fun onLoginError(err: String) {
                                                mainHandler.post {
                                                    errorMessage = err
                                                    isOpeningProfile = false
                                                    showWebViewPreview = true
                                                    forceShowInputs = true
                                                    statusMessage = "Sprawdź dane logowania lub dokończ w oknie poniżej"
                                                }
                                            }


                                            override fun onContextSuccess(domain: String, city: String, contextJson: String) {
                                                mainHandler.post {
                                                    if (!isLoggingIn) return@post
                                                    runCatching { CookieManager.getInstance().flush() }
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
                                                            isOpeningProfile = false
                                                            onSessionConnected(session)
                                                        },
                                                        onFailure = { e ->
                                                            isOpeningProfile = false
                                                            errorMessage = e.localizedMessage ?: "Błąd odczytu danych ucznia"
                                                            showWebViewPreview = true
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    )

                                    webChromeClient = WebChromeClient()
                                    webViewClient = object : EduVulcanWebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val js = EduVulcanWebAuthenticator.buildAutomationJs(
                                                username = username.trim(),
                                                password = password,
                                                autoSelectSingleProfile = true
                                            )
                                            EduVulcanWebSecurity.evaluate(view, js)
                                        }


                                    }

                                    webViewRef = this
                                    loadUrl(EduVulcanWebAuthenticator.LOGIN_URL)
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (isLoggingIn) {
                                isLoggingIn = false
                                isOpeningProfile = false
                                showWebViewPreview = false
                                forceShowInputs = false
                                detectedProfiles = emptyList()
                                runCatching { webViewRef?.stopLoading() }
                            } else {
                                onDismiss()
                            }
                        }
                    ) {
                        Text(if (isLoggingIn) "Wróć" else "Anuluj")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isLoggingIn) {
                            OutlinedButton(
                                onClick = {
                                    errorMessage = null
                                    isOpeningProfile = false
                                    showWebViewPreview = true
                                    forceShowInputs = false
                                    detectedProfiles = emptyList()
                                    statusMessage = "Zaloguj się i wybierz profil w poniższym oknie:"
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
                                isOpeningProfile = false
                                showWebViewPreview = false
                                forceShowInputs = false
                                detectedProfiles = emptyList()
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
