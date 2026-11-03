package app.simt.echo.capture.visual

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finite rotation of one normalised image point (x right, y down, z forward).
 * Positive yaw moves the image left, positive pitch moves it down, and positive
 * roll follows the same first-order field. Reused in place; no allocation.
 */
internal class PinholeRotation {
    var x: Double = 0.0
    var y: Double = 0.0

    fun map(xNorm: Double, yNorm: Double, yaw: Double, pitch: Double, roll: Double, inverse: Boolean): Boolean {
        val sign = if (inverse) -1.0 else 1.0
        val wx = -pitch * sign
        val wy = -yaw * sign
        val wz = -roll * sign
        val theta = sqrt(wx * wx + wy * wy + wz * wz)
        if (theta < SMALL_ANGLE) {
            x = xNorm
            y = yNorm
            return true
        }
        val inv = 1.0 / theta
        val kx = wx * inv
        val ky = wy * inv
        val kz = wz * inv
        val cosine = cos(theta)
        val sine = sin(theta)
        val dot = kx * xNorm + ky * yNorm + kz
        val crossX = ky - kz * yNorm
        val crossY = kz * xNorm - kx
        val crossZ = kx * yNorm - ky * xNorm
        val preserved = 1.0 - cosine
        val mappedX = xNorm * cosine + crossX * sine + kx * dot * preserved
        val mappedY = yNorm * cosine + crossY * sine + ky * dot * preserved
        val mappedZ = cosine + crossZ * sine + kz * dot * preserved
        if (!mappedZ.isFinite() || mappedZ <= MIN_FORWARD) return false
        x = mappedX / mappedZ
        y = mappedY / mappedZ
        return x.isFinite() && y.isFinite()
    }

    private companion object {
        const val SMALL_ANGLE = 1e-12
        const val MIN_FORWARD = 0.05
    }
}
