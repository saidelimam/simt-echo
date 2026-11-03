package app.simt.echo.capture

import app.simt.echo.capture.visual.VisualEgo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Heading quality and three-witness triangulation for the built-in PDR
 * engine (plans W12-01, W16-03).
 *
 * Pure and deterministic: feed accelerometer magnitudes, raw yaw samples,
 * optional gyroscope yaw rates, magnetometer scalars and visual ego-motion
 * in; read stillness, drift rate, quality, corrected yaw and travel-direction
 * offset out.When visual confidence is 0 and no magnetic anomaly is present,
 * behaviour is bit-identical to W12-01.
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

    /** Magnetic field anomaly score ∈ [0,1] (W16-03); 0.0 on clean field. */
    var magAnomaly01: Double = 0.0
        private set

    /** Slowly-varying camera-to-travel heading offset from visual FOE (rad, W16-03). */
    var travelOffsetRad: Double = 0.0
        private set

    /** Active visual confidence ∈ [0,1]; 0.0 when visual is silent or stale. */
    var visualConfidence01: Double = 0.0
        private set

    /** Walking gyro-bias estimate from visual yaw residuals (rad/s, W16-03). */
    var visualGyroBiasRadPerSec: Double = 0.0
        private set

    private val accelWindow = ArrayDeque<Pair<Long, Double>>()
    private var lastStepTNs: Long? = null
    private val fitPoints = ArrayDeque<Pair<Double, Double>>()
    private var lastRawYaw: Double? = null
    private var lastYawTNs: Long = 0L
    private var unwrappedYaw = 0.0
    private var residualRmsRad = 0.0
    private var turnRateRadPerSec = 0.0
    private var lastSwitchTNs: Long? = null
    private var driftOffsetRad = 0.0

    private var lastGyroYawRate: Double = 0.0
    private var lastGyroTNs: Long? = null
    private var secularDriftRateEma: Double = 0.0
    private var lastVisualTNs: Long = Long.MIN_VALUE
    private var hasTravelOffset: Boolean = false

    private var baselineMagUt: Double = Double.NaN
    private var baselineVertUt: Double = Double.NaN
    private var prevVertUt: Double = Double.NaN
    private var vertNoiseUt: Double = 0.0
    private var lastMagTNs: Long? = null
    private var lastMagAnomalyTNs: Long? = null

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

    /** Feeds one clockwise-positive gyroscope yaw rate sample (rad/s, W16-03). */
    fun onGyro(yawRateRadPerSec: Double, tNs: Long) {
        if (!yawRateRadPerSec.isFinite()) return
        lastGyroYawRate = yawRateRadPerSec
        lastGyroTNs = tNs
    }

    /**
     * Feeds reduced magnetometer scalars ([magUt] = |B|, [vertUt] = vertical
     * projection in µT, W16-03). Detects steel anomalies by field deviation
     * from the clean baseline and lowers [quality].
     */
    fun onMagnetic(magUt: Double, vertUt: Double = Double.NaN, tNs: Long = lastYawTNs) {
        if (!magUt.isFinite() || magUt <= 0.0) return
        if (!baselineMagUt.isFinite()) {
            baselineMagUt = magUt
            if (vertUt.isFinite()) {
                baselineVertUt = vertUt
                prevVertUt = vertUt
            }
            lastMagTNs = tNs
            return
        }
        val magDev = abs(magUt - baselineMagUt)
        val magScore = ((magDev - MAG_CLEAN_DEV_UT) / (MAG_FULL_DEV_UT - MAG_CLEAN_DEV_UT)).coerceIn(0.0, 1.0)
        var vertScore = 0.0
        if (vertUt.isFinite()) {
            if (!baselineVertUt.isFinite()) {
                baselineVertUt = vertUt
                prevVertUt = vertUt
            } else {
                val vertDev = abs(vertUt - baselineVertUt)
                val vertThresh = maxOf(VERT_MIN_THRESH_UT, 4.0 * vertNoiseUt)
                val vertFull = maxOf(VERT_MIN_FULL_UT, 8.0 * vertNoiseUt)
                vertScore = ((vertDev - vertThresh) / (vertFull - vertThresh)).coerceIn(0.0, 1.0)
            }
        }
        val instAnomaly = maxOf(magScore, vertScore)
        if (instAnomaly > 0.05) {
            lastMagAnomalyTNs = tNs
            magAnomaly01 = maxOf(magAnomaly01, instAnomaly).coerceIn(0.0, 1.0)
        } else {
            baselineMagUt += MAG_BASELINE_ALPHA * (magUt - baselineMagUt)
            if (vertUt.isFinite() && baselineVertUt.isFinite()) {
                val stepDiff = if (prevVertUt.isFinite()) abs(vertUt - prevVertUt) else 0.0
                vertNoiseUt += 0.05 * (stepDiff - vertNoiseUt)
                baselineVertUt += MAG_BASELINE_ALPHA * (vertUt - baselineVertUt)
            }
            val prevT = lastMagTNs
            val dtSec = if (prevT != null && tNs > prevT) (tNs - prevT).toDouble() / 1e9 else 0.05
            val decay = exp(-dtSec / MAG_DECAY_TAU_S).coerceIn(0.0, 1.0)
            magAnomaly01 = (magAnomaly01 * decay).let { if (it < 1e-4) 0.0 else it }
        }
        if (vertUt.isFinite()) prevVertUt = vertUt
        lastMagTNs = tNs
        updateQuality(tNs)
    }

    /** Feeds one visual ego-motion observation (W16-03). */
    fun onVisualEgo(ego: VisualEgo, tNs: Long) {
        onVisual(
            yawResidualRadPerSec = ego.yawResidualRadPerSec,
            yawSigmaRadPerSec = ego.yawSigmaRadPerSec,
            travelOffsetRad = ego.travelOffsetRad,
            hasFoe = ego.hasFoe,
            confidence01 = ego.confidence01,
            tNs = tNs,
        )
    }

    /** Primitive visual ego-motion feed (allocation-free, W16-03). */
    fun onVisual(
        yawResidualRadPerSec: Double,
        yawSigmaRadPerSec: Double,
        travelOffsetRad: Double,
        hasFoe: Boolean,
        confidence01: Double,
        tNs: Long,
    ) {
        if (!confidence01.isFinite() || confidence01 < MIN_VISUAL_CONFIDENCE) {
            if (tNs >= lastVisualTNs) visualConfidence01 = 0.0
            return
        }
        val dtSec = if (lastVisualTNs != Long.MIN_VALUE && tNs > lastVisualTNs) {
            ((tNs - lastVisualTNs).toDouble() / 1e9).coerceIn(0.02, 2.0)
        } else {
            0.5
        }
        lastVisualTNs = tNs
        visualConfidence01 = confidence01.coerceIn(0.0, 1.0)
        if (yawResidualRadPerSec.isFinite() &&
            yawSigmaRadPerSec.isFinite() &&
            yawSigmaRadPerSec > 0.0 &&
            yawSigmaRadPerSec <= MAX_VISUAL_YAW_SIGMA
        ) {
            val observedBias = (-yawResidualRadPerSec).coerceIn(-MAX_DRIFT_RAD_PER_SEC, MAX_DRIFT_RAD_PER_SEC)
            val sigma2 = yawSigmaRadPerSec * yawSigmaRadPerSec
            val ref2 = REF_VISUAL_YAW_SIGMA * REF_VISUAL_YAW_SIGMA
            val precisionWeight = ref2 / (sigma2 + ref2)
            val alpha = (dtSec / VISUAL_BIAS_TAU_S * visualConfidence01 * precisionWeight).coerceIn(0.05, 0.5)
            visualGyroBiasRadPerSec = (visualGyroBiasRadPerSec + alpha * (observedBias - visualGyroBiasRadPerSec))
                .coerceIn(-MAX_DRIFT_RAD_PER_SEC, MAX_DRIFT_RAD_PER_SEC)
            if (!isStill) driftRateRadPerSec = visualGyroBiasRadPerSec
        }
        if (hasFoe && travelOffsetRad.isFinite()) {
            val clamped = travelOffsetRad.coerceIn(-MAX_TRAVEL_OFFSET_RAD, MAX_TRAVEL_OFFSET_RAD)
            if (!hasTravelOffset) {
                this.travelOffsetRad = clamped
                hasTravelOffset = true
            } else {
                val alpha = (dtSec / TRAVEL_OFFSET_TAU_S * visualConfidence01).coerceIn(0.02, 0.35)
                this.travelOffsetRad = wrapPi(this.travelOffsetRad + alpha * wrapPi(clamped - this.travelOffsetRad))
                    .coerceIn(-MAX_TRAVEL_OFFSET_RAD, MAX_TRAVEL_OFFSET_RAD)
            }
        }
    }

    /** Feeds one raw yaw sample ([rawYawRad] wrapped, e.g. `PdrEngine` yaw). */
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

                val gyroT = lastGyroTNs
                val hasGyro = gyroT != null && tNs >= gyroT && tNs - gyroT <= GYRO_STALE_NS
                val visActive = lastVisualTNs != Long.MIN_VALUE &&
                    tNs >= lastVisualTNs &&
                    tNs - lastVisualTNs <= VISUAL_HOLD_WINDOW_NS &&
                    visualConfidence01 >= MIN_VISUAL_CONFIDENCE
                val magAnomT = lastMagAnomalyTNs
                val magGateActive = magAnomaly01 >= MAG_GATE_ON ||
                    (magAnomT != null && tNs >= magAnomT && tNs - magAnomT <= MAG_HOLD_NS)

                if (hasGyro && (visActive || magGateActive)) {
                    val gyroCorrRate = lastGyroYawRate - visualGyroBiasRadPerSec
                    val deltaExcess = wrapPi(delta - gyroCorrRate * dtSec)
                    driftOffsetRad += deltaExcess
                    if (visActive && !magGateActive && !isStill) {
                        val excessRate = (deltaExcess / dtSec + visualGyroBiasRadPerSec)
                            .coerceIn(-MAX_DRIFT_RAD_PER_SEC, MAX_DRIFT_RAD_PER_SEC)
                        val driftAlpha = (dtSec / SECULAR_DRIFT_TAU_S).coerceIn(0.005, 0.2)
                        secularDriftRateEma = (secularDriftRateEma + driftAlpha * (excessRate - secularDriftRateEma))
                            .coerceIn(-MAX_DRIFT_RAD_PER_SEC, MAX_DRIFT_RAD_PER_SEC)
                        driftRateRadPerSec = secularDriftRateEma
                    }
                } else {
                    if (!visActive && lastVisualTNs != Long.MIN_VALUE && tNs - lastVisualTNs > VISUAL_HOLD_WINDOW_NS) {
                        visualConfidence01 = 0.0
                    }
                    driftOffsetRad += driftRateRadPerSec * dtSec
                }
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
    fun correctedYaw(rawYawRad: Double): Double = wrapPi(rawYawRad - driftOffsetRad)

    /** Fused travel heading (rad, W16-03): drift-corrected camera yaw plus FOE travel offset. */
    fun fusedHeading(rawYawRad: Double): Double = wrapPi(rawYawRad - driftOffsetRad + travelOffsetRad)

    fun reset() {
        isStill = false
        driftRateRadPerSec = 0.0
        quality = 1.0
        magAnomaly01 = 0.0
        travelOffsetRad = 0.0
        visualConfidence01 = 0.0
        visualGyroBiasRadPerSec = 0.0
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
        lastGyroYawRate = 0.0
        lastGyroTNs = null
        secularDriftRateEma = 0.0
        lastVisualTNs = Long.MIN_VALUE
        hasTravelOffset = false
        baselineMagUt = Double.NaN
        baselineVertUt = Double.NaN
        prevVertUt = Double.NaN
        vertNoiseUt = 0.0
        lastMagTNs = null
        lastMagAnomalyTNs = null
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
        residualRmsRad = sqrt(residual / n)
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
        val turnFactor = 1.0 / (1.0 + abs(turnRateRadPerSec) / TURN_REF_RAD_PER_SEC)
        val magFactor = 1.0 - MAG_ANOMALY_PENALTY * magAnomaly01.coerceIn(0.0, 1.0)
        quality = (residualFactor * switchFactor * turnFactor * magFactor).coerceIn(0.0, 1.0)
    }

    companion object {
        const val STILL_WINDOW_NS = 500_000_000L
        const val STILL_VAR_G2 = 0.02
        val MAX_DRIFT_RAD_PER_SEC = 2.0 * PI / 180.0 / 60.0
        const val FIT_WINDOW_S = 8.0
        const val MIN_FIT_SPAN_S = 1.0
        val RESIDUAL_TOL_RAD = 2.0 * PI / 180.0
        const val SWITCH_PENALTY = 0.5
        const val SWITCH_TAU_S = 2.0
        const val TURN_TAU_S = 0.2
        val TURN_REF_RAD_PER_SEC = 30.0 * PI / 180.0

        const val MIN_VISUAL_CONFIDENCE = 0.5
        const val MAX_VISUAL_YAW_SIGMA = 0.08
        private const val REF_VISUAL_YAW_SIGMA = 0.005
        private const val VISUAL_BIAS_TAU_S = 2.5
        private const val SECULAR_DRIFT_TAU_S = 4.0
        private const val TRAVEL_OFFSET_TAU_S = 2.5
        val MAX_TRAVEL_OFFSET_RAD = 30.0 * PI / 180.0
        const val VISUAL_HOLD_WINDOW_NS = 2_000_000_000L
        const val GYRO_STALE_NS = 250_000_000L

        const val MAG_ANOMALY_PENALTY = 0.75
        private const val MAG_CLEAN_DEV_UT = 1.5
        private const val MAG_FULL_DEV_UT = 6.0
        private const val VERT_MIN_THRESH_UT = 0.002
        private const val VERT_MIN_FULL_UT = 0.020
        private const val MAG_BASELINE_ALPHA = 0.02
        private const val MAG_DECAY_TAU_S = 0.5
        private const val MAG_GATE_ON = 0.02
        private const val MAG_HOLD_NS = 1_500_000_000L
    }
}
