package app.simt.echo.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan W13-09: heartbeat interval quickens 1.2 s → 0.4 s over the last 8 m. */
class HeartbeatTest {

    @Test
    fun farEndIsSlow() {
        assertEquals(1200L, DistanceHeartbeat.intervalMs(8.0))
    }

    @Test
    fun atTurnIsQuick() {
        assertEquals(400L, DistanceHeartbeat.intervalMs(0.0))
    }

    @Test
    fun midpointIsMidInterval() {
        assertEquals(800L, DistanceHeartbeat.intervalMs(4.0))
    }

    @Test
    fun beyondRangeClampsToSlow() {
        assertEquals(1200L, DistanceHeartbeat.intervalMs(25.0))
    }

    @Test
    fun negativeClampsToQuick() {
        assertEquals(400L, DistanceHeartbeat.intervalMs(-3.0))
    }

    @Test
    fun intervalDecreasesMonotonically() {
        var previous = Long.MAX_VALUE
        var distance = 8.0
        while (distance >= 0.0) {
            val current = DistanceHeartbeat.intervalMs(distance)
            assertTrue(current <= previous, "not monotonic at $distance")
            previous = current
            distance -= 0.5
        }
    }
}
