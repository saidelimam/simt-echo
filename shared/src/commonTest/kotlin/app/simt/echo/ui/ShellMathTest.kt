package app.simt.echo.ui

import app.simt.echo.ui.components.fitScale
import app.simt.echo.ui.components.wrapPi
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-geometry gates for the nav shell: no device needed. */
class ShellMathTest {

    @Test
    fun wrapPiKeepsSmallAngles() {
        assertEquals(0f, wrapPi(0f))
        assertEquals(1f, wrapPi(1f))
        assertEquals(-1f, wrapPi(-1f))
    }

    @Test
    fun wrapPiUnwrapsFullTurns() {
        val twoPi = 2f * PI.toFloat()
        assertEquals(0f, wrapPi(twoPi), 1e-5f)
        // 359° clockwise reads as −1°, so the arrow never spins forward.
        assertEquals(-0.017453292f, wrapPi(6.265732f), 1e-5f)
    }

    @Test
    fun wrapPiMapsPiToMinusPiBoundary() {
        val result = wrapPi(PI.toFloat())
        assertTrue(result <= PI.toFloat() && result >= -PI.toFloat())
    }

    @Test
    fun fitScaleFitsWidth() {
        // 100 px wide path in a 200 px box with 16 px padding: (200−32)/100.
        assertEquals(1.68f, fitScale(0f, 100f, 0f, 10f, 200f, 200f, 16f), 1e-5f)
    }

    @Test
    fun fitScaleFitsHeightWhenTaller() {
        // 10 px wide, 100 px tall: height constrains → (200−32)/100.
        assertEquals(1.68f, fitScale(0f, 10f, 0f, 100f, 200f, 200f, 16f), 1e-5f)
    }

    @Test
    fun fitScaleNeverDividesByZero() {
        assertEquals(1f, fitScale(5f, 5f, 5f, 5f, 200f, 200f, 16f))
    }
}
