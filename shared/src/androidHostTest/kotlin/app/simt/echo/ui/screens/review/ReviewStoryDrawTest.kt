package app.simt.echo.ui.screens.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.simt.echo.ai.MaskSubpath
import app.simt.echo.ai.SvgPoint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * W13-05 review fix: the keyframe inspector keeps the frame's aspect
 * (letterboxed, never stretched) and its pan stops at the zoomed bounds;
 * the AI mask outline is built once per canvas size, not per trace frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], manifest = Config.NONE)
class ReviewStoryDrawTest {

    // Fit.

    @Test
    fun fitContainLetterboxesAtTheContentAspect() {
        val wide = fitContain(content = Size(200f, 100f), viewport = Size(100f, 100f))
        assertEquals(Size(100f, 50f), wide.size)
        assertEquals(Offset(0f, 25f), wide.topLeft)

        val tall = fitContain(content = Size(100f, 200f), viewport = Size(100f, 100f))
        assertEquals(Size(50f, 100f), tall.size)
        assertEquals(Offset(25f, 0f), tall.topLeft)

        assertEquals(0f, fitContain(Size.Zero, Size(100f, 100f)).width, "no content, nothing to fit")
    }

    @Test
    fun drawFittedKeepsTheAspectInsteadOfStretching() {
        val image = ImageBitmap(20, 10)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(20f, 10f)) {
            drawRect(Color.Red)
        }
        val target = ImageBitmap(100, 100)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(100f, 100f)) {
            drawFitted(image)
        }
        val pixels = IntArray(100 * 100).also(target::readPixels)
        fun alpha(x: Int, y: Int) = (pixels[y * 100 + x] ushr 24) and 0xFF

        assertEquals(255, alpha(50, 50), "the frame fills the middle band")
        assertEquals(0, alpha(50, 10), "a 2:1 frame leaves the top letterbox empty, not stretched over it")
        assertEquals(0, alpha(50, 90), "...and the bottom")
    }

    // Pan bounds.

    @Test
    fun noPanAtOneTimesZoom() {
        val pan = clampPan(Offset(80f, -60f), scale = 1f, content = Size(100f, 50f), viewport = Size(100f, 100f))
        assertTrue(pan.x == 0f && pan.y == 0f, "nothing to pan at 1x, was $pan")
    }

    @Test
    fun panStopsWhereTheZoomedFrameMeetsTheViewport() {
        val content = Size(100f, 100f)
        val viewport = Size(100f, 100f)
        // 3x: content spans 300, so it may slide (300 - 100) / 2 = 100 either way.
        assertEquals(Offset(100f, -100f), clampPan(Offset(400f, -400f), 3f, content, viewport))
        assertEquals(Offset(40f, -20f), clampPan(Offset(40f, -20f), 3f, content, viewport))
    }

    @Test
    fun aLetterboxedAxisOnlyPansOnceTheZoomedFrameOutgrowsTheViewport() {
        // 2:1 frame in a square viewport: 100 x 50 fitted.
        val content = Size(100f, 50f)
        val viewport = Size(100f, 100f)
        // 1.5x → height 75 < 100: no vertical pan; width 150 → ±25 horizontally.
        assertEquals(Offset(25f, 0f), clampPan(Offset(90f, 90f), 1.5f, content, viewport))
        // 4x → height 200 → ±50 vertically.
        assertEquals(Offset(150f, 50f), clampPan(Offset(900f, 900f), 4f, content, viewport))
    }

    @Test
    fun zoomingOutPullsAnOverlongPanBackIn() {
        val content = Size(100f, 100f)
        val viewport = Size(100f, 100f)
        val atFive = clampPan(Offset(200f, 0f), 5f, content, viewport)
        assertEquals(200f, atFive.x)
        assertEquals(50f, clampPan(atFive, 2f, content, viewport).x)
    }

    // Mask outline.

    private val square = listOf(
        MaskSubpath(
            points = listOf(SvgPoint(0f, 0f), SvgPoint(10f, 0f), SvgPoint(10f, 10f), SvgPoint(0f, 10f)),
            closed = true,
        ),
    )

    @Test
    fun maskOutlineIsBuiltOncePerCanvasSize() {
        val outline = MaskOutline(square, maskW = 10, maskH = 10)
        val first = outline.pathsFor(Size(200f, 100f))
        assertSame(first, outline.pathsFor(Size(200f, 100f)), "every trace frame reuses the same paths")
        assertSame(first.single(), outline.pathsFor(Size(200f, 100f)).single())

        val resized = outline.pathsFor(Size(400f, 100f))
        assertNotSame(first, resized, "a new canvas size rebuilds")
        val bounds = resized.single().getBounds()
        assertEquals(400f, bounds.width, 1e-3f)
        assertEquals(100f, bounds.height, 1e-3f)
        assertTrue(first.single().getBounds().width < bounds.width)
    }
}
