package app.simt.echo.nav

import app.simt.echo.nav.ghost.Dtw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DtwTest {
    private fun distance(a: DoubleArray, b: DoubleArray, band: Int, mean: Boolean = false, scratch: Dtw.Scratch = Dtw.Scratch(b.size)): Double =
        Dtw.distance(a.size, b.size, band, scratch, mean) { i, j -> val d = a[i] - b[j]; d * d }

    @Test
    fun identityShiftAndStretch() {
        val a = doubleArrayOf(0.0, 0.0, 1.0, 2.0)
        assertEquals(0.0, distance(a, a, 0))
        assertEquals(0.0, distance(a, doubleArrayOf(0.0, 1.0, 2.0, 2.0), 1))
        assertEquals(0.0, distance(a, doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0), 1))
        assertTrue(distance(a, doubleArrayOf(0.0, 1.0, 2.0, 2.0), 0) > 0.0)
    }

    @Test
    fun bandRestrictsReachabilityAndEmptyInputsAreExplicit() {
        assertEquals(Double.POSITIVE_INFINITY, distance(doubleArrayOf(1.0), doubleArrayOf(1.0, 1.0, 1.0), 1))
        assertEquals(0.0, distance(doubleArrayOf(), doubleArrayOf(), 0))
        assertEquals(Double.POSITIVE_INFINITY, distance(doubleArrayOf(), doubleArrayOf(), 0, mean = true))
        assertEquals(Double.POSITIVE_INFINITY, distance(doubleArrayOf(1.0), doubleArrayOf(), 1))
        assertFailsWith<IllegalArgumentException> { distance(doubleArrayOf(1.0), doubleArrayOf(1.0), -1) }
        assertFailsWith<IllegalArgumentException> { distance(doubleArrayOf(1.0), doubleArrayOf(1.0), 1, scratch = Dtw.Scratch(0)) }
    }

    @Test
    fun meanUsesHistoricalTieOrderAndWinningPathLength() {
        // All predecessors tie at (2,2); vertical wins, making a 3-cell path.
        val scratch = Dtw.Scratch(2)
        assertEquals(1.0, Dtw.distance(2, 2, 1, scratch) { i, j -> if (i == 1 && j == 1) 1.0 else 0.0 })
        assertEquals(1.0 / 3.0, Dtw.distance(2, 2, 1, scratch, mean = true) { i, j -> if (i == 1 && j == 1) 1.0 else 0.0 })
    }

    @Test
    fun nonFiniteTurnCostsRetainHistoricalPropagation() {
        val observed = doubleArrayOf(Double.NaN, 0.0)
        val expected = doubleArrayOf(0.0, 0.0)
        assertTrue(distance(observed, expected, 1).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, distance(observed, expected, 1, mean = true))
    }

    @Test
    fun scratchReuseAcrossLengthsBandsAndScoringModesHasNoStaleCells() {
        val scratch = Dtw.Scratch(10)
        val a = doubleArrayOf(0.0, 2.0, 4.0, 7.0)
        val b = doubleArrayOf(1.0, 3.0, 6.0)
        repeat(10) {
            for (band in 0..5) {
                for (mean in listOf(false, true)) {
                    assertEquals(distance(a, b, band, mean), distance(a, b, band, mean, scratch))
                    assertEquals(distance(b, a, band, mean), distance(b, a, band, mean, scratch))
                }
            }
        }
    }
}
