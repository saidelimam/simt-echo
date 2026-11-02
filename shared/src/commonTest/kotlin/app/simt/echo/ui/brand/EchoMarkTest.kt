package app.simt.echo.ui.brand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W10-07: the in-app mark draws the freeze §2 echo bands, nested and clamped. */
class EchoMarkTest {

    @Test
    fun echoCountIsClampedToTheThreeFreezeBands() {
        assertEquals(0, echoBands(-1).size)
        assertEquals(1, echoBands(1).size)
        assertEquals(3, echoBands(3).size)
        assertEquals(3, echoBands(9).size)
    }

    @Test
    fun bandsAreNestedRingsThatNeverOverlap() {
        val bands = echoBands(3)
        for (band in bands) {
            assertTrue(band.innerStroke < band.outerStroke, "a band is a ring, not a fill: $band")
        }
        for (i in 1 until bands.size) {
            assertTrue(
                bands[i - 1].outerStroke < bands[i].innerStroke,
                "echo ${i + 1} must start outside echo $i",
            )
        }
    }

    @Test
    fun echoesFadeOutward() {
        val alphas = echoBands(3).map { it.alpha }
        assertEquals(alphas.sortedDescending(), alphas)
    }

    @Test
    fun aSingleEchoIsTheInnerEmberBand() {
        val inner = echoBands(1).single()
        assertEquals(9f, inner.outerStroke)
        assertEquals(5f, inner.innerStroke)
    }
}
