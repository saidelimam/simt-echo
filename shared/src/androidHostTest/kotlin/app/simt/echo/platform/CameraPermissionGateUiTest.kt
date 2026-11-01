package app.simt.echo.platform

import android.Manifest
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.simt.echo.host.RobolectricActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Headless permission-gate verification (W6-03/W7-02, plan W4-03).
 *
 * Drives the real [CameraPermissionGate] under Robolectric: first-run
 * shows the rationale (never a denial), a granted OS permission renders
 * the gated content. No test doubles — the Android actual of
 * `rememberCameraPermissionState` reads the shadow package manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class CameraPermissionGateUiTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun notDetermined_showsRationaleNotContent() {
        compose.setContent {
            CameraPermissionGate { Text("secret-content") }
        }
        compose.onNodeWithText("Camera needed").assertIsDisplayed()
        compose.onNodeWithText(cameraRationale()).assertIsDisplayed()
        compose.onNodeWithText(cameraNotDeterminedMessage()).assertIsDisplayed()
        compose.onNodeWithText("Allow camera").assertIsDisplayed()
        compose.onNodeWithText("secret-content").assertDoesNotExist()
    }

    @Test
    fun granted_rendersContent() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.CAMERA)
        compose.setContent {
            CameraPermissionGate { Text("secret-content") }
        }
        compose.onNodeWithText("secret-content").assertIsDisplayed()
        compose.onNodeWithText("Camera needed").assertDoesNotExist()
    }
}
