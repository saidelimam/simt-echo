package app.simt.echo.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W8-05 app-shell gate: one Cosmos shell owns the top chrome, the
 * transitions, safe-area clearance, the persistent Record + Receive deck,
 * and heading focus. Back and Close are never interchanged; every route
 * renders inside the shell with no screen-local top bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class EchoShellTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun setShell(
        route: EchoRoute,
        onBack: () -> Unit = {},
        onClose: () -> Unit = {},
        onSettings: () -> Unit = {},
        onRecord: () -> Unit = {},
        onReceive: () -> Unit = {},
        recordEnabled: Boolean = true,
        receiveEnabled: Boolean = true,
        body: String = "body",
    ) {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                EchoShell(
                    config = shellConfigFor(route),
                    onBack = onBack,
                    onClose = onClose,
                    onSettings = onSettings,
                    onRecord = onRecord,
                    onReceive = onReceive,
                    recordEnabled = recordEnabled,
                    receiveEnabled = receiveEnabled,
                ) {
                    androidx.compose.material3.Text(text = body)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun grammar_backAndCloseNeverInterchanged() {
        // Back routes: RouteDetail shows Back, never Close.
        assertEquals(EchoChrome.BACK, shellConfigFor(EchoRoute.RouteDetail("r")).chrome)
        // Close routes: Share/Settings show Close, never Back.
        assertEquals(EchoChrome.CLOSE, shellConfigFor(EchoRoute.Share("r")).chrome)
        assertEquals(EchoChrome.CLOSE, shellConfigFor(EchoRoute.Settings).chrome)
        // Root + terminal: neither.
        assertEquals(EchoChrome.NONE, shellConfigFor(EchoRoute.Home).chrome)
        assertEquals(EchoChrome.NONE, shellConfigFor(EchoRoute.Arrived("r")).chrome)
    }

    @Test
    fun grammar_titlesAndGearPerRoute() {
        assertTrue(shellConfigFor(EchoRoute.Home).title.isNotBlank())
        assertTrue(shellConfigFor(EchoRoute.Home).showSettings)
        assertFalse(shellConfigFor(EchoRoute.Settings).showSettings)
        assertFalse(shellConfigFor(EchoRoute.Navigate("r", false)).showSettings)
        assertFalse(shellConfigFor(EchoRoute.Arrived("r")).showSettings)
    }

    @Test
    fun backChrome_firesBackAndHidesClose() {
        var backed = false
        setShell(EchoRoute.RouteDetail("r"), onBack = { backed = true })
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }

    @Test
    fun closeChrome_firesCloseAndHidesBack() {
        var closed = false
        setShell(EchoRoute.Share("r"), onClose = { closed = true })
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun noneChrome_showsNeitherBackNorClose() {
        setShell(EchoRoute.Arrived("r"))
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        compose.onNodeWithText("Arrived").assertIsDisplayed()
    }

    @Test
    fun settingsGear_opensSettingsFromHome() {
        var opened = false
        setShell(EchoRoute.Home, onSettings = { opened = true })
        compose.onNodeWithText("ECHO").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun headingFocus_movesOnDestinationChange() {
        var route: EchoRoute by mutableStateOf<EchoRoute>(EchoRoute.Home)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                EchoShell(
                    config = shellConfigFor(route),
                    onBack = {},
                    onClose = {},
                    onSettings = {},
                    onRecord = {},
                    onReceive = {},
                ) {
                    androidx.compose.material3.Text(text = "body")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(EchoShellHeadingTag).assertTextEquals("Echo")
        compose.onNodeWithTag(EchoShellHeadingTag).assertIsFocused()
        compose.runOnIdle { route = EchoRoute.Record() }
        compose.waitForIdle()
        compose.onNodeWithTag(EchoShellHeadingTag).assertTextEquals("Record")
        compose.onNodeWithTag(EchoShellHeadingTag).assertIsFocused()
    }

    @Test
    fun primaryDeck_persistentRecordAndReceive() {
        var recorded = false
        var received = false
        // Persistent on an instrument screen, not just Home.
        setShell(
            EchoRoute.Navigate("r", false),
            onRecord = { recorded = true },
            onReceive = { received = true },
        )
        compose.onNodeWithTag(EchoShellDeckTag).assertIsDisplayed()
        compose.onNodeWithText("Record").assertIsDisplayed()
        compose.onNodeWithText("Receive").assertIsDisplayed()
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Receive").performClick()
        assertTrue(recorded)
        assertTrue(received)
    }

    @Test
    fun primaryDeck_disablesCurrentRouteAction() {
        setShell(EchoRoute.Record(), recordEnabled = false, receiveEnabled = true)
        compose.onNodeWithTag(EchoShellRecordTag).assertIsNotEnabled()
        compose.onNodeWithTag(EchoShellReceiveTag).assertIsEnabled()
    }

    @Test
    fun safeAreas_fontScale2_headingAndDeckStayVisible() {
        val big = Density(density = compose.density.density, fontScale = 2.0f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides big) {
                EchoTheme(darkTheme = true) {
                    EchoShell(
                        config = shellConfigFor(EchoRoute.RouteDetail("r")),
                        onBack = {},
                        onClose = {},
                        onSettings = {},
                        onRecord = {},
                        onReceive = {},
                    ) {
                        androidx.compose.material3.Text(text = "body")
                    }
                }
            }
        }
        compose.waitForIdle()
        // Chrome, heading, body and dock all clear each other at 2.0 scale:
        // nothing is pushed off-screen and the deck keeps 48 dp targets.
        compose.onNodeWithTag(EchoShellTopChromeTag).assertIsDisplayed()
        compose.onNodeWithTag(EchoShellHeadingTag).assertIsDisplayed()
        compose.onNodeWithText("body").assertIsDisplayed()
        compose.onNodeWithTag(EchoShellDeckTag).assertIsDisplayed()
        val minPx = with(compose.density) { 48.dp.roundToPx() }
        listOf(EchoShellRecordTag, EchoShellReceiveTag).forEach { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue(node.size.height >= minPx, "$tag height below 48dp at fontScale 2.0")
            assertTrue(node.size.width >= minPx, "$tag width below 48dp at fontScale 2.0")
        }
    }

    @Test
    fun everyRoute_rendersInsideShellWithChrome() {
        val routes: List<EchoRoute> = listOf(
            EchoRoute.Home,
            EchoRoute.Record(),
            EchoRoute.Review("d"),
            EchoRoute.Processing("d"),
            EchoRoute.RouteDetail("r"),
            EchoRoute.Share("r"),
            EchoRoute.Receive,
            EchoRoute.Align("r"),
            EchoRoute.Navigate("r", false),
            EchoRoute.Arrived("r"),
            EchoRoute.Settings,
        )
        routes.forEach { route ->
            assertTrue(shellConfigFor(route).title.isNotBlank(), "$route needs a shell heading")
        }
        // One setContent per test (AndroidComposeTestRule contract): the
        // route is state, so every destination renders through the same
        // shell composition the app ships.
        var route: EchoRoute by mutableStateOf(routes.first())
        compose.setContent {
            EchoTheme(darkTheme = true) {
                EchoShell(
                    config = shellConfigFor(route),
                    onBack = {},
                    onClose = {},
                    onSettings = {},
                    onRecord = {},
                    onReceive = {},
                ) {
                    androidx.compose.material3.Text(text = "body")
                }
            }
        }
        routes.forEach { next ->
            compose.runOnIdle { route = next }
            compose.waitForIdle()
            val config = shellConfigFor(next)
            compose.onNodeWithTag(EchoShellTag).assertIsDisplayed()
            compose.onNodeWithTag(EchoShellWordmarkTag).assertIsDisplayed()
            compose.onNodeWithTag(EchoShellHeadingTag).assertTextEquals(config.title)
            when (config.chrome) {
                EchoChrome.BACK -> {
                    compose.onNodeWithContentDescription("Back").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Close").assertDoesNotExist()
                }
                EchoChrome.CLOSE -> {
                    compose.onNodeWithContentDescription("Close").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                }
                EchoChrome.NONE -> {
                    compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                    compose.onNodeWithContentDescription("Close").assertDoesNotExist()
                }
            }
            compose.onNodeWithTag(EchoShellDeckTag).assertIsDisplayed()
        }
    }

    @Test
    fun transitions_staticSettlesImmediately_reducedShorterThanFull() {
        assertEquals(0, shellTransitionDurationMs(CosmosMotionTier.STATIC))
        val reduced = shellTransitionDurationMs(CosmosMotionTier.REDUCED)
        val full = shellTransitionDurationMs(CosmosMotionTier.FULL)
        assertTrue(reduced > 0 && full > reduced)
        assertEquals(
            androidx.compose.animation.EnterTransition.None,
            echoEnterTransition(CosmosMotionTier.STATIC),
        )
        assertEquals(
            androidx.compose.animation.ExitTransition.None,
            echoExitTransition(CosmosMotionTier.STATIC),
        )
    }
}
