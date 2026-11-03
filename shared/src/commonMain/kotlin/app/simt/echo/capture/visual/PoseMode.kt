package app.simt.echo.capture.visual

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class PoseMode { HELD_UP, FLAT, SWINGING, POCKET, EAR, UNKNOWN }

/**
 * Pure conservative hold classifier. Gravity uses handset axes (+y towards
 * its top, +z out of the screen); acceleration is signed linear acceleration
 * along gravity, in m/s². A fixed 20 Hz, 3.2 s history distinguishes dominant
 * 1–2 Hz arm swing from arbitrary shaking. No proximity sensor is assumed:
 * EAR is a sideways-hold proxy, not a claim that a call was detected.
 */
class PoseModeEstimator {
    private val times = LongArray(CAPACITY)
    private val signal = DoubleArray(CAPACITY)
    private val x = DoubleArray(CAPACITY)
    private val y = DoubleArray(CAPACITY)
    private val z = DoubleArray(CAPACITY)
    private var next = 0
    private var count = 0
    private var lastTimeNs = 0L
    private var tiltDeg = 0.0
    private var sideways = false

    fun reset() {
        next = 0
        count = 0
        lastTimeNs = 0L
    }

    fun onSample(tNs: Long, gravityX: Double, gravityY: Double, gravityZ: Double, linearAcceleration: Double) {
        val norm = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        if (!norm.isFinite() || norm < 1e-6 || !linearAcceleration.isFinite()) {
            reset()
            return
        }
        if (count > 0) {
            if (tNs <= lastTimeNs || tNs - lastTimeNs < SAMPLE_INTERVAL_NS) return
            if (tNs - lastTimeNs > STALE_NS) reset()
        }
        x[next] = gravityX / norm
        y[next] = gravityY / norm
        z[next] = gravityZ / norm
        tiltDeg = acos(abs(z[next]).coerceIn(0.0, 1.0)) * 180.0 / PI
        sideways = abs(x[next]) > 0.85
        signal[next] = linearAcceleration
        times[next] = tNs
        next = (next + 1) % CAPACITY
        count = (count + 1).coerceAtMost(CAPACITY)
        lastTimeNs = tNs
    }

    fun classify(meanLuma: Double, nowNs: Long): PoseMode {
        if (count == 0 || !meanLuma.isFinite() || nowNs < lastTimeNs || nowNs - lastTimeNs > STALE_NS) return PoseMode.UNKNOWN
        val oldest = (next - count + CAPACITY) % CAPACITY
        val spanNs = lastTimeNs - times[oldest]
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var mean = 0.0
        for (i in 0 until count) {
            val slot = (oldest + i) % CAPACITY
            sx += x[slot]
            sy += y[slot]
            sz += z[slot]
            mean += signal[slot]
        }
        mean /= count
        val orientationVariance = (1.0 - (sx * sx + sy * sy + sz * sz) / (count.toDouble() * count)).coerceAtLeast(0.0)
        if (meanLuma < 16.0 && spanNs >= 1_000_000_000L && orientationVariance < 0.003) return PoseMode.POCKET
        // Walking has cadence too; only a changing hold plus that spectrum is arm swing.
        if (spanNs >= 2_000_000_000L && orientationVariance >= 0.01 && isSwinging(oldest, mean)) return PoseMode.SWINGING
        if (sideways) return PoseMode.EAR
        if (tiltDeg < 20.0) return PoseMode.FLAT
        // Outside the declared useful 20–80 degree cone is unknown, not usable.
        return if (tiltDeg <= 80.0) PoseMode.HELD_UP else PoseMode.UNKNOWN
    }

    private fun isSwinging(oldest: Int, mean: Double): Boolean {
        var variance = 0.0
        for (i in 0 until count) {
            val value = signal[(oldest + i) % CAPACITY] - mean
            variance += value * value / count
        }
        if (variance < 0.4 * 0.4) return false
        var bestPower = 0.0
        var bestFrequency = 0.0
        for (bin in 2..12) {
            val frequency = bin * 0.25
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until count) {
                val slot = (oldest + i) % CAPACITY
                val phase = 2.0 * PI * frequency * (times[slot] - times[oldest]) / 1e9
                val value = signal[slot] - mean
                real += value * cos(phase)
                imaginary += value * sin(phase)
            }
            val power = 2.0 * (real * real + imaginary * imaginary) / (count.toDouble() * count)
            if (power > bestPower) {
                bestPower = power
                bestFrequency = frequency
            }
        }
        return bestFrequency in 1.0..2.0 && bestPower >= 0.65 * variance
    }

    companion object {
        const val SAMPLE_INTERVAL_NS = 50_000_000L
        const val STALE_NS = 500_000_000L
        private const val CAPACITY = 64
    }
}
