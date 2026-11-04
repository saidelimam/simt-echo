package app.simt.echo.capture.visual

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** One IMU producer and one analysis reader; all storage is allocated at construction. */
class VisualImuHistory(private val capacity: Int = 256) : VisualMotionInput {
    init {
        require(capacity > 0)
    }

    private class Sample {
        @Volatile var stamp = 0L
        // Volatile payload also prevents loads crossing the closing guard on weak memory models.
        @Volatile var tNs = 0L
        @Volatile var qw = 1.0
        @Volatile var qx = 0.0
        @Volatile var qy = 0.0
        @Volatile var qz = 0.0
        @Volatile var gx = 0.0
        @Volatile var gy = 0.0
        @Volatile var gz = 1.0
        @Volatile var acceleration = 0.0

        fun copyFrom(other: Sample) {
            tNs = other.tNs
            qw = other.qw
            qx = other.qx
            qy = other.qy
            qz = other.qz
            gx = other.gx
            gy = other.gy
            gz = other.gz
            acceleration = other.acceleration
        }
    }

    private val slots = Array(capacity) { Sample() }
    @Volatile private var publishedSequence = 0L
    @Volatile private var latestTimeNs = -1L
    private var writtenSequence = 0L
    private var rotationTimeNs = -1L
    private var accelerationTimeNs = -1L
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 1.0
    private var linearAcceleration = 0.0

    private val lower = Sample()
    private val upper = Sample()
    private val scratch = Sample()
    private val estimator = PoseModeEstimator()
    private var nextEstimatorSequence = 0L
    private var lastReadTimeNs = -1L

    /** Android row-major 3x3 device-to-world rotation. The supplied array is never retained. */
    fun onRotation(tNs: Long, matrix: FloatArray) {
        if (tNs < 0L || tNs <= rotationTimeNs || matrix.size != 9) return
        val m00 = matrix[0].toDouble()
        val m01 = matrix[1].toDouble()
        val m02 = matrix[2].toDouble()
        val m10 = matrix[3].toDouble()
        val m11 = matrix[4].toDouble()
        val m12 = matrix[5].toDouble()
        val m20 = matrix[6].toDouble()
        val m21 = matrix[7].toDouble()
        val m22 = matrix[8].toDouble()
        if (!m00.isFinite() || !m01.isFinite() || !m02.isFinite() ||
            !m10.isFinite() || !m11.isFinite() || !m12.isFinite() ||
            !m20.isFinite() || !m21.isFinite() || !m22.isFinite()
        ) return
        if (abs(m00 * m00 + m01 * m01 + m02 * m02 - 1.0) > MATRIX_TOLERANCE ||
            abs(m10 * m10 + m11 * m11 + m12 * m12 - 1.0) > MATRIX_TOLERANCE ||
            abs(m20 * m20 + m21 * m21 + m22 * m22 - 1.0) > MATRIX_TOLERANCE ||
            abs(m00 * m10 + m01 * m11 + m02 * m12) > MATRIX_TOLERANCE ||
            abs(m00 * m20 + m01 * m21 + m02 * m22) > MATRIX_TOLERANCE ||
            abs(m10 * m20 + m11 * m21 + m12 * m22) > MATRIX_TOLERANCE ||
            abs(m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) +
                m02 * (m10 * m21 - m11 * m20) - 1.0) > MATRIX_TOLERANCE
        ) return

        var qw: Double
        var qx: Double
        var qy: Double
        var qz: Double
        val trace = m00 + m11 + m22
        if (trace > 0.0) {
            val scale = 2.0 * sqrt(trace + 1.0)
            qw = scale * 0.25
            qx = (m21 - m12) / scale
            qy = (m02 - m20) / scale
            qz = (m10 - m01) / scale
        } else if (m00 > m11 && m00 > m22) {
            val scale = 2.0 * sqrt(1.0 + m00 - m11 - m22)
            qw = (m21 - m12) / scale
            qx = scale * 0.25
            qy = (m01 + m10) / scale
            qz = (m02 + m20) / scale
        } else if (m11 > m22) {
            val scale = 2.0 * sqrt(1.0 + m11 - m00 - m22)
            qw = (m02 - m20) / scale
            qx = (m01 + m10) / scale
            qy = scale * 0.25
            qz = (m12 + m21) / scale
        } else {
            val scale = 2.0 * sqrt(1.0 + m22 - m00 - m11)
            qw = (m10 - m01) / scale
            qx = (m02 + m20) / scale
            qy = (m12 + m21) / scale
            qz = scale * 0.25
        }
        val norm = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (!norm.isFinite() || norm < 1e-12) return
        qw /= norm
        qx /= norm
        qy /= norm
        qz /= norm

        val sequence = writtenSequence + 1L
        val slot = slots[((sequence - 1L) % capacity).toInt()]
        // Odd means being overwritten; the even generation publishes the complete slot payload.
        slot.stamp = sequence * 2L - 1L
        slot.tNs = tNs
        slot.qw = qw
        slot.qx = qx
        slot.qy = qy
        slot.qz = qz
        slot.gx = m20
        slot.gy = m21
        slot.gz = m22
        slot.acceleration = if (accelerationTimeNs <= tNs) linearAcceleration else 0.0
        slot.stamp = sequence * 2L
        gravityX = m20
        gravityY = m21
        gravityZ = m22
        rotationTimeNs = tNs
        writtenSequence = sequence
        latestTimeNs = tNs
        publishedSequence = sequence
    }

    fun onAccelerometer(tNs: Long, ax: Double, ay: Double, az: Double) {
        if (rotationTimeNs < 0L || tNs < rotationTimeNs || tNs <= accelerationTimeNs ||
            !ax.isFinite() || !ay.isFinite() || !az.isFinite()
        ) return
        val projected = ax * gravityX + ay * gravityY + az * gravityZ - GRAVITY
        if (!projected.isFinite()) return
        linearAcceleration = projected
        accelerationTimeNs = tNs
    }

    /**
     * Advisory freshness only: readAt still requires a real bracket, never
     * extrapolation. A past frame may arrive after [MAX_GAP_NS] of camera +
     * analysis latency; it is readable while the history still brackets it.
     * Only a request materially in the future is unavailable.
     */
    override fun isAvailable(tNs: Long): Boolean {
        val latest = latestTimeNs
        return tNs >= 0L && latest >= 0L && tNs <= latest + MAX_GAP_NS && latest - tNs <= MAX_PAST_NS
    }

    override fun readAt(tNs: Long, meanLuma: Double, target: VisualFramePose): Boolean {
        if (tNs < 0L || tNs < lastReadTimeNs || !meanLuma.isFinite()) return false
        val latest = publishedSequence
        if (latest == 0L) return false
        val oldest = maxOf(1L, latest - capacity + 1L)
        var sequence = oldest
        var found = false
        var hasLower = false
        var exact = false
        while (sequence <= latest) {
            if (!copySample(sequence, upper)) return false
            if (hasLower && upper.tNs <= lower.tNs) return false
            if (upper.tNs >= tNs) {
                exact = upper.tNs == tNs
                if (!exact && !hasLower) return false
                found = true
                break
            }
            lower.copyFrom(upper)
            hasLower = true
            sequence++
        }
        if (!found) return false

        // At the oldest exact sample, an available following sample can supply angular rate.
        if (exact && !hasLower) {
            lower.copyFrom(upper)
            if (sequence < latest) {
                if (!copySample(sequence + 1L, upper) || upper.tNs <= lower.tNs) return false
            }
        }
        var gap = upper.tNs - lower.tNs
        if (gap > MAX_GAP_NS) {
            if (!exact) return false
            if (upper.tNs == tNs) lower.copyFrom(upper) else upper.copyFrom(lower)
            gap = 0L
        }
        if (!exact && gap <= 0L) return false
        val dot = lower.qw * upper.qw + lower.qx * upper.qx + lower.qy * upper.qy + lower.qz * upper.qz
        val sign = if (dot < 0.0) -1.0 else 1.0
        val weight = if (gap == 0L) 0.0 else (tNs - lower.tNs).toDouble() / gap
        var qw = lower.qw * (1.0 - weight) + sign * upper.qw * weight
        var qx = lower.qx * (1.0 - weight) + sign * upper.qx * weight
        var qy = lower.qy * (1.0 - weight) + sign * upper.qy * weight
        var qz = lower.qz * (1.0 - weight) + sign * upper.qz * weight
        val norm = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (!norm.isFinite() || norm < 1e-12) return false
        qw /= norm
        qx /= norm
        qy /= norm
        qz /= norm
        val dx = lower.qw * upper.qx - lower.qx * upper.qw - lower.qy * upper.qz + lower.qz * upper.qy
        val dy = lower.qw * upper.qy + lower.qx * upper.qz - lower.qy * upper.qw - lower.qz * upper.qx
        val dz = lower.qw * upper.qz - lower.qx * upper.qy + lower.qy * upper.qx - lower.qz * upper.qw
        val rate = if (gap == 0L) 0.0 else 2.0 * atan2(sqrt(dx * dx + dy * dy + dz * dz), abs(dot)) / (gap / 1e9)
        if (!rate.isFinite() || !feedEstimator(oldest, latest, tNs)) return false
        target.qw = qw
        target.qx = qx
        target.qy = qy
        target.qz = qz
        target.angularRateRadPerSec = rate
        target.mode = estimator.classify(meanLuma, tNs)
        lastReadTimeNs = tNs
        return true
    }

    private fun copySample(sequence: Long, target: Sample): Boolean {
        val slot = slots[((sequence - 1L) % capacity).toInt()]
        val expected = sequence * 2L
        if (slot.stamp != expected) return false
        target.copyFrom(slot)
        return slot.stamp == expected
    }

    private fun feedEstimator(oldest: Long, latest: Long, tNs: Long): Boolean {
        if (nextEstimatorSequence < oldest) {
            estimator.reset()
            nextEstimatorSequence = oldest
        }
        while (nextEstimatorSequence <= latest) {
            if (!copySample(nextEstimatorSequence, scratch)) {
                estimator.reset()
                nextEstimatorSequence = 0L
                return false
            }
            if (scratch.tNs > tNs) break
            estimator.onSample(scratch.tNs, scratch.gx, scratch.gy, scratch.gz, scratch.acceleration)
            nextEstimatorSequence++
        }
        return true
    }

    companion object {
        private const val GRAVITY = 9.81
        private const val MAX_GAP_NS = 100_000_000L
        private const val MAX_PAST_NS = 2_000_000_000L
        private const val MATRIX_TOLERANCE = 0.01
    }
}