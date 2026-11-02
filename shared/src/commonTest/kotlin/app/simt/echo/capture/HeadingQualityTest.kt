package app.simt.echo.capture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W12-01 gates: stillness detection, drift-rate recovery (±2°/min clamp,
 * 0.3°/min tolerance) and quality scoring. All fixtures synthetic and
 * deterministic. `PdrEngineTest` is untouched; the last test here covers
 * only the additive engine wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeadingQualityTest {

    private val degPerMinToRadPerSec: (Double) -> Double = { degPerMin ->
        degPerMin * PI / 180.0 / 60.0
    }

    /** 50 Hz accelerometer stream of [magnitudeG] for [seconds]. */
    private fun feedAccel(hq: HeadingQuality, magnitudeG: Double, seconds: Double, hz: Int = 50) {
        val n = (seconds * hz).toInt()
        for (i in 0 until n) {
            hq.onAccelerometer(magnitudeG, i * 1_000_000_000L / hz)
        }
    }

    @Test
    fun stillWhenAccelQuietAndNoSteps() {
        val hq = HeadingQuality()
        feedAccel(hq, magnitudeG = 1.0, seconds = 1.0)
        assertTrue(hq.isStill, "quiet accelerometer should read as still")
    }

    @Test
    fun walkingVarianceBreaksStillness() {
        val hq = HeadingQuality()
        // 2 Hz sine, amplitude 2 m/s² around gravity — the PdrEngineTest walk.
        val hz = 50
        for (i in 0 until hz * 2) {
            val tSec = i.toDouble() / hz
            val magnitudeG = (9.81 + 2.0 * kotlin.math.sin(2.0 * PI * 2.0 * tSec)) / 9.81
            hq.onAccelerometer(magnitudeG, i * 1_000_000_000L / hz)
        }
        assertFalse(hq.isStill, "walking variance must not read as still")
    }

    @Test
    fun recentStepBreaksStillnessThenRecovers() {
        val hq = HeadingQuality()
        feedAccel(hq, magnitudeG = 1.0, seconds = 1.0)
        assertTrue(hq.isStill)
        // A step at t = 1.0 s breaks stillness even though accel stays quiet.
        hq.onStep(1_000_000_000L)
        assertFalse(hq.isStill, "a fresh step must break stillness")
        // 0.6 s of quiet accel after the step recovers stillness.
        for (i in 1..30) {
            hq.onAccelerometer(1.0, 1_000_000_000L + i * 20_000_000L)
        }
        assertTrue(hq.isStill, "stillness should recover 0.5 s after the step")
    }

    @Test
    fun driftBiasRecoveredWithinTolerance() {
        val hq = HeadingQuality()
        val trueDrift = degPerMinToRadPerSec(1.5)
        val tolerance = degPerMinToRadPerSec(0.3)
        // 60 s of stillness with yaw ramping at 1.5°/min, 50 Hz.
        val hz = 50
        for (i in 0 until hz * 60) {
            val tSec = i.toDouble() / hz
            hq.onAccelerometer(1.0, i * 1_000_000_000L / hz)
            hq.onYaw(wrapPi(trueDrift * tSec), i * 1_000_000_000L / hz, axisSwitched = false)
        }
        assertTrue(hq.isStill)
        assertEquals(
            trueDrift,
            hq.driftRateRadPerSec,
            tolerance,
            "drift 1.5°/min should be recovered within 0.3°/min, got ${hq.driftRateRadPerSec * 180.0 * 60.0 / PI}°/min",
        )
        // The accumulated drift is subtracted: corrected yaw stays near true north.
        val corrected = hq.correctedYaw(wrapPi(trueDrift * 60.0))
        assertTrue(
            kotlin.math.abs(corrected) < 0.5 * PI / 180.0,
            "corrected yaw should be within 0.5° of truth, got ${corrected * 180.0 / PI}°",
        )
    }

    @Test
    fun driftEstimateClampedToTwoDegPerMin() {
        val hq = HeadingQuality()
        val trueDrift = degPerMinToRadPerSec(5.0)
        val hz = 50
        for (i in 0 until hz * 30) {
            val tSec = i.toDouble() / hz
            hq.onAccelerometer(1.0, i * 1_000_000_000L / hz)
            hq.onYaw(wrapPi(trueDrift * tSec), i * 1_000_000_000L / hz, axisSwitched = false)
        }
        assertEquals(
            HeadingQuality.MAX_DRIFT_RAD_PER_SEC,
            hq.driftRateRadPerSec,
            1e-9,
            "5°/min drift must clamp to +2°/min",
        )
    }

    @Test
    fun qualityIsOneWhenCalmAndStill() {
        val hq = HeadingQuality()
        val hz = 50
        for (i in 0 until hz * 3) {
            hq.onAccelerometer(1.0, i * 1_000_000_000L / hz)
            hq.onYaw(0.0, i * 1_000_000_000L / hz, axisSwitched = false)
        }
        assertTrue(hq.isStill)
        assertEquals(1.0, hq.quality, 1e-9, "calm stillness should score q = 1")
    }

    @Test
    fun axisSwitchPenalisesQuality() {
        val hq = HeadingQuality()
        val hz = 50
        for (i in 0 until hz * 3) {
            hq.onAccelerometer(1.0, i * 1_000_000_000L / hz)
            hq.onYaw(0.0, i * 1_000_000_000L / hz, axisSwitched = false)
        }
        val before = hq.quality
        val tNs = (hz * 3) * 1_000_000_000L / hz
        hq.onAccelerometer(1.0, tNs)
        hq.onYaw(0.0, tNs, axisSwitched = true)
        assertTrue(hq.quality in 0.0..1.0)
        assertTrue(hq.quality < before, "axis switch must lower q ($before → ${hq.quality})")
    }

    @Test
    fun fastTurnPenalisesQuality() {
        val hq = HeadingQuality()
        // Moving (not still): yaw spinning at 90°/s for 2 s at 50 Hz.
        val hz = 50
        val rate = PI / 2.0
        for (i in 0 until hz * 2) {
            val tSec = i.toDouble() / hz
            // Jittered magnitude keeps accel variance above the still threshold.
            val magnitudeG = 1.0 + 0.25 * kotlin.math.sin(2.0 * PI * 2.0 * tSec)
            hq.onAccelerometer(magnitudeG, i * 1_000_000_000L / hz)
            hq.onYaw(wrapPi(rate * tSec), i * 1_000_000_000L / hz, axisSwitched = false)
        }
        assertFalse(hq.isStill)
        assertTrue(hq.quality in 0.0..1.0)
        assertTrue(hq.quality < 0.5, "90°/s turn should score q < 0.5, got ${hq.quality}")
    }

    @Test
    fun qualityAlwaysBounded() {
        val hq = HeadingQuality()
        // Deterministic mixed episode: still, drift, switch, turn, step.
        var tNs = 0L
        fun tick(mag: Double, yaw: Double, switch: Boolean) {
            hq.onAccelerometer(mag, tNs)
            hq.onYaw(yaw, tNs, switch)
            tNs += 20_000_000L
        }
        repeat(100) { tick(1.0, 0.0, false) }
        assertTrue(hq.quality in 0.0..1.0)
        repeat(100) { i -> tick(1.0, wrapPi(0.001 * i), i == 50) }
        assertTrue(hq.quality in 0.0..1.0)
        hq.onStep(tNs)
        repeat(100) { i -> tick(1.0 + 0.2 * kotlin.math.sin(i.toDouble()), wrapPi(0.1 + 0.05 * i), false) }
        assertTrue(hq.quality in 0.0..1.0)
    }

    @Test
    fun deterministicReplayMatches() {
        fun run(): Triple<Boolean, Double, Double> {
            val hq = HeadingQuality()
            val hz = 50
            for (i in 0 until hz * 10) {
                val tSec = i.toDouble() / hz
                hq.onAccelerometer(1.0 + 0.01 * kotlin.math.sin(i.toDouble()), i * 1_000_000_000L / hz)
                hq.onYaw(
                    wrapPi(degPerMinToRadPerSec(1.0) * tSec),
                    i * 1_000_000_000L / hz,
                    axisSwitched = i == 250,
                )
            }
            return Triple(hq.isStill, hq.driftRateRadPerSec, hq.quality)
        }
        assertEquals(run(), run(), "identical fixtures must give identical results")
    }

    @Test
    fun engineEmitsHeadingQualityAdditively() = runTest {
        val engine = PdrEngine()
        engine.start()
        val collected = mutableListOf<app.simt.echo.platform.MotionUpdate>()
        val job = launch { engine.updates.collect { collected += it } }
        runCurrent()
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        // 2 s of quiet accel + static rotation at 50 Hz.
        for (i in 0 until 100) {
            val tNs = i * 20_000_000L
            engine.onAccelerometer(ImuSample(tNs = tNs, ax = 0.0, ay = 0.0, az = 9.81))
            engine.onRotation(RotSample(tNs = tNs, r = identity))
        }
        runCurrent()
        job.cancel()
        assertTrue(collected.isNotEmpty(), "engine should emit rotation updates")
        assertTrue(
            collected.all { it.headingQuality in 0.0..1.0 },
            "every update must carry q ∈ [0,1]",
        )
        assertTrue(engine.headingQuality.isStill, "quiet fixture should read as still")
        assertEquals(0, collected.count { it.isStep }, "quiet fixture must not step")
        assertEquals(0.0, collected.last().yawRad, 1e-9, "raw yaw path is unchanged")
    }
}
