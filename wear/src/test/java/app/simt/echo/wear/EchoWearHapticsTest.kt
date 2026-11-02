package app.simt.echo.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W5-04 gate: every wire name maps to its §8.4 waveform; unknown names are ignored. */
class EchoWearHapticsTest {

    @Test
    fun allPatternsMapToSection84Waveforms() {
        assertArrayEquals(longArrayOf(0, 30), EchoWearHaptics.timings("TICK"))
        assertArrayEquals(longArrayOf(0, 40, 80, 40), EchoWearHaptics.timings("CONFIRM"))
        assertArrayEquals(longArrayOf(0, 60, 120, 60), EchoWearHaptics.timings("LEFT"))
        assertArrayEquals(longArrayOf(0, 60, 120, 60, 120, 60), EchoWearHaptics.timings("RIGHT"))
        assertArrayEquals(longArrayOf(0, 300, 150, 300), EchoWearHaptics.timings("U_TURN"))
        assertArrayEquals(longArrayOf(0, 400), EchoWearHaptics.timings("WARN"))
        assertArrayEquals(longArrayOf(0, 200, 120, 60, 120, 200), EchoWearHaptics.timings("ARRIVE"))
    }

    @Test
    fun unknownNamesYieldNull() {
        assertNull(EchoWearHaptics.timings("SPIN"))
        assertNull(EchoWearHaptics.timings(""))
        assertNull(EchoWearHaptics.timings("tick"))
    }
}
