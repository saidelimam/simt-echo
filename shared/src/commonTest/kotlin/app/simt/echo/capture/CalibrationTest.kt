package app.simt.echo.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun walkErrorIsSignedRelative() {
        assertEquals(0.25, calibrationWalkError(10.0, 12.5))
        assertEquals(-0.2, calibrationWalkError(10.0, 8.0))
        assertNull(calibrationWalkError(10.0, 0.0))
    }
}
