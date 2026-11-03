package app.simt.echo.capture.visual

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class PoseModeTest {
    private fun trace(tiltDeg: Double = 60.0, frequency: Double = 0.0, amplitude: Double = 0.0, swayDeg: Double = 0.0): PoseModeEstimator {
        val estimator = PoseModeEstimator()
        repeat(64) { i ->
            val t = i * 0.05
            val phase = sin(2.0 * PI * frequency * t)
            val tilt = (tiltDeg + swayDeg * phase) * PI / 180.0
            estimator.onSample(i * 50_000_000L, 0.0, sin(tilt) * 9.81, cos(tilt) * 9.81, amplitude * phase)
        }
        return estimator
    }

    @Test
    fun usefulConeFlatAndSidewaysHoldAreDistinguished() {
        for (angle in listOf(25.0, 45.0, 75.0)) assertEquals(PoseMode.HELD_UP, trace(angle).classify(100.0, 3_150_000_000L))
        assertEquals(PoseMode.FLAT, trace(5.0).classify(100.0, 3_150_000_000L))
        assertEquals(PoseMode.UNKNOWN, trace(90.0).classify(100.0, 3_150_000_000L))
        val sideways = PoseModeEstimator()
        sideways.onSample(0L, 9.81, 0.0, 0.0, 0.0)
        assertEquals(PoseMode.EAR, sideways.classify(100.0, 0L))
    }

    @Test
    fun dominantArmSwingBandNotArbitraryShaking() {
        for (frequency in listOf(1.0, 1.5, 2.0)) {
            assertEquals(PoseMode.SWINGING, trace(frequency = frequency, amplitude = 2.0, swayDeg = 15.0).classify(100.0, 3_150_000_000L))
        }
        for (frequency in listOf(0.5, 3.0)) {
            assertEquals(PoseMode.HELD_UP, trace(frequency = frequency, amplitude = 2.0).classify(100.0, 3_150_000_000L))
        }
        assertEquals(PoseMode.HELD_UP, trace(frequency = 1.5, amplitude = 0.1).classify(100.0, 3_150_000_000L))
        assertEquals(PoseMode.HELD_UP, trace(frequency = 1.5, amplitude = 2.0).classify(100.0, 3_150_000_000L))
    }

    @Test
    fun pocketRequiresDarknessAndSustainedStationaryOrientation() {
        val stable = trace()
        assertEquals(PoseMode.POCKET, stable.classify(5.0, 3_150_000_000L))
        assertEquals(PoseMode.HELD_UP, stable.classify(100.0, 3_150_000_000L))
        val moving = PoseModeEstimator()
        repeat(64) { i ->
            moving.onSample(i * 50_000_000L, if (i % 2 == 0) 4.0 else -4.0, 8.0, 4.0, 0.0)
        }
        assertEquals(PoseMode.HELD_UP, moving.classify(5.0, 3_150_000_000L))
    }

    @Test
    fun staleMissingOrInvalidSensorsNeverProduceAUsablePose() {
        val estimator = trace()
        assertEquals(PoseMode.UNKNOWN, estimator.classify(100.0, 4_000_000_000L))
        estimator.onSample(4_000_000_000L, Double.NaN, 0.0, 0.0, 0.0)
        assertEquals(PoseMode.UNKNOWN, estimator.classify(100.0, 4_000_000_000L))
        estimator.reset()
        assertEquals(PoseMode.UNKNOWN, estimator.classify(100.0, 0L))
    }
}
