package app.simt.echo.capture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Heading math (implementation plan §6.1). Angles are radians,
 * clockwise-positive (a right turn increases yaw), wrapped to (-π, π].
 * Device rotation uses the game rotation vector (gyro + accelerometer,
 * no magnetometer) so garage steel cannot pull the heading.
 */

/** Wraps [angleRad] to (-π, π]. */
fun wrapPi(angleRad: Double): Double {
    var a = angleRad % (2.0 * PI)
    if (a > PI) a -= 2.0 * PI
    if (a < -PI) a += 2.0 * PI
    return a
}

/** Mean of angles on the circle; undefined (near-zero resultant) → 0.0. */
fun circularMean(angles: List<Double>): Double {
    if (angles.isEmpty()) return 0.0
    var sinSum = 0.0
    var cosSum = 0.0
    for (angle in angles) {
        sinSum += kotlin.math.sin(angle)
        cosSum += kotlin.math.cos(angle)
    }
    return atan2(sinSum, cosSum)
}

/**
 * Heading from a row-major device→world rotation matrix [r] (from
 * `SensorManager.getRotationMatrixFromVector`), with camera/top axis
 * hysteresis: |R22| near 1 means the phone lies flat (use device-top
 * axis); near 0 means upright (use back-camera axis). [prevCamera] is
 * the previous frame's decision so the axis cannot flicker at the
 * 0.6–0.8 band edge.
 */
fun headingRad(r: FloatArray, prevCamera: Boolean): Pair<Double, Boolean> {
    require(r.size >= 9) { "rotation matrix needs 9 elements, got ${r.size}" }
    val flatness = abs(r[8])
    val camera = if (prevCamera) flatness < 0.8f else flatness < 0.6f
    val heading = if (camera) {
        atan2(-r[2].toDouble(), -r[5].toDouble())
    } else {
        atan2(r[1].toDouble(), r[4].toDouble())
    }
    return heading to camera
}
