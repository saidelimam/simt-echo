package app.simt.echo.ui.atmosphere

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiltFilterTest {

    @Test
    fun aPhoneLyingStillPublishesNothing() {
        val filter = TiltFilter()
        val random = Random(7)
        var published = 0
        // Ten seconds of 60 ms samples with sensor noise of +-0.004 around a fixed tilt of zero.
        repeat(170) {
            if (filter.update(random.nextFloat() * 0.008f - 0.004f, random.nextFloat() * 0.008f - 0.004f) != null) published++
        }
        assertEquals(0, published, "noise alone must not redraw the field")
    }

    @Test
    fun aRealTiltIsPublishedAndConverges() {
        val filter = TiltFilter()
        var last = filter.update(0.5f, -0.3f)
        assertNotNull(last, "a clear tilt must publish at once")
        repeat(80) { filter.update(0.5f, -0.3f)?.let { last = it } }
        val settled = assertNotNull(last)
        assertTrue(abs(settled.x - 0.5f) <= TiltFilter.TILT_PUBLISH_STEP, "x converges: ${settled.x}")
        assertTrue(abs(settled.y + 0.3f) <= TiltFilter.TILT_PUBLISH_STEP, "y converges: ${settled.y}")
    }

    @Test
    fun publishedSamplesAreSpacedByAtLeastTheStep() {
        val filter = TiltFilter()
        var previous = androidx.compose.ui.geometry.Offset.Zero
        repeat(200) { i ->
            val x = if (i < 100) 0.8f else -0.8f
            filter.update(x, 0f)?.let {
                val moved = maxOf(abs(it.x - previous.x), abs(it.y - previous.y))
                assertTrue(moved >= TiltFilter.TILT_PUBLISH_STEP - 1e-6f, "published a move of only $moved")
                previous = it
            }
        }
        assertNull(filter.update(previous.x, previous.y), "holding the published value publishes nothing")
    }
}
