package app.simt.echo.ui.screens.navigate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.EchoConfig
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertTrue

/**
 * Review 0013 F5 follow-up: with the camera overlay on, a missing camera
 * permission is its own screen — not a `CosmosDeniedState` painted under the
 * navigate scrim and pinned actions — and refusing still leaves the walker a
 * sensor-only way to navigate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    shadows = [HostTestResourcesShadow::class],
    qualifiers = "w411dp-h891dp",
)
class NavigatePermissionTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun routes(): RouteRepository {
        val dir = Files.createTempDirectory("r13-10-navigate").toString().toPath()
        return RouteRepository(FileStore(FileSystem.SYSTEM, dir)).also { it.save(Fixtures.L_ROUTE) }
    }

    private fun motion(): MotionSource = object : MotionSource {
        override val updates = MutableSharedFlow<MotionUpdate>()
        override fun start() = Unit
        override fun stop() = Unit
    }

    private fun screen(routes: RouteRepository) {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                NavigateScreen(
                    routeId = Fixtures.L_ROUTE.routeId,
                    routes = routes,
                    onArrived = {},
                    onBack = {},
                    config = EchoConfig(cameraOverlayEnabled = true),
                    motionSource = motion(),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun overlayWithoutPermissionShowsTheGateInsteadOfTheHud() {
        screen(routes())
        compose.onNodeWithText("Camera needed").assertIsDisplayed()
        compose.onNodeWithText("Continue without camera").assertIsDisplayed()
        // The HUD must not compose over the denial: no reticle, no actions.
        compose.onNodeWithTag(NavigateReticleTag).assertDoesNotExist()
        compose.onNodeWithText("I see it").assertDoesNotExist()
    }

    @Test
    fun continuingWithoutCameraKeepsNavigationReachable() {
        screen(routes())
        compose.onNodeWithText("Continue without camera").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(NavigateReticleTag).fetchSemanticsNodes().isNotEmpty()
        }
        val labels = listOf(
            "I see it", "Not yet", "Lost", "Go back to last", "Restart from start", "I'm here",
        )
        val present = labels.filter {
            compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(present.isNotEmpty(), "sensor-only navigation rendered no command; expected one of $labels")
        compose.onNodeWithText(present.first()).assertIsDisplayed()
    }
}
