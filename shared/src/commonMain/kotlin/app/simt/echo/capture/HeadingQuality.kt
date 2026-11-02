package app.simt.echo.capture

import kotlin.math.PI
import kotlin.math.exp

/**
 * Heading quality for the built-in PDR engine (plan W12-01).
 *
 * Pure and deterministic: feed accelerometer magnitudes and raw yaw samples
 * in, read stillness, drift rate and quality out. No coroutines, no platform
 * imports, so this runs unmodified in `commonTest`.
 *
 * - Stillness = accelerometer-magnitude variance < 0.02 g² over the last
 *   0.5 s with no step accepted in the last 0.5 s.
 * - During stillness the yaw drift rate (rotation-vector gyro bias) is fit
 *   by least squares over the still episode (up to [FIT_WINDOW_S]) and
 *   clamped to ±2°/min. [correctedYaw] subtracts the accumulated drift.
 * - Quality q ∈ [0,1] multiplies a drift-residual factor, an axis-switch
 *   factor (`headingRad` camera/top hysteresis flips) and a turn-rate
 *   factor. W12-02 feeds q into the Ghost filter's heading likelihood and
 *   the σ aura.
 */
class HeadingQuality {

    /** True when the device has been still over the recent window. */
    var isStill: Boolean = false
        private set

    /** Estimated yaw drift rate, rad/s, clamped to ±2°/min. */
    var driftRateRadPerSec: Double = 0.0
        private set

    /** Heading quality q ∈ [0,1]; 1.0 is fully trusted. */
    var quality: Double = 1.0
        private set

    // Stillness window: (tNs, |a| in g), pruned to STILL_WINDOW_NS.
    private val accelWindow = ArrayDeque<Pair<Long, Double>>()
    private var lastStepTNs: Long? = null

    // Drift fit: still-episode (tSec, unwrapped yaw) points, pruned to FIT_WINDOW_S.
    private val fitPoints = ArrayDeque<Pair<Double, Double>>()
    private var lastRawYaw: Double? = null
    private var lastYawTNs: Long = 0L
    private var unwrappedYaw = 0.0
    private var residualRmsRad = 0.0

    // Turn-rate EMA state.
    private var turnRateRadPerSec = 0.0

    // Last axis-switch time; null before the first switch.
    private var lastSwitchTNs: Long? = null

    // Accumulated drift subtracted by correctedYaw (rad).
    private var driftOffsetRad = 0.0

    /** Feeds one accelerometer magnitude sample ([magnitudeG] = |a| / 9.81). */
    fun onAccelerometer(magnitudeG: Double, tNs: Long) {
        accelWindow.addLast(tNs to magnitudeG)
        val cutoff = tNs - STILL_WINDOW_NS
        while (accelWindow.size > 1 && accelWindow.first().first < cutoff) {
            accelWindow.removeFirst()
        }
        updateStillness(tNs)
    }

    /** Records an accepted step so stillness requires 0.5 s of quiet. */
    fun onStep(tNs: Long) {
        lastStepTNs = tNs
        updateStillness(tNs)
    }

    /**
     * Feeds one raw yaw sample ([rawYawRad] wrapped, e.g. `PdrEngine` yaw).
     * [axisSwitched] is true when `headingRad` flipped its camera/top axis
     * on this sample. Drift integrates on every sample; the drift *rate* is
     * re-fit only while still.
     */
    fun onYaw(rawYawRad: Double, tNs: Long, axisSwitched: Boolean) {
        val prev = lastRawYaw
        if (prev != null && tNs > lastYawTNs) {
            val dtSec = (tNs - lastYawTNs).toDouble() / 1e9
            if (dtSec > 0.0) {
                val delta = wrapPi(rawYawRad - prev)
                unwrappedYaw += delta
                val instRate = delta / dtSec
                val alpha = (dtSec / TURN_TAU_S).coerceIn(0.0, 1.0)
                turnRateRadPerSec += alpha * (instRate - turnRateRadPerSec)
                // Integrate the drift offset with the current rate estimate.
                driftOffsetRad += driftRateRadPerSec * dtSec
            }
        }
        lastRawYaw = rawYawRad
        lastYawTNs = tNs
        if (axisSwitched) lastSwitchTNs = tNs

        val wasStill = isStill
        updateStillness(tNs)
        if (!isStill) {
            if (wasStill) fitPoints.clear()
        } else {
            fitPoints.addLast(tNs.toDouble() / 1e9 to unwrappedYaw)
            val fitCutoff = tNs.toDouble() / 1e9 - FIT_WINDOW_S
            while (fitPoints.size > 1 && fitPoints.first().first < fitCutoff) {
                fitPoints.removeFirst()
            }
            fitDriftRate()
        }
        updateQuality(tNs)
    }

    /** Raw yaw with the accumulated drift subtracted, wrapped to (-π, π]. */
    fun correctedYaw(rawYawRad: Double): Double =
        wrapPi(rawYawRad - driftOffsetRad)

    fun reset() {
        isStill = false
        driftRateRadPerSec = 0.0
        quality = 1.0
        accelWindow.clear()
        lastStepTNs = null
        fitPoints.clear()
        lastRawYaw = null
        lastYawTNs = 0L
        unwrappedYaw = 0.0
        residualRmsRad = 0.0
        turnRateRadPerSec = 0.0
        lastSwitchTNs = null
        driftOffsetRad = 0.0
    }

    private fun updateStillness(tNs: Long) {
        val accelStill = accelWindow.size >= 2 && accelVarianceG2() < STILL_VAR_G2
        val lastStep = lastStepTNs
        val stepQuiet = lastStep == null || tNs - lastStep > STILL_WINDOW_NS
        isStill = accelStill && stepQuiet
    }

    private fun accelVarianceG2(): Double {
        val n = accelWindow.size
        if (n < 2) return Double.MAX_VALUE
        var mean = 0.0
        for ((_, m) in accelWindow) mean += m
        mean /= n
        var variance = 0.0
        for ((_, m) in accelWindow) {
            val d = m - mean
            variance += d * d
        }
        return variance / n
    }

    private fun fitDriftRate() {
        val n = fitPoints.size
        if (n < 3) return
        val span = fitPoints.last().first - fitPoints.first().first
        if (span < MIN_FIT_SPAN_S) return
        var sumT = 0.0
        var sumY = 0.0
        for ((t, y) in fitPoints) {
            sumT += t
            sumY += y
        }
        val meanT = sumT / n
        val meanY = sumY / n
        var num = 0.0
        var den = 0.0
        for ((t, y) in fitPoints) {
            val dt = t - meanT
            num += dt * (y - meanY)
            den += dt * dt
        }
        if (den <= 0.0) return
        val slope = num / den
        var residual = 0.0
        for ((t, y) in fitPoints) {
            val d = y - (meanY + slope * (t - meanT))
            residual += d * d
        }
        residualRmsRad = kotlin.math.sqrt(residual / n)
        driftRateRadPerSec = slope.coerceIn(-MAX_DRIFT_RAD_PER_SEC, MAX_DRIFT_RAD_PER_SEC)
    }

    private fun updateQuality(tNs: Long) {
        val residualFactor = 1.0 - (residualRmsRad / RESIDUAL_TOL_RAD).coerceIn(0.0, 1.0)
        val switchAt = lastSwitchTNs
        val switchPenalty = if (switchAt == null) {
            0.0
        } else {
            exp(-(tNs - switchAt).toDouble() / 1e9 / SWITCH_TAU_S).coerceIn(0.0, 1.0)
        }
        val switchFactor = 1.0 - SWITCH_PENALTY * switchPenalty
        val turnFactor = 1.0 / (1.0 + kotlin.math.abs(turnRateRadPerSec) / TURN_REF_RAD_PER_SEC)
        quality = (residualFactor * switchFactor * turnFactor).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Stillness window: 0.5 s (plan W12-01). */
        const val STILL_WINDOW_NS = 500_000_000L

        /** Stillness threshold: accel-magnitude variance < 0.02 g². */
        const val STILL_VAR_G2 = 0.02

        /** Drift-rate clamp: ±2°/min in rad/s. */
        val MAX_DRIFT_RAD_PER_SEC = 2.0 * PI / 180.0 / 60.0

        /** Still-episode fit window for the drift slope. */
        const val FIT_WINDOW_S = 8.0

        /** Minimum still-episode span before the drift fit updates. */
        const val MIN_FIT_SPAN_S = 1.0

        /** Drift residual RMS that drives the residual factor to zero (2°). */
        val RESIDUAL_TOL_RAD = 2.0 * PI / 180.0

        /** Axis-switch penalty weight and decay time constant. */
        const val SWITCH_PENALTY = 0.5
        const val SWITCH_TAU_S = 2.0

        /** Turn-rate EMA time constant and reference rate (30°/s). */
        const val TURN_TAU_S = 0.2
        val TURN_REF_RAD_PER_SEC = 30.0 * PI / 180.0
    }
}
