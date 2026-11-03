package app.simt.echo.ui.screens.review

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W8-08 instrument gate: the Review minimap fit is pure, so the Canvas
 * mapping is proven here — padding on every side, degenerate input never
 * divides by zero, and stat chips split without losing text.
 */
class ReviewInstrumentTest {

    @Test
    fun degenerateSeriesFallsBackToUnitScale() {
        assertEquals(1f, reviewMapScale(3f, 3f, 7f, 7f, 100f, 100f, 16f))
    }

    @Test
    fun widerSpanFitsWidthWithPadding() {
        // x spans 10 in a 100 px box with 16 px padding: (100 − 32) / 10.
        val scale = reviewMapScale(0f, 10f, 0f, 1f, 100f, 100f, 16f)
        assertEquals(6.8f, scale, 0.001f)
    }

    @Test
    fun tallerSpanFitsHeightWithPadding() {
        // y spans 20 in a 100 px box with 16 px padding: (100 − 32) / 20.
        val scale = reviewMapScale(0f, 1f, 0f, 20f, 100f, 100f, 16f)
        assertEquals(3.4f, scale, 0.001f)
    }

    @Test
    fun mappedSeriesSitsInsidePadding() {
        val scale = reviewMapScale(0f, 10f, 0f, 10f, 100f, 100f, 16f)
        val lo = mapReviewPoint(Offset(0f, 0f), 0f, 0f, scale, 16f)
        val hi = mapReviewPoint(Offset(10f, 10f), 0f, 0f, scale, 16f)
        assertEquals(Offset(16f, 16f), lo)
        assertEquals(Offset(84f, 84f), hi)
        assertTrue(lo.x >= 16f && hi.x <= 100f - 16f)
        assertTrue(lo.y >= 16f && hi.y <= 100f - 16f)
    }

    @Test
    fun layoutCentersAndRotatesLongAxisToHorizontal() {
        // Walked path is narrow in X (0..2) and tall in Y (0..20) in a 200x100 wide box.
        // layoutReviewPoints rotates it so the 20-unit span lies horizontally,
        // filling the width and centering vertically.
        val points = listOf(Offset(0f, 0f), Offset(2f, 20f))
        val mapped = layoutReviewPoints(points, boxWidth = 200f, boxHeight = 100f, paddingPx = 20f)
        val spanX = mapped.maxOf { it.x } - mapped.minOf { it.x }
        val spanY = mapped.maxOf { it.y } - mapped.minOf { it.y }
        assertTrue(spanX > spanY, "long axis rotated to horizontal: spanX=$spanX, spanY=$spanY")
        assertEquals(160f, spanX, 0.01f)
        val centerX = (mapped.minOf { it.x } + mapped.maxOf { it.x }) * 0.5f
        val centerY = (mapped.minOf { it.y } + mapped.maxOf { it.y }) * 0.5f
        assertEquals(100f, centerX, 0.01f)
        assertEquals(50f, centerY, 0.01f)
    }

    @Test
    fun statChipsKeepEveryStat() {
        assertEquals(
            listOf("SAM 0.86", "tracked 78%", "visible ~9 m"),
            statChipParts("SAM 0.86 · tracked 78% · visible ~9 m"),
        )
        assertEquals(listOf("SAM 0.9"), statChipParts("SAM 0.9"))
        assertTrue(statChipParts("").isEmpty())
    }

    @Test
    fun nodeKindReadsAsWords() {
        assertEquals("turn", nodeKindWord("TURN"))
        assertEquals("level change", nodeKindWord("LEVEL_CHANGE"))
        assertEquals("destination", nodeKindWord("DESTINATION"))
    }
}
