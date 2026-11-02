package app.simt.echo.ui.screens.navigate

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W13-08 review fix: the σ aura is a translucent halo whose DRAWN extent
 * tracks σ (not a same-colour disc clamped to one size), and the glyph
 * keeps a ≥ 3:1 outline against any tint. Rendered with native graphics
 * through the real draw helpers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], manifest = Config.NONE)
class NavigateAuraDrawTest {

    private val px = AURA_CANVAS_DP.toInt()
    private val lime = Color(0xFFB8F04A)

    private fun renderAura(sigmaM: Double, dimChrome: Boolean = false): IntArray {
        val bitmap = ImageBitmap(px, px)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
            drawSigmaAura(
                color = lime,
                radiusPx = auraRadiusDpFor(sigmaM),
                softness01 = auraSoftnessFor(sigmaM),
                dimChrome = dimChrome,
                dashed = false,
            )
        }
        return IntArray(px * px).also(bitmap::readPixels)
    }

    private fun alphaAt(pixels: IntArray, x: Int, y: Int): Int = (pixels[y * px + x] ushr 24) and 0xFF

    /** Furthest distance from the centre, along the +x ray, that carries any ink. */
    private fun drawnRadius(pixels: IntArray): Int {
        val cy = px / 2
        var extent = 0
        for (x in px / 2 until px) if (alphaAt(pixels, x, cy) > 0) extent = x - px / 2
        return extent
    }

    @Test
    fun drawnAuraRadiusTracksSigma() {
        val tight = drawnRadius(renderAura(sigmaM = 0.5))
        val loose = drawnRadius(renderAura(sigmaM = 5.5))
        assertTrue(loose - tight >= 15, "drawn radius must grow with σ: $tight px vs $loose px")
        assertTrue(abs(tight - auraRadiusDpFor(0.5).toInt()) <= 3, "tight σ draws its own radius, got $tight px")
        assertTrue(abs(loose - auraRadiusDpFor(5.5).toInt()) <= 3, "loose σ draws its own radius, got $loose px")
    }

    @Test
    fun auraIsAHaloNeverAnOpaqueDisc() {
        for (sigma in listOf(0.0, 1.0, 3.0, 6.0)) {
            val pixels = renderAura(sigmaM = sigma)
            val peak = pixels.maxOf { (it ushr 24) and 0xFF }
            assertTrue(peak <= (AURA_MAX_ALPHA * 255f).toInt() + 1, "σ=$sigma peak alpha $peak must stay a tint")
            assertTrue(peak > 0, "σ=$sigma still shows an aura")
        }
        assertTrue(auraAlphaFor(0f, dimChrome = true) < auraAlphaFor(0f, dimChrome = false), "calm dims the aura")
    }

    @Test
    fun softnessDrivesTheFalloff() {
        assertTrue(auraSolidStopFor(0f) > auraSolidStopFor(0.5f), "softer fades from nearer the centre")
        assertEquals(0f, auraSolidStopFor(1f))
        val crisp = renderAura(sigmaM = 0.0)
        val soft = renderAura(sigmaM = 6.0)
        val c = px / 2
        // Same distance from the centre: the crisp aura still holds more tint than the soft one.
        assertTrue(alphaAt(crisp, c + 40, c) > alphaAt(soft, c + 40, c))
    }

    @Test
    fun glyphOutlineContrastsWithEveryGlyphColour() {
        val colours = listOf(
            lime, Color.White, Color.Black, Color(0xFF6750A4), Color(0xFFFFB74D), Color(0xFF808080),
            Color(0xFF2E7D32), Color(0xFFFF8C42), Color(0xFF333333), Color(0xFFCCCCCC),
        )
        for (glyph in colours) {
            val outline = glyphOutlineColor(glyph)
            val hi = max(glyph.luminance(), outline.luminance())
            val lo = min(glyph.luminance(), outline.luminance())
            val ratio = (hi + 0.05f) / (lo + 0.05f)
            assertTrue(ratio >= 3f, "glyph $glyph vs outline $outline contrast $ratio")
        }
    }
}
