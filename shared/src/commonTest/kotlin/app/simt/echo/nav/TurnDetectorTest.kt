package app.simt.echo.nav

import app.simt.echo.model.TurnDir
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** §6.5 gates: settled turns fire once; small or unsteady changes do not. */
class TurnDetectorTest {

    private companion object {
        const val TICK_NS = 50_000_000L // 20 Hz
    }

    private fun deg(v: Double) = v * PI / 180.0

    /** Feeds [seconds] of constant [yawDeg] from [startNs]; returns next t. */
    private fun feed(
        detector: TurnDetector,
        startNs: Long,
        seconds: Double,
        yawDeg: Double,
    ): Pair<Long, DetectedTurn?> {
        var fired: DetectedTurn? = null
        var t = startNs
        val ticks = (seconds * 20).toInt()
        repeat(ticks) {
            t += TICK_NS
            detector.onSample(t, deg(yawDeg))?.let { fired = it }
        }
        return t to fired
    }

    @Test
    fun settledRightTurnFires() {
        val detector = TurnDetector()
        var t = 0L
        // 5 s straight builds the reference window, then a settled 90° turn.
        var result = feed(detector, t, 5.0, 0.0); t = result.first
        result = feed(detector, t, 3.0, 90.0)
        val turn = result.second
        assertNotNull(turn)
        assertEquals(TurnDir.RIGHT, turn.dir)
        assertEquals(deg(90.0), turn.angleRad, 0.05)
    }

    @Test
    fun settledLeftTurnFires() {
        val detector = TurnDetector()
        var t = 0L
        var result = feed(detector, t, 5.0, 0.0); t = result.first
        result = feed(detector, t, 3.0, -90.0)
        assertEquals(TurnDir.LEFT, result.second?.dir)
    }

    @Test
    fun uTurnIsClassified() {
        val detector = TurnDetector()
        var t = 0L
        var result = feed(detector, t, 5.0, 0.0); t = result.first
        result = feed(detector, t, 3.0, 180.0)
        assertEquals(TurnDir.U_TURN, result.second?.dir)
    }

    @Test
    fun smallHeadingChangeDoesNotFire() {
        val detector = TurnDetector()
        var t = 0L
        var result = feed(detector, t, 5.0, 0.0); t = result.first
        result = feed(detector, t, 3.0, 30.0)
        assertNull(result.second)
    }

    @Test
    fun unsteadyHeadingDoesNotFire() {
        val detector = TurnDetector()
        var t = 0L
        val straight = feed(detector, t, 5.0, 0.0); t = straight.first
        // Oscillate 0°/90° every sample for 3 s: recent window never settles.
        var fired: DetectedTurn? = null
        repeat(60) { i ->
            t += TICK_NS
            detector.onSample(t, deg(if (i % 2 == 0) 0.0 else 90.0))?.let { fired = it }
        }
        assertNull(fired)
    }
}
