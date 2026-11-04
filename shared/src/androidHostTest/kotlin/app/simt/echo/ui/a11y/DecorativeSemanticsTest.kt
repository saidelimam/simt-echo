package app.simt.echo.ui.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.components.CosmosBanner
import app.simt.echo.ui.components.CosmosBannerKind
import app.simt.echo.ui.components.CosmosChip
import app.simt.echo.ui.components.CosmosChipTag
import app.simt.echo.ui.components.CosmosDeniedState
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.CosmosErrorState
import app.simt.echo.ui.components.CosmosOfflineState
import app.simt.echo.ui.components.CosmosSegmentedControl
import app.simt.echo.ui.components.CosmosSuccessState
import app.simt.echo.ui.screens.align.AlignScreen
import app.simt.echo.ui.screens.navigate.NavigateProgress
import app.simt.echo.ui.screens.review.FilmFrame
import app.simt.echo.ui.screens.review.FilmStrip
import app.simt.echo.ui.theme.EchoTheme
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
 * Review 0013 F2 (R13-02): a mark is not a message. State glyphs, chip
 * check marks, the banner mark, leg dots and the Align pip label are
 * decorative — each fact is already stated by a heading, a state or a
 * sentence — so none of them may become its own TalkBack node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class DecorativeSemanticsTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun stateMarksAreNotSpoken() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column {
                    CosmosEmptyState(title = "No walks yet", actionLabel = "Record", onAction = {})
                    CosmosDeniedState(title = "Camera is off", actionLabel = "Open settings", onAction = {})
                    CosmosOfflineState(title = "You are offline", actionLabel = "Open saved", onAction = {})
                    CosmosErrorState(message = "Offer failed", actionLabel = "Retry", onAction = {})
                    CosmosSuccessState(title = "Arrived")
                }
            }
        }
        compose.waitForIdle()
        listOf("○", "!", "i", "×", "✓").forEach { mark ->
            compose.onAllNodesWithText(mark, useUnmergedTree = true)
                .assertCountEquals(0)
        }
        compose.onNodeWithText("No walks yet").assertIsDisplayed()
        // The fifth state sits below the test viewport; existing is enough.
        compose.onNodeWithText("Arrived").assertExists()
    }

    @Test
    fun chipCheckMarksAreDecorativeButSelectionIsExposed() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column {
                    CosmosChip(label = "Landmarks", selected = true, onSelect = {})
                    CosmosSegmentedControl(
                        options = listOf("Walk", "Wheel"),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("✓", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("$CosmosChipTag:Landmarks").assertIsSelected()
    }

    @Test
    fun bannerNamesItsKindInWords() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosBanner(message = "Upload failed", kind = CosmosBannerKind.Error)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Error").assertIsDisplayed()
        compose.onAllNodesWithText("×", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Upload failed").assertIsDisplayed()
    }

    @Test
    fun legDotsAreDecorativeBecauseTheLegLineSaysIt() {
        compose.setContent {
            EchoTheme(darkTheme = true) { NavigateProgress(legIndex = 1, legCount = 3) }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("●", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("○", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Leg 2 of 3").assertIsDisplayed()
    }

    @Test
    fun filmStripCellIsOneMergedNode() {
        val frames = listOf(
            FilmFrame(nodeId = "n1", atDistanceM = 0.0, label = "Start"),
            FilmFrame(nodeId = "n2", atDistanceM = 12.0, label = "Turn"),
        )
        compose.setContent {
            EchoTheme(darkTheme = true) {
                FilmStrip(
                    frames = frames,
                    heroes = emptyMap(),
                    selectedNodeId = "n1",
                    onScrub = {},
                    onInspect = {},
                )
            }
        }
        compose.waitForIdle()
        val cell = compose.onNodeWithContentDescription(
            "Frame at 0 m, Start. Selected — activate again to inspect",
        ).fetchSemanticsNode()
        assertTrue(
            cell.config.isMergingSemanticsOfDescendants,
            "the film-strip cell must merge its index, star and distance children",
        )
    }

    @Test
    fun alignPipIsNamedOnce() {
        val dir = Files.createTempDirectory("r13-02-align").toString().toPath()
        val routes = RouteRepository(FileStore(FileSystem.SYSTEM, dir))
        routes.save(Fixtures.L_ROUTE)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                AlignScreen(
                    routeId = Fixtures.L_ROUTE.routeId,
                    routes = routes,
                    onAligned = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Live preview").assertCountEquals(1)
        compose.onAllNodesWithText("Live preview", useUnmergedTree = true).assertCountEquals(0)
    }
}
