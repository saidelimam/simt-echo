package app.simt.echo.ui.screens.processing

import androidx.compose.ui.unit.IntOffset
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Review 0013 F7 (R13-07): nearby checkpoints no longer stack their
 * labels. The separation pass is pure, so it is asserted directly.
 */
class ConstellationSeparationTest {

    private fun overlaps(a: IntOffset, b: IntOffset, width: Int, height: Int): Boolean {
        val overlapX = minOf(a.x + width, b.x + width) - maxOf(a.x, b.x)
        val overlapY = minOf(a.y + height, b.y + height) - maxOf(a.y, b.y)
        return overlapX > 0 && overlapY > 0
    }

    @Test
    fun closeStarsArePushedApart() {
        val origins = listOf(
            IntOffset(100, 100),
            IntOffset(104, 102),
            IntOffset(102, 106),
            IntOffset(108, 104),
        )
        val widths = List(origins.size) { 80 }
        val heights = List(origins.size) { 48 }
        val separated = separateStarOrigins(
            origins = origins,
            widths = widths,
            heights = heights,
            width = 400,
            height = 400,
            edge = 4,
        )
        for (i in separated.indices) {
            for (j in i + 1 until separated.size) {
                assertTrue(
                    !overlaps(separated[i], separated[j], widths[i], heights[i]),
                    "stars $i and $j still overlap: ${separated[i]} vs ${separated[j]}",
                )
            }
        }
    }

    @Test
    fun separatedStarsStayPut() {
        val origins = listOf(IntOffset(10, 10), IntOffset(200, 200))
        val separated = separateStarOrigins(
            origins = origins,
            widths = listOf(40, 40),
            heights = listOf(30, 30),
            width = 400,
            height = 400,
            edge = 4,
        )
        assertTrue(origins == separated)
    }
}
