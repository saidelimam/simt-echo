package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * W8-02 acceptance: the real [HomeScreen] renders under the frozen Cosmos
 * theme in both schemes (Deep Space dark + Daylight Nebula light), laying
 * out text with the bundled display family. Headless pixel capture
 * (captureToImage) returns an undrawn surface under Robolectric, so the
 * gate asserts the composed semantics per scheme instead of pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class EchoThemeHomeRenderTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun homeRendersInDeepSpaceScheme() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                HomeScreen(onOpen = {})
            }
        }
        compose.onNodeWithText("Walk it once. Share the way.").assertIsDisplayed()
        compose.onNodeWithText("Record").assertIsDisplayed()
        compose.onNodeWithText("Receive").assertIsDisplayed()
    }

    @Test
    fun homeRendersInDaylightScheme() {
        compose.setContent {
            EchoTheme(darkTheme = false) {
                HomeScreen(onOpen = {})
            }
        }
        compose.onNodeWithText("Walk it once. Share the way.").assertIsDisplayed()
        compose.onNodeWithText("Record").assertIsDisplayed()
        compose.onNodeWithText("Receive").assertIsDisplayed()
    }
}
