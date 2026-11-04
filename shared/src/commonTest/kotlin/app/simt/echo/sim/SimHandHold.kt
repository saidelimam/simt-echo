package app.simt.echo.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Hand-hold motions the stationary gate must distinguish. */
enum class SimMotion {
    /** Natural posture with tremor and millimetre sway; no step-scale acceleration. */
    STILL_IN_HAND,

    /** Deliberate 2 Hz vertical bob while standing still. */
    BOB_IN_PLACE,

    /** Faster, larger 3 Hz shake. */
    SHAKE,

    /** Forward travel with a natural 2 Hz gait bob. */
    WALK,
}

/**
 * One consistent 50 Hz stream of camera pose, accelerometer magnitude and
 * rotation-vector orientation for a hand-held phone.
 *
 * The base camera pitch is deliberately −25°: the device is tilted 65° from
 * the horizontal, which is the useful [PoseMode][app.simt.echo.capture.visual.PoseMode]
 * cone the real `VisualImuHistory`/`PoseModeEstimator` path expects. Motion is
 * analytic and seeded only through its phase, so every scenario is repeatable.
 */
class SimHandHold(
    val motion: SimMotion,
    val speedMps: Double = 1.2,
    private val start: SimPose = DEFAULT_START,
) {
    init {
        require(speedMps >= 0.0 && speedMps.isFinite())
    }

    fun poseAt(tSec: Double): SimPose {
        require(tSec.isFinite())
        return when (motion) {
            SimMotion.STILL_IN_HAND -> start.copy(
                x = start.x + 0.005 * sin(TWO_PI * 0.30 * tSec),
                y = start.y + 0.004 * sin(TWO_PI * 0.40 * tSec + 0.7),
                z = start.z + 0.002 * sin(TWO_PI * 0.60 * tSec),
                yawRad = start.yawRad + 0.004 * sin(TWO_PI * 0.30 * tSec + 0.4),
                pitchRad = BASE_PITCH + 0.006 * sin(TWO_PI * 0.50 * tSec + 0.2),
            )

            SimMotion.BOB_IN_PLACE -> start.copy(
                x = start.x + 0.010 * sin(TWO_PI * 0.50 * tSec),
                y = start.y + 0.010 * sin(TWO_PI * 0.50 * tSec + PI / 3.0),
                z = start.z + BOB_AMPLITUDE_M * sin(TWO_PI * BOB_HZ * tSec),
                yawRad = start.yawRad + 0.020 * sin(TWO_PI * 0.50 * tSec + 0.8),
                pitchRad = BASE_PITCH + BOB_PITCH_RAD * sin(TWO_PI * BOB_HZ * tSec + 0.4),
            )

            SimMotion.SHAKE -> start.copy(
                x = start.x + 0.012 * sin(TWO_PI * 1.0 * tSec),
                z = start.z + SHAKE_AMPLITUDE_M * sin(TWO_PI * SHAKE_HZ * tSec),
                yawRad = start.yawRad + SHAKE_YAW_RAD * sin(TWO_PI * SHAKE_HZ * tSec + 0.3),
                pitchRad = BASE_PITCH + SHAKE_PITCH_RAD * sin(TWO_PI * SHAKE_HZ * tSec + 0.9),
            )

            SimMotion.WALK -> start.copy(
                x = start.x + 0.008 * sin(TWO_PI * 0.50 * tSec),
                y = start.y + speedMps * tSec,
                z = start.z + BOB_AMPLITUDE_M * sin(TWO_PI * BOB_HZ * tSec),
                yawRad = start.yawRad + 0.010 * sin(TWO_PI * 1.0 * tSec),
                pitchRad = BASE_PITCH + 0.018 * sin(TWO_PI * BOB_HZ * tSec + 0.2),
            )
        }
    }

    /** Gravity plus linear acceleration along world-up, m/s², at [tSec]. */
    fun accelerationMagnitudeAt(tSec: Double): Double {
        require(tSec.isFinite())
        val vertical = when (motion) {
            SimMotion.STILL_IN_HAND -> 0.002 * (TWO_PI * 0.60).let { it * it } * sin(TWO_PI * 0.60 * tSec)
            SimMotion.BOB_IN_PLACE, SimMotion.WALK ->
                BOB_AMPLITUDE_M * (TWO_PI * BOB_HZ).let { it * it } * sin(TWO_PI * BOB_HZ * tSec)
            SimMotion.SHAKE ->
                SHAKE_AMPLITUDE_M * (TWO_PI * SHAKE_HZ).let { it * it } * sin(TWO_PI * SHAKE_HZ * tSec)
        }
        return GRAVITY - vertical
    }

    /** World-up unit vector in device coordinates, the third matrix row. */
    fun gravityUnitAt(tSec: Double): DoubleArray {
        val matrix = orientationMatrixAt(tSec)
        return doubleArrayOf(matrix[6].toDouble(), matrix[7].toDouble(), matrix[8].toDouble())
    }

    /** Device-to-world rotation matrix, row-major, at [tSec]. */
    fun orientationMatrixAt(tSec: Double): FloatArray {
        val pose = poseAt(tSec)
        val halfPitch = PI / 4.0 + pose.pitchRad / 2.0
        val halfYaw = -pose.yawRad / 2.0
        val qw = cos(halfYaw) * cos(halfPitch)
        val qx = cos(halfYaw) * sin(halfPitch)
        val qy = sin(halfYaw) * sin(halfPitch)
        val qz = sin(halfYaw) * cos(halfPitch)
        return floatArrayOf(
            (1.0 - 2.0 * (qy * qy + qz * qz)).toFloat(),
            (2.0 * (qx * qy - qz * qw)).toFloat(),
            (2.0 * (qx * qz + qy * qw)).toFloat(),
            (2.0 * (qx * qy + qz * qw)).toFloat(),
            (1.0 - 2.0 * (qx * qx + qz * qz)).toFloat(),
            (2.0 * (qy * qz - qx * qw)).toFloat(),
            (2.0 * (qx * qz - qy * qw)).toFloat(),
            (2.0 * (qy * qz + qx * qw)).toFloat(),
            (1.0 - 2.0 * (qx * qx + qy * qy)).toFloat(),
        )
    }

    /** Angular rates `(yaw, pitch)` in rad/s at [tSec], for motion-blur rendering. */
    fun angularRatesAt(tSec: Double): Pair<Double, Double> = when (motion) {
        SimMotion.STILL_IN_HAND -> 0.004 * TWO_PI * 0.30 * cos(TWO_PI * 0.30 * tSec + 0.4) to
            0.006 * TWO_PI * 0.50 * cos(TWO_PI * 0.50 * tSec + 0.2)
        SimMotion.BOB_IN_PLACE -> 0.020 * TWO_PI * 0.50 * cos(TWO_PI * 0.50 * tSec + 0.8) to
            BOB_PITCH_RAD * TWO_PI * BOB_HZ * cos(TWO_PI * BOB_HZ * tSec + 0.4)
        SimMotion.SHAKE -> SHAKE_YAW_RAD * TWO_PI * SHAKE_HZ * cos(TWO_PI * SHAKE_HZ * tSec + 0.3) to
            SHAKE_PITCH_RAD * TWO_PI * SHAKE_HZ * cos(TWO_PI * SHAKE_HZ * tSec + 0.9)
        SimMotion.WALK -> 0.010 * TWO_PI * 1.0 * cos(TWO_PI * 1.0 * tSec) to
            0.018 * TWO_PI * BOB_HZ * cos(TWO_PI * BOB_HZ * tSec + 0.2)
    }

    companion object {
        val BASE_PITCH = -25.0 * PI / 180.0
        // The scene runs from -4 m to 60 m. Starting near 4 m leaves a full
        // 12 s walk (14.4 m at 1.2 m/s) inside the rendered geometry.
        val DEFAULT_START = SimPose(x = 0.0, y = 4.0, z = 1.5, yawRad = 0.0, pitchRad = BASE_PITCH)
        private const val GRAVITY = 9.81
        private const val TWO_PI = 2.0 * PI
        private const val BOB_HZ = 2.0
        private const val SHAKE_HZ = 3.0
        private const val BOB_AMPLITUDE_M = 0.0139
        private const val SHAKE_AMPLITUDE_M = 0.020
        private const val BOB_PITCH_RAD = 0.035
        private const val SHAKE_PITCH_RAD = 0.035
        private const val SHAKE_YAW_RAD = 0.035
    }
}
