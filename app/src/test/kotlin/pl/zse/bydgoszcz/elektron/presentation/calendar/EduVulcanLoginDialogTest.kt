package pl.zse.bydgoszcz.elektron.presentation.calendar

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EduVulcanLoginDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginRequiresCredentialsAndPasswordIsHiddenByDefault() {
        compose.setContent { MaterialTheme { EduVulcanLoginDialog({}, {}) } }
        compose.onNodeWithText("Zaloguj się").assertIsNotEnabled()
        compose.onNodeWithText("Login lub e-mail eduVULCAN").performTextInput("test@example.test")
        compose.onNodeWithText("Zaloguj się").assertIsNotEnabled()
        compose.onNodeWithText("Hasło").performTextInput("test-password")
        compose.onNodeWithText("Hasło").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Zaloguj się").assertIsEnabled()
        compose.onNodeWithContentDescription("Pokaż hasło").performClick()
        compose.onNodeWithContentDescription("Ukryj hasło").assertIsEnabled()
    }

    @Test fun cancelDoesNotConnectAnAccount() {
        var dismissed = false
        var connected = false
        compose.setContent { MaterialTheme { EduVulcanLoginDialog({ dismissed = true }, { connected = true }) } }
        compose.onNodeWithText("Anuluj").performClick()
        assertTrue(dismissed)
        assertTrue(!connected)
    }

    @Test fun initialErrorMessageIsDisplayedWhenProvided() {
        compose.setContent {
            MaterialTheme {
                EduVulcanLoginDialog(
                    onDismiss = {},
                    onSessionConnected = {},
                    initialErrorMessage = "Twoja sesja eduVULCAN wygasła. Zaloguj się ponownie."
                )
            }
        }
        compose.onNodeWithText("Twoja sesja eduVULCAN wygasła. Zaloguj się ponownie.").assertExists()
    }
}
