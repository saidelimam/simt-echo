package app.simt.echo.ui.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.ProvideCosmosMotionTier
import app.simt.echo.ui.shell.EchoChrome
import app.simt.echo.ui.shell.EchoShell
import app.simt.echo.ui.shell.EchoShellDeckTag
import app.simt.echo.ui.shell.EchoShellHeadingTag
import app.simt.echo.ui.shell.EchoShellReceiveTag
import app.simt.echo.ui.shell.EchoShellRecordTag
import app.simt.echo.ui.shell.shellConfigFor
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertTrue

/**
 * W8-12 accessibility & motion gate (verify, don't assert).
 *
 * Headless half of the gate, Robolectric + Compose test rule:
 * - fontScale 2.0 keeps chrome, heading and the Record/Receive deck
 *   visible on every route shell (display-size sweep + the manual
 *   TalkBack pass per primary screen are recorded as residual W9-12:
 *   no emulator is reachable here, `adb` is not installed);
 * - every chrome icon carries a TalkBack name and the page heading is
 *   marked as a heading on every route;
 * - Reduced/Static motion parity: the same commands in the same order
 *   complete under FULL, REDUCED and STATIC tiers.
 *
 * WCAG pairs for the shipped schemes stay pinned in commonTest
 * ([EchoThemeGatesTest][app.simt.echo.ui.theme.EchoThemeGatesTest]);
 * this gate does not re-assert hexes. Any failure reopens its screen
 * slice — this gate does not fix forward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class A11yMotionGateTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private val routes: List<EchoRoute> = listOf(
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

    @Test
    fun everyRoute_fontScale2_chromeHeadingAndDeckStayVisible() {
        // One setContent (rule contract): the route is state, driven at
        // fontScale 2.0, so every destination proves large-text survival
        // through the same shell composition the app ships.
        val big = Density(density = compose.density.density, fontScale = 2.0f)
        var route: EchoRoute by mutableStateOf(routes.first())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides big) {
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
        }
        routes.forEach { next ->
            compose.runOnIdle { route = next }
            compose.waitForIdle()
            val config = shellConfigFor(next)
            compose.onNodeWithTag(EchoShellHeadingTag).assertIsDisplayed()
            compose.onNodeWithText("body").assertIsDisplayed()
            when (config.chrome) {
                EchoChrome.BACK -> compose.onNodeWithContentDescription("Back").assertIsDisplayed()
                EchoChrome.CLOSE -> compose.onNodeWithContentDescription("Close").assertIsDisplayed()
                EchoChrome.NONE -> {
                    compose.onNodeWithContentDescription("Back").assertDoesNotExist()
                    compose.onNodeWithContentDescription("Close").assertDoesNotExist()
                }
            }
            if (config.showPrimaryDeck) {
                compose.onNodeWithTag(EchoShellDeckTag).assertIsDisplayed()
                compose.onNodeWithTag(EchoShellRecordTag).assertIsDisplayed()
                compose.onNodeWithTag(EchoShellReceiveTag).assertIsDisplayed()
            } else {
                compose.onNodeWithTag(EchoShellDeckTag).assertDoesNotExist()
                compose.onNodeWithTag(EchoShellRecordTag).assertDoesNotExist()
                compose.onNodeWithTag(EchoShellReceiveTag).assertDoesNotExist()
            }
        }
    }

    @Test
    fun everyRoute_chromeIconsNamedAndHeadingMarked() {
        // TalkBack audit at the shell level: a shown chrome icon always
        // has a content description, the gear is named, and the page
        // heading carries heading semantics for traversal order.
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
            val heading = compose.onNodeWithTag(EchoShellHeadingTag).fetchSemanticsNode()
            assertTrue(
                heading.config.contains(SemanticsProperties.Heading),
                "$next heading missing heading semantics for TalkBack traversal",
            )
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
            if (config.showSettings) {
                compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
            } else {
                compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
            }
        }
    }

    @Test
    fun motionParity_sameCommandsCompleteInEveryTier() {
        // Reduced/Static parity: tier only settles motion, never the task
        // list or its completion — Record then Receive fire in order under
        // every tier through the same composition.
        var tier by mutableStateOf(CosmosMotionTier.FULL)
        val fired = mutableListOf<String>()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = tier) {
                    EchoShell(
                        config = shellConfigFor(EchoRoute.Home),
                        onBack = {},
                        onClose = {},
                        onSettings = {},
                        onRecord = { fired += "record:$tier" },
                        onReceive = { fired += "receive:$tier" },
                    ) {
                        androidx.compose.material3.Text(text = "body")
                    }
                }
            }
        }
        for (next in CosmosMotionTier.entries) {
            tier = next
            compose.waitForIdle()
            compose.onNodeWithTag(EchoShellRecordTag).assertIsDisplayed()
            compose.onNodeWithTag(EchoShellReceiveTag).assertIsDisplayed()
            compose.onNodeWithText("body").assertIsDisplayed()
            compose.onNodeWithTag(EchoShellRecordTag).performClick()
            compose.onNodeWithTag(EchoShellReceiveTag).performClick()
        }
        assertTrue(
            fired == CosmosMotionTier.entries.flatMap { listOf("record:$it", "receive:$it") },
            "task order/completion changed under reduced motion: $fired",
        )
    }
}
