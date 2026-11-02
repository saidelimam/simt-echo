package app.simt.echo.ui.screens.record

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W13-04 review fix: a step ripple leaves from an outer 20 % band and
 * travels to 0.6 × the short edge, which would cross the centre — the
 * spec says the centre 60 % (copy + instrument) is never painted. Drawn
 * with native graphics through the real [drawStepRipples].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], manifest = Config.NONE)
class StepRipplesDrawTest {

    private val width = 300
    private val height = 600

    private fun render(ripples: List<StepRipple>, ageMs: Long): IntArray {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(width.toFloat(), height.toFloat()),
        ) {
            drawStepRipples(ripples, nowMs = ageMs, color = Color.Red, strokePx = 4f)
        }
        return IntArray(width * height).also(bitmap::readPixels)
    }

    private fun inkInColumns(pixels: IntArray, fromX: Int, toX: Int): Int {
        var ink = 0
        for (y in 0 until height) for (x in fromX until toX) if ((pixels[y * width + x] ushr 24) != 0) ink++
        return ink
    }

    @Test
    fun ripplesNeverPaintTheCentreSixtyPercent() {
        val ripples = listOf(
            StepRipple(id = 0, leftEdge = true, startMs = 0L),
            StepRipple(id = 1, leftEdge = false, startMs = 0L),
        )
        // Mid-flight: the unclipped ring (≈ 0.96 × 0.6 × 300 px) reaches well past the band.
        val pixels = render(ripples, ageMs = 600L)

        val bandPx = (width * RIPPLE_BAND_FRACTION).toInt()
        assertEquals(0, inkInColumns(pixels, bandPx + 1, width - bandPx - 1), "centre 60 % must stay clear")
        assertTrue(inkInColumns(pixels, 0, bandPx) > 0, "the left ring still shows in its band")
        assertTrue(inkInColumns(pixels, width - bandPx, width) > 0, "the right ring still shows in its band")
    }

    @Test
    fun aFinishedRippleDrawsNothing() {
        val pixels = render(listOf(StepRipple(id = 0, leftEdge = true, startMs = 0L)), ageMs = 5_000L)
        assertEquals(0, inkInColumns(pixels, 0, width))
    }
}
