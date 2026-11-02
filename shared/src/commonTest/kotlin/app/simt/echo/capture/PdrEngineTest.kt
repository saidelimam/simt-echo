package app.simt.echo.capture

import app.simt.echo.platform.MotionSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §6.1/§6.2 gates: heading matrices, the synthetic walk, yaw wrap. */
@OptIn(ExperimentalCoroutinesApi::class)
class PdrEngineTest {

    private fun identityMatrix(): FloatArray =
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Upright portrait, back camera facing east (see matrix in §6.1). */
    private fun eastCameraMatrix(): FloatArray =
        floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f,
        )

    @Test
    fun identityHeadingIsZero() {
        val (heading, camera) = headingRad(identityMatrix(), prevCamera = false)
        assertEquals(0.0, heading, 1e-9)
        assertEquals(false, camera)
    }

    @Test
    fun uprightEastCameraHeadingIsPlusPiOver2() {
        val (heading, camera) = headingRad(eastCameraMatrix(), prevCamera = false)
        assertEquals(PI / 2, heading, 1e-6)
        assertEquals(true, camera)
    }

    @Test
    fun yawWrapsAcrossPi() {
        assertEquals(-PI + 0.1, wrapPi(PI + 0.1), 1e-9)
        assertEquals(PI - 0.1, wrapPi(-PI - 0.1), 1e-9)
        assertEquals(0.0, wrapPi(2 * PI), 1e-9)
        assertEquals(0.5, wrapPi(0.5), 1e-12)
    }

    @Test
    fun circularMeanAveragesAcrossWrap() {
        // Mean of +170° and −170° is ±180°, not 0°.
        val mean = circularMean(listOf(2.967, -2.967))
        assertEquals(PI, kotlin.math.abs(mean), 0.01)
    }

    @Test
    fun syntheticWalkProducesStepsAndDistance() = runTest {
        val engine = PdrEngine()
        engine.start()
        val collected = mutableListOf<app.simt.echo.platform.MotionUpdate>()
        val job = launch { engine.updates.collect { collected += it } }
        runCurrent() // let the collector subscribe before emissions start
        // 2 Hz sine, amplitude 2 m/s² on |a| around gravity, 50 Hz, 10 s.
        val hz = 50
        for (i in 0 until hz * 10) {
            val tSec = i.toDouble() / hz
            val magnitude = 9.81 + 2.0 * sin(2.0 * PI * 2.0 * tSec)
            engine.onAccelerometer(
                ImuSample(
                    tNs = (tSec * 1_000_000_000).toLong(),
                    ax = 0.0,
                    ay = 0.0,
                    az = magnitude,
                ),
            )
        }
        runCurrent() // deliver buffered emissions to the collector
        job.cancel()
        val steps = collected.filter { it.isStep }
        assertTrue(steps.size in 19..21, "expected 20 ± 1 steps, got ${steps.size}")
        val distance = steps.lastOrNull()?.distanceM ?: 0.0
        assertTrue(distance in 13.6 - 1.5..13.6 + 1.5, "expected 13.6 ± 1.5 m, got $distance")
        // Distance is monotonic and step counts sequential: the contract
        // W1-03 capture and W1-04 segmenter rely on.
        assertTrue(steps.zipWithNext().all { (a, b) -> b.distanceM > a.distanceM })
        assertEquals(steps.size, steps.last().stepCount)
        assertTrue(steps.all { it.source == "echo_builtin_pdr" })
    }

    @Test
    fun stationarySignalProducesNoSteps() = runTest {
        val engine = PdrEngine()
        engine.start()
        val collected = mutableListOf<app.simt.echo.platform.MotionUpdate>()
        val job = launch { engine.updates.collect { collected += it } }
        runCurrent() // let the collector subscribe before emissions start
        repeat(500) { i ->
            engine.onAccelerometer(
                ImuSample(tNs = i * 20_000_000L, ax = 0.0, ay = 0.0, az = 9.81),
            )
        }
        runCurrent() // deliver buffered emissions to the collector
        job.cancel()
        assertTrue(collected.none { it.isStep })
    }

    @Test
    fun engineIsMotionSource() {
        val source: MotionSource = PdrEngine()
        source.start()
        source.stop()
    }

    @Test
    fun magneticFeedReducesToMagnitudeAndVertical() = runTest {
        val engine = PdrEngine()
        engine.start()
        val collected = mutableListOf<app.simt.echo.platform.MotionUpdate>()
        val job = launch { engine.updates.collect { collected += it } }
        runCurrent() // let the collector subscribe before emissions start
        // Magnetometer before any rotation matrix: dropped, no vertical yet.
        engine.onMagnetic(MagSample(tNs = 1L, bx = 30.0, by = 20.0, bz = 40.0))
        // Identity rotation: world-up is device-z, so vertical == bz.
        engine.onRotation(RotSample(tNs = 2L, r = identityMatrix()))
        engine.onMagnetic(MagSample(tNs = 3L, bx = 30.0, by = 40.0, bz = 0.0))
        engine.onRotation(RotSample(tNs = 4L, r = identityMatrix()))
        runCurrent() // deliver buffered emissions to the collector
        job.cancel()
        val last = collected.last()
        assertEquals(50.0, last.magMag!!, 1e-9)
        assertEquals(0.0, last.magVert!!, 1e-9)
    }
}
