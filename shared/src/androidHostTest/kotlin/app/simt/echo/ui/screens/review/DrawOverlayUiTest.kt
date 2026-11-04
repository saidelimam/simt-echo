package app.simt.echo.ui.screens.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.media.CropBox
import app.simt.echo.Fixtures
import app.simt.echo.ui.components.CosmosButtonTag
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless draw-gesture verification (W6-03/W7-02, plan W2-01).
 *
 * Renders the real [DrawOverlay] with a decoded keyframe, performs a
 * drag, confirms — the reported [CropBox] must equal the normalised drag
 * rect (0..1, edges ordered). The mapping oracle here is hand-computed,
 * independent of the production formula.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    shadows = [HostTestResourcesShadow::class],
    // Phone-sized window: the overlay's canvas is a weight below its form,
    // so the default 470 dp Robolectric window leaves too little room for
    // the drag the test is about.
    qualifiers = "w411dp-h891dp",
)
class DrawOverlayUiTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun dragConfirm_reportsNormalizedCropBox() {
        var captured: CropBox? = null
        val heroBytes = Base64.decode(TEST_PNG_BASE64)
        compose.setContent {
            DrawOverlay(
                nodeId = "n1",
                nodeLabel = "Turn right",
                heroBytes = heroBytes,
                title = "Pillar",
                onTitle = {},
                onConfirm = { _, box -> captured = box },
                onCancel = {},
            )
        }
        // Action buttons sit below the canvas, never overlapping the photo.
        val canvasBounds = compose.onNodeWithTag(DrawCanvasTag).getUnclippedBoundsInRoot()
        // The name field now also offers a Clear button, so address the
        // overlay's own command by tag.
        val clearNode = compose.onNodeWithTag("$CosmosButtonTag:Clear")
        val clearBounds = clearNode.getUnclippedBoundsInRoot()
        val useBoxBounds = compose.onNodeWithText("Use box").getUnclippedBoundsInRoot()
        assertTrue(clearBounds.top >= canvasBounds.bottom, "Clear button must sit below the canvas")
        assertTrue(useBoxBounds.top >= canvasBounds.bottom, "Use box button must sit below the canvas")

        val from = Offset(0.1f, 0.55f)
        val to = Offset(0.5f, 0.9f)
        compose.onNodeWithTag(DrawCanvasTag).performTouchInput {
            val start = Offset(width * from.x, height * from.y)
            val end = Offset(width * to.x, height * to.y)
            down(0, start)
            repeat(10) { i ->
                val t = (i + 1) / 10f
                moveTo(Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t))
            }
            up(0)
        }
        // The drag must arm the confirm action; a disabled "Use box" is a
        // no-op click, so assert the gesture registered before confirming.
        clearNode.assertIsEnabled()
        compose.onNodeWithText("Use box").performClick()

        val box = assertNotNull(captured, "confirm must report the drawn box")
        // Start edges carry the touch-slop offset (onDragStart fires at the
        // slop-crossing point, ~16 px on a ~272x161 canvas); end edges are
        // exact. A broken mapping (raw px, swapped axes, unclamped) fails
        // these by an order of magnitude, not by slop.
        assertEquals(0.1f, box.left, 0.12f)
        assertEquals(0.55f, box.top, 0.12f)
        assertEquals(0.5f, box.right, 0.03f)
        assertEquals(0.9f, box.bottom, 0.03f)
    }

    @Test
    fun startCard_marksSelectedWhenFirstFrameIsSelected() {
        compose.setContent {
            app.simt.echo.ui.theme.EchoTheme(darkTheme = true) {
                StartCard(
                    description = "Red door",
                    heroBytes = null,
                    onDescription = {},
                    selected = true,
                )
            }
        }
        compose.onNodeWithContentDescription("Start selected").assertIsDisplayed()
    }

    @Test
    fun reviewScreen_displaysOnlySelectedFrameDetailsAndSwitchesOnNext() {
        val dir = java.nio.file.Files.createTempDirectory("review-single-frame").toString()
        val store = app.simt.echo.data.FileStore(okio.FileSystem.SYSTEM, dir.toPath())
        val drafts = app.simt.echo.data.DraftRepository(store)
        val routes = app.simt.echo.data.RouteRepository(store)
        val route = Fixtures.L_ROUTE.copy(
            routeId = "draft-single",
            name = "Morning commute",
        )
        drafts.saveDraftRoute(route)
        val media = object : app.simt.echo.media.MediaTools {
            override suspend fun extractFrame(video: okio.Path, atMs: Long, maxSide: Int, out: okio.Path) = Unit
            override suspend fun cropJpeg(input: okio.Path, box: CropBox, maxSide: Int, out: okio.Path) = Unit
            override suspend fun cutClip(
                video: okio.Path,
                startMs: Long,
                endMs: Long,
                maxFps: Int,
                out: okio.Path,
            ) = app.simt.echo.media.ClipInfo(out, 0, 0L)
        }
        val vm = ReviewViewModel(
            draftId = "draft-single",
            drafts = drafts,
            routes = routes,
            media = media,
            nowMs = { 0L },
            newId = { "lm-1" },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        compose.setContent {
            app.simt.echo.ui.theme.EchoTheme(darkTheme = true) {
                ReviewScreen(
                    viewModel = vm,
                    onSaved = {},
                    onBack = {},
                    onSaveDraft = {},
                )
            }
        }
        compose.waitForIdle()

        // Initially the first frame (Start) is selected: StartCard is shown,
        // while non-selected NodeCard controls ("Draw landmark", "Spoken cue") are not.
        compose.onNodeWithContentDescription("Start selected").assertExists()
        compose.onNodeWithText("Start description").assertExists()
        compose.onNodeWithText("Draw landmark").assertDoesNotExist()
        compose.onNodeWithText("End").assertExists()

        // Step to the next frame: now only that node's card is rendered.
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Start description").assertDoesNotExist()
        compose.onNodeWithText("Draw landmark").assertExists()
    }

    companion object {
        /** Real 16x16 RGB PNG (decodes via BitmapFactory under Robolectric). */
        const val TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAABlklEQVR4nBXRURVEIQhFUSMYgQhGMAIRiGCEE8EIRiACEYhABCLMG7/ZrMt1jMEcyGAN9kAHNjgDBnfwBj6IQQ5q0IMxJnMikzXZE53Y5EyY3Mmb+CQmOalJzw8IUxBhCVtQwYQjIFzhCS6EkEIJLR9YzIUs1mIvdGGLs2BxF2/hi1jkoha9PrCZG9mszd7oxjZnw+Zu3sY3sclNbXp/QJmKKEvZiiqmHAXlKk9xJZRUSmn9gDENMZaxDTXMOAbGNZ7hRhhplNH2gcM8yGEd9kEPdjgHDvfwDn6IQx7q0OcD/wK/Sr4jv9hfkG/1N/x/Fx44BCQU9Pc94zIvclmXfdGLXc79j9/Lu/glLnmpS98PPOZDHuuxH/qwx3n/5ffxHv6IRz7q0e8DznTEWc521DHn+D/KdZ7jTjjplNP+gWAGEqxgBxpYcOIf/AYv8CCCDCro+EAyE0lWshNNLDn5P/MmL/Ekkkwq6fxAMQspVrELLaw49S/lFq/wIoosquj6QDMbaVazG22sOf2v8Dav8SaabKrp5geIAnAQEbP2+wAAAABJRU5ErkJggg=="
    }
}
