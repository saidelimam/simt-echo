package app.simt.echo.ui.screens.navigate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.EchoConfig
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.components.CameraNavigateOverlay
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.LandmarkCard
import app.simt.echo.ui.components.LandmarkImageSlotTag
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
 * Review 0013 F4 (R13-04): at fontScale 2.0 the walker still sees the
 * commands. The HUD body scrolls, the actions are pinned, the instruction
 * is a heading, the relock ring is named, and a card that already shows a
 * crop does not stack a second empty image slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    shadows = [HostTestResourcesShadow::class],
    qualifiers = "w411dp-h891dp",
)
class NavigateFontScaleTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private val big: Density
        get() = Density(compose.density.density, fontScale = 2.0f)

    @Test
    fun cameraHudScrollsItsBodyAndPinsTheActions() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides big) {
                EchoTheme(darkTheme = true) {
                    CameraNavigateOverlay(
                        arrowRad = 0f,
                        instruction = "Turn right at the pillar",
                        distanceText = "23.4 m",
                        uncertaintyText = "±1.2 m",
                        offline = false,
                        landmarkTitle = "Pillar",
                        landmarkCue = "Red pillar on the left, past the door",
                        landmarkStats = "in ~12 m",
                        cropBytes = null,
                        cropDescription = null,
                        progress01 = 0.3f,
                        legText = "Leg 1 of 3",
                        scrimAlpha = 0.5f,
                        preview = {},
                        actions = { CosmosPrimaryButton(label = "I see it", onClick = {}) },
                        arrow = { Box(Modifier.size(200.dp)) },
                        distance = { Text(text = "23.4 m") },
                        landmarkCard = {
                            LandmarkCard(
                                title = "Pillar",
                                cue = "Red pillar",
                                stats = "in ~12 m",
                                showImageSlot = false,
                            )
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        // Pinned: the primary command needs no scrolling.
        compose.onNodeWithText("I see it").assertIsDisplayed()
        // The leg line is above the fold at this scale and scroll reaches it.
        compose.onNodeWithText("Leg 1 of 3").performScrollTo().assertIsDisplayed()
        val instruction = compose.onNodeWithText("Turn right at the pillar").fetchSemanticsNode()
        assertTrue(instruction.config.contains(SemanticsProperties.Heading))
    }

    @Test
    fun relockRingNamesItsProgress() {
        compose.setContent {
            EchoTheme(darkTheme = true) { RelockRing(state = NavigateRelockState(0.4)) }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Relocking, 40 percent").assertExists()
    }

    @Test
    fun aCardThatAlreadyShowsACropHasNoSecondImageSlot() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column {
                    LandmarkCard(title = "With slot", cue = "cue", stats = null)
                    LandmarkCard(
                        title = "Crop above",
                        cue = "cue",
                        stats = null,
                        showImageSlot = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(LandmarkImageSlotTag).assertCountEquals(1)
    }

    @Test
    fun realNavigateScreenKeepsItsActionsAtFontScaleTwo() {
        val dir = Files.createTempDirectory("r13-04-navigate").toString().toPath()
        val routes = RouteRepository(FileStore(FileSystem.SYSTEM, dir))
        routes.save(Fixtures.L_ROUTE)
        val motion = object : MotionSource {
            override val updates = MutableSharedFlow<MotionUpdate>()
            override fun start() = Unit
            override fun stop() = Unit
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides big) {
                EchoTheme(darkTheme = true) {
                    NavigateScreen(
                        routeId = Fixtures.L_ROUTE.routeId,
                        routes = routes,
                        onArrived = {},
                        onBack = {},
                        config = EchoConfig(cameraOverlayEnabled = false),
                        motionSource = motion,
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(NavigateReticleTag).fetchSemanticsNodes().isNotEmpty()
        }
        // Whichever phase the unstarted walker is in, its commands are
        // visible — the old fixed Column pushed them off-screen here.
        val labels = listOf(
            "I see it", "Not yet", "Lost", "Go back to last", "Restart from start", "I'm here",
        )
        val present = labels.filter {
            compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(present.isNotEmpty(), "no navigate action rendered; expected one of $labels")
        compose.onNodeWithText(present.first()).assertIsDisplayed()
    }
}
