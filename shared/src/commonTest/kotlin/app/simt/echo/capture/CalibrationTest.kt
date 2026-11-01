package app.simt.echo.capture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W4-02 test delta: taped-walk calibration corrects the step constant. */
class CalibrationTest {

    @Test
    fun tapedWalkScalesKByTrueOverMeasured() {
        // ECHO read 8 m for a true 10 m walk: K grows by 10/8.
        assertEquals(0.48 * 10.0 / 8.0, calibrateK(0.48, 10.0, 8.0))
    }

    @Test
    fun calibratedKBringsTwentyMetreWalkInsideTolerance() {
        // Uncalibrated engine over-reads a 20 m walk as 25 m; the taped
        // 10 m correction brings it to 20 ± 1.5 m (slice acceptance).
        val biasedK = 0.48
        val measuredForTrue10 = 12.5
        val calibrated = calibrateK(biasedK, 10.0, measuredForTrue10)!!
        val corrected20 = 25.0 * calibrated / biasedK
        assertEquals(20.0, corrected20, 1.5)
    }

    @Test
    fun zeroOrNegativeInputsReturnNull() {
        assertNull(calibrateK(0.48, 10.0, 0.0))
        assertNull(calibrateK(0.48, 0.0, 8.0))
        assertNull(calibrateK(0.48, -10.0, 8.0))
    }

    @Test
    fun implausibleOrNonFiniteResultsAreRejected() {
        // A 10 m tape walk "measured" as 0.1 m would make K = 48: a typo,
        // not a gait. NaN/Infinity likewise never calibrate.
        assertNull(calibrateK(0.48, 10.0, 0.1))
        assertNull(calibrateK(0.48, 10.0, 1000.0))
        assertNull(calibrateK(0.48, 10.0, Double.NaN))
        assertNull(calibrateK(0.48, Double.POSITIVE_INFINITY, 8.0))
        assertNull(calibrateK(Double.NaN, 10.0, 8.0))
    }

    /** One synthetic 2 Hz walk through the real engine; returns its distance. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.walkDistance(stepK: Double): Double {
        val engine = PdrEngine(stepK)
        engine.start()
        val distances = mutableListOf<Double>()
        val job = launch { engine.updates.collect { if (it.isStep) distances += it.distanceM } }
        runCurrent()
        val hz = 50
        for (i in 0 until hz * 10) {
            val tSec = i.toDouble() / hz
            engine.onAccelerometer(
                ImuSample(tNs = (tSec * 1_000_000_000).toLong(), ax = 0.0, ay = 0.0, az = 9.81 + 2.0 * sin(2.0 * PI * 2.0 * tSec)),
            )
        }
        runCurrent()
        job.cancel()
        engine.stop()
        return distances.lastOrNull() ?: 0.0
    }

    @Test
    fun calibratedKReducesEngineDistanceError() = runTest {
        // The §6.2 fixture walk, taped at exactly 10 m. With the default K
        // the engine over-reads it; the taped calibration must measurably
        // shrink the error through the engine (clamp included) — this is
        // the slice acceptance, proven on the engine, not on arithmetic.
        val raw = walkDistance(STEP_K_DEFAULT)
        assertTrue(abs(raw - 10.0) > 1.0, "fixture must start biased, got $raw")
        val calibrated = calibrateK(STEP_K_DEFAULT, 10.0, raw)
        assertTrue(calibrated != null, "calibration from $raw must be plausible")
        val corrected = walkDistance(calibrated!!)
        assertTrue(
            abs(corrected - 10.0) < abs(raw - 10.0),
            "calibrated error ${abs(corrected - 10.0)} must beat raw ${abs(raw - 10.0)}",
        )
        assertEquals(10.0, corrected, 1.5)
    }

    @Test
    fun walkErrorIsSignedRelative() {
        assertEquals(0.25, calibrationWalkError(10.0, 12.5))
        assertEquals(-0.2, calibrationWalkError(10.0, 8.0))
        assertNull(calibrationWalkError(10.0, 0.0))
    }
}
