package app.simt.echo.ui.atmosphere

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W13-01 ripple motif: freeze §4 shape, slot recycling, uniform layout. */
class EchoRipplesTest {

    private var now = 0L
    private val state = EchoRippleState(clock = { now })
    private val out = FloatArray(ECHO_RIPPLE_SLOTS * 4)

    @Test
    fun rippleGrowsAndFadesThenEnds() {
        val start = rippleFrame(0)!!
        val mid = rippleFrame(ECHO_RIPPLE_DURATION_MS / 2)!!
        assertEquals(0f, start.reach)
        assertEquals(ECHO_RIPPLE_START_ALPHA, start.alpha)
        assertTrue(mid.reach > 0.5f, "ease-out: past half reach at half time")
        assertTrue(mid.alpha < start.alpha)
        assertNull(rippleFrame(ECHO_RIPPLE_DURATION_MS))
        assertNull(rippleFrame(-1))
    }

    @Test
    fun uniformsCarryCentreRadiusAndAlpha() {
        state.emit(Offset(100f, 200f))
        now = ECHO_RIPPLE_DURATION_MS / 2
        state.writeUniforms(now, shortEdgePx = 1000f, out = out)
        val frame = rippleFrame(now)!!
        assertEquals(100f, out[0])
        assertEquals(200f, out[1])
        assertEquals(frame.reach * ECHO_RIPPLE_REACH_OF_SHORT_EDGE * 1000f, out[2])
        assertEquals(frame.alpha, out[3])
        for (slot in 1 until ECHO_RIPPLE_SLOTS) assertEquals(0f, out[slot * 4 + 3], "unused slot is silent")
    }

    @Test
    fun aFifthRippleReplacesTheOldest() {
        repeat(ECHO_RIPPLE_SLOTS) { i ->
            now = i * 10L
            state.emit(Offset(i.toFloat(), 0f))
        }
        now = 50L
        state.emit(Offset(99f, 0f))
        state.writeUniforms(now, shortEdgePx = 1000f, out = out)
        assertEquals(99f, out[0], "slot 0 (oldest) was recycled")
        assertEquals(1f, out[4])
    }

    @Test
    fun aliveOnlyWhileARippleTravels() {
        assertFalse(state.anyAlive(now))
        state.emit(Offset.Zero)
        assertTrue(state.anyAlive(now))
        now += ECHO_RIPPLE_DURATION_MS
        assertFalse(state.anyAlive(now))
    }
}
