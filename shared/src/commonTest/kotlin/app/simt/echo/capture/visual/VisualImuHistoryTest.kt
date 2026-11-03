package app.simt.echo.capture.visual

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisualImuHistoryTest {
    @Test
    fun exactEndpointsAndNormalizedInterpolationUseFrameTime() {
        val history = VisualImuHistory()
        val target = VisualFramePose()
        history.onRotation(0L, rotationX(0.0))
        history.onRotation(100_000_000L, rotationX(90.0))
        assertTrue(history.readAt(0L, 100.0, target))
        assertQuaternion(target, 1.0, 0.0, 0.0, 0.0)
        assertEquals(5.0 * PI, target.angularRateRadPerSec, 1e-6)
        assertTrue(history.readAt(25_000_000L, 100.0, target))
        val w = 0.75 + 0.25 * cos(PI / 4.0)
        val x = 0.25 * sin(PI / 4.0)
        val norm = sqrt(w * w + x * x)
        assertQuaternion(target, w / norm, x / norm, 0.0, 0.0)
        assertTrue(history.readAt(50_000_000L, 100.0, target))
        assertQuaternion(target, cos(PI / 8.0), sin(PI / 8.0), 0.0, 0.0)
        assertTrue(history.readAt(100_000_000L, 100.0, target))
        assertQuaternion(target, cos(PI / 4.0), sin(PI / 4.0), 0.0, 0.0)
    }

    @Test
    fun shortestArcCrossesQuaternionSignBoundary() {
        val history = VisualImuHistory()
        history.onRotation(0L, rotationX(110.0))
        history.onRotation(100_000_000L, rotationX(250.0))
        val target = VisualFramePose()
        assertTrue(history.readAt(50_000_000L, 100.0, target))
        assertQuaternion(target, 0.0, 1.0, 0.0, 0.0)
        assertEquals(140.0 * PI / 180.0 / 0.1, target.angularRateRadPerSec, 1e-5)
    }

    @Test
    fun allMatrixConversionBranchesProduceUnitDeviceToWorldRotations() {
        val matrices = arrayOf(
            rotationX(180.0),
            floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f),
            floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f),
        )
        val target = VisualFramePose()
        for (axis in matrices.indices) {
            val history = VisualImuHistory()
            history.onRotation(0L, matrices[axis])
            assertTrue(history.readAt(0L, 100.0, target))
            assertQuaternion(target, 0.0, if (axis == 0) 1.0 else 0.0, if (axis == 1) 1.0 else 0.0, if (axis == 2) 1.0 else 0.0)
            assertEquals(0.0, target.angularRateRadPerSec)
        }
    }

    @Test
    fun sixtyFiveDegreeHoldAndBasicGravityPosesUseThirdRow() {
        val target = VisualFramePose()
        for (angle in listOf(0.0, 65.0, 90.0)) {
            val history = VisualImuHistory()
            val matrix = rotationX(angle)
            history.onRotation(0L, matrix)
            history.onAccelerometer(0L, 0.0, 9.81 * matrix[7], 9.81 * matrix[8])
            history.onRotation(50_000_000L, matrix)
            assertTrue(history.readAt(50_000_000L, 100.0, target))
            assertEquals(when (angle) { 0.0 -> PoseMode.FLAT; 65.0 -> PoseMode.HELD_UP; else -> PoseMode.UNKNOWN }, target.mode)
            assertEquals(0.0, target.angularRateRadPerSec, 1e-6)
        }
        val sideways = VisualImuHistory()
        sideways.onRotation(0L, floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f))
        sideways.onAccelerometer(0L, 9.81, 0.0, 0.0)
        assertTrue(sideways.readAt(0L, 100.0, target))
        assertEquals(PoseMode.EAR, target.mode)
    }

    @Test
    fun missingStaleUnbracketedAndReversedTimesDoNotMutateTarget() {
        val history = VisualImuHistory()
        val target = VisualFramePose().apply { qw = 7.0; mode = PoseMode.EAR; angularRateRadPerSec = 9.0 }
        assertFalse(history.isAvailable(0L))
        assertFalse(history.readAt(0L, 100.0, target))
        history.onRotation(50_000_000L, rotationX(65.0))
        assertTrue(history.isAvailable(50_000_000L))
        assertFalse(history.isAvailable(150_000_001L))
        assertFalse(history.readAt(-1L, 100.0, target))
        assertFalse(history.readAt(0L, 100.0, target))
        assertFalse(history.readAt(60_000_000L, 100.0, target))
        history.onRotation(150_000_001L, rotationX(65.0))
        assertFalse(history.readAt(100_000_000L, 100.0, target))
        assertFalse(history.readAt(50_000_000L, Double.NaN, target))
        assertFalse(history.readAt(50_000_000L, Double.POSITIVE_INFINITY, target))
        assertEquals(7.0, target.qw)
        assertEquals(PoseMode.EAR, target.mode)
        assertEquals(9.0, target.angularRateRadPerSec)
        history.onRotation(200_000_000L, rotationX(65.0))
        assertTrue(history.readAt(200_000_000L, 100.0, target))
        assertFalse(history.readAt(175_000_000L, 100.0, target))
    }

    @Test
    fun exactSamplesAcrossAGapUseMatchingQuaternionWithoutInventingRate() {
        val history = VisualImuHistory()
        history.onRotation(0L, rotationX(0.0))
        history.onRotation(200_000_000L, rotationX(65.0))
        val target = VisualFramePose()
        assertTrue(history.readAt(0L, 100.0, target))
        assertQuaternion(target, 1.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, target.angularRateRadPerSec)
        assertFalse(history.readAt(100_000_000L, 100.0, target))
        assertTrue(history.readAt(200_000_000L, 100.0, target))
        assertQuaternion(target, cos(65.0 * PI / 360.0), sin(65.0 * PI / 360.0), 0.0, 0.0)
        assertEquals(0.0, target.angularRateRadPerSec)
    }

    @Test
    fun malformedNonfiniteAndNonrotationMatricesAreDropped() {
        val history = VisualImuHistory()
        val target = VisualFramePose()
        val invalid = arrayOf(
            FloatArray(0), FloatArray(8), FloatArray(16), FloatArray(9),
            rotationX(0.0).apply { this[0] = Float.NaN },
            rotationX(0.0).apply { this[4] = Float.POSITIVE_INFINITY },
            rotationX(0.0).apply { this[0] = 2f },
            rotationX(0.0).apply { this[0] = -1f },
            floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        )
        for (matrix in invalid) history.onRotation(50_000_000L, matrix)
        history.onRotation(-1L, rotationX(0.0))
        assertFalse(history.isAvailable(50_000_000L))
        assertFalse(history.readAt(50_000_000L, 100.0, target))
        history.onRotation(50_000_000L, rotationX(65.0))
        history.onRotation(50_000_000L, rotationX(0.0))
        history.onRotation(0L, rotationX(0.0))
        assertTrue(history.readAt(50_000_000L, 100.0, target))
        assertEquals(PoseMode.HELD_UP, target.mode)
    }

    @Test
    fun boundedOverwriteDropsOldTimesAndDoesNotRetainInputArray() {
        assertFailsWith<IllegalArgumentException> { VisualImuHistory(0) }
        val history = VisualImuHistory(3)
        val matrix = rotationX(65.0)
        repeat(5) { history.onRotation(it * 50_000_000L, matrix) }
        matrix.fill(Float.NaN)
        val target = VisualFramePose()
        assertFalse(history.readAt(50_000_000L, 100.0, target))
        assertTrue(history.readAt(100_000_000L, 100.0, target))
        assertEquals(PoseMode.HELD_UP, target.mode)
        assertTrue(history.readAt(175_000_000L, 100.0, target))
        assertQuaternion(target, cos(65.0 * PI / 360.0), sin(65.0 * PI / 360.0), 0.0, 0.0)
        val single = VisualImuHistory(1)
        single.onRotation(0L, rotationX(0.0))
        single.onRotation(50_000_000L, rotationX(65.0))
        assertFalse(single.readAt(0L, 100.0, target))
        assertTrue(single.readAt(50_000_000L, 100.0, target))
        assertEquals(0.0, target.angularRateRadPerSec)
    }

    @Test
    fun classificationConsumesOnlyNewSamplesThroughFrameAndResetsOnOverrun() {
        val history = VisualImuHistory(24)
        val matrix = rotationX(65.0)
        repeat(24) { history.onRotation(it * 50_000_000L, matrix) }
        val target = VisualFramePose()
        assertTrue(history.readAt(0L, 5.0, target))
        assertEquals(PoseMode.HELD_UP, target.mode, "future history must not leak into classification")
        assertTrue(history.readAt(1_150_000_000L, 5.0, target))
        assertEquals(PoseMode.POCKET, target.mode)
        assertTrue(history.readAt(1_150_000_000L, 100.0, target))
        assertEquals(PoseMode.HELD_UP, target.mode, "same-time reads must use current luma without adding duplicate samples")
        repeat(25) { history.onRotation(1_200_000_000L + it * 1_000_000L, matrix) }
        assertTrue(history.readAt(1_224_000_000L, 5.0, target))
        assertEquals(PoseMode.HELD_UP, target.mode, "lost history must not retain the old pocket window")
    }

    @Test
    fun signedAccelerationAlongGravityDistinguishesArmSwingFromStationaryHold() {
        for (frequency in listOf(1.0, 1.5, 2.0)) {
            assertEquals(PoseMode.SWINGING, accelerationTrace(frequency, 2.0))
            assertEquals(PoseMode.HELD_UP, accelerationTrace(frequency, 0.0))
        }
        assertEquals(PoseMode.HELD_UP, accelerationTrace(3.0, 2.0))
    }

    private fun accelerationTrace(frequency: Double, amplitude: Double): PoseMode {
        val history = VisualImuHistory()
        val target = VisualFramePose()
        var matrix = rotationX(60.0)
        history.onAccelerometer(0L, Double.NaN, 0.0, 0.0)
        history.onRotation(0L, matrix)
        repeat(64) { index ->
            val time = (index + 1) * 50_000_000L
            val phase = sin(2.0 * PI * frequency * time / 1e9)
            val acceleration = 9.81 + amplitude * phase
            history.onAccelerometer(time, 0.0, acceleration * matrix[7], acceleration * matrix[8])
            // Invalid or reversed accelerometer events must not replace the valid signed sample.
            history.onAccelerometer(time + 1L, Double.NaN, 0.0, 0.0)
            history.onAccelerometer(time - 1L, 0.0, 0.0, 0.0)
            matrix = rotationX(60.0 + 15.0 * phase)
            history.onRotation(time, matrix)
        }
        assertTrue(history.readAt(3_200_000_000L, 100.0, target))
        return target.mode
    }

    private fun rotationX(degrees: Double): FloatArray {
        val radians = degrees * PI / 180.0
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    private fun assertQuaternion(target: VisualFramePose, w: Double, x: Double, y: Double, z: Double) {
        val sign = if (target.qw * w + target.qx * x + target.qy * y + target.qz * z < 0.0) -1.0 else 1.0
        assertEquals(w, sign * target.qw, 1e-6)
        assertEquals(x, sign * target.qx, 1e-6)
        assertEquals(y, sign * target.qy, 1e-6)
        assertEquals(z, sign * target.qz, 1e-6)
        assertTrue(abs(target.qw * target.qw + target.qx * target.qx + target.qy * target.qy + target.qz * target.qz - 1.0) < 1e-12)
    }
}