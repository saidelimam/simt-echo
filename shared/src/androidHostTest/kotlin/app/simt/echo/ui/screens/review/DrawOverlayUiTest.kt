package app.simt.echo.ui.screens.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.media.CropBox
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

/**
 * Headless draw-gesture verification (W6-03/W7-02, plan W2-01).
 *
 * Renders the real [DrawOverlay] with a decoded keyframe, performs a
 * drag, confirms — the reported [CropBox] must equal the normalised drag
 * rect (0..1, edges ordered). The mapping oracle here is hand-computed,
 * independent of the production formula.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
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
        // The confirm/clear row overlaps the top of the canvas (both are
        // top-left in the BoxWithConstraints), so drag the lower area where
        // the gesture reaches the canvas itself. Segmented moves keep the
        // reported box spanning start→end rather than collapsing to one
        // slop-crossing jump.
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
        compose.onNodeWithText("Clear").assertIsEnabled()
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

    companion object {
        /** Real 16x16 RGB PNG (decodes via BitmapFactory under Robolectric). */
        const val TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAABlklEQVR4nBXRURVEIQhFUSMYgQhGMAIRiGCEE8EIRiACEYhABCLMG7/ZrMt1jMEcyGAN9kAHNjgDBnfwBj6IQQ5q0IMxJnMikzXZE53Y5EyY3Mmb+CQmOalJzw8IUxBhCVtQwYQjIFzhCS6EkEIJLR9YzIUs1mIvdGGLs2BxF2/hi1jkoha9PrCZG9mszd7oxjZnw+Zu3sY3sclNbXp/QJmKKEvZiiqmHAXlKk9xJZRUSmn9gDENMZaxDTXMOAbGNZ7hRhhplNH2gcM8yGEd9kEPdjgHDvfwDn6IQx7q0OcD/wK/Sr4jv9hfkG/1N/x/Fx44BCQU9Pc94zIvclmXfdGLXc79j9/Lu/glLnmpS98PPOZDHuuxH/qwx3n/5ffxHv6IRz7q0e8DznTEWc521DHn+D/KdZ7jTjjplNP+gWAGEqxgBxpYcOIf/AYv8CCCDCro+EAyE0lWshNNLDn5P/MmL/Ekkkwq6fxAMQspVrELLaw49S/lFq/wIoosquj6QDMbaVazG22sOf2v8Dav8SaabKrp5geIAnAQEbP2+wAAAABJRU5ErkJggg=="
    }
}
