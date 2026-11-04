package app.simt.echo.nav.ghost

import app.simt.echo.capture.wrapPi
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.TurnDir
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Along-route particle filter — the Ghost navigator core (plans W12-02, W18-03).
 *
 * Particles carry `s` (metres along the route polyline), `k` (stride scale,
 * prior N(1, 0.08)) and `b` (heading bias, prior N(0, 5°)) in fixed-capacity
 * struct-of-arrays [DoubleArray]s (`MAX_PARTICLE_COUNT = 512`) with a variable
 * active particle count [activeN] (`96..512`, default `256`).
 *
 * Allocation audit (≤ 1 ms/update budget): every array is sized
 * [MAX_PARTICLE_COUNT] in the constructor and reused — [predict], [observe],
 * and [resizeParticles] allocate nothing.
 */
class GhostFilter(
    route: EchoRoute,
    seed: Long = DEFAULT_SEED,
    kPriorMean: Double = 1.0,
) {
    private val geometry = RouteGeometry(route)
    private var random = Random(seed)
    private var resizeRandom = Random(seed xor RESIZE_SEED_SALT)
    private val initialSeed = seed
    private val kPrior: Double =
        if (kPriorMean.isFinite() && kPriorMean in STRIDE_K_MIN..STRIDE_K_MAX) kPriorMean else 1.0

    private val sA = DoubleArray(MAX_PARTICLE_COUNT)
    private val kA = DoubleArray(MAX_PARTICLE_COUNT)
    private val bA = DoubleArray(MAX_PARTICLE_COUNT)
    private val wA = DoubleArray(MAX_PARTICLE_COUNT)
    private val cumA = DoubleArray(MAX_PARTICLE_COUNT)
    private val tmpS = DoubleArray(MAX_PARTICLE_COUNT)
    private val tmpK = DoubleArray(MAX_PARTICLE_COUNT)
    private val tmpB = DoubleArray(MAX_PARTICLE_COUNT)

    private val sigExp = DoubleArray(MAX_SIGNATURE_STEPS)
    private val signatureDtw = Dtw.Scratch(MAX_SIGNATURE_STEPS)

    private var spareValid = false
    private var spareValue = 0.0
    private var suspectVarianceM2 = 0.0
    private var calmSeconds = 0.0
    private var dormantOdoM = 0.0
    private var bankedN = PARTICLE_COUNT

    /** Active particle count in `MIN_PARTICLE_COUNT..MAX_PARTICLE_COUNT` (W18-03). */
    var activeN: Int = PARTICLE_COUNT
        private set

    /** Distinct stride-scale values across active particles (test diagnostic). */
    internal fun distinctStrideCount(): Int = kA.take(activeN).distinct().size

    /** Posterior mean stride scale `k` across active particles (W17-02). */
    var meanK: Double = kPrior
        private set

    /** Posterior mean along-route position, metres. */
    var meanS: Double = 0.0
        private set

    /** Posterior σ (weighted std), metres. */
    var sigma: Double = 0.0
        private set

    /** Effective sample size after the last update. */
    var effectiveN: Double = PARTICLE_COUNT.toDouble()
        private set

    /** Heading observations applied (non-zero quality) since construction. */
    var headingObservationCount: Int = 0
        private set

    /** Systematic resamples performed since construction. */
    var resampleCount: Int = 0
        private set

    init {
        reset(0.0)
    }

    /** (Re)spreads particles around route position [startS] at default [PARTICLE_COUNT]. */
    fun reset(startS: Double) {
        spareValid = false
        suspectVarianceM2 = 0.0
        calmSeconds = 0.0
        dormantOdoM = 0.0
        activeN = PARTICLE_COUNT
        bankedN = PARTICLE_COUNT
        resizeRandom = Random(initialSeed xor RESIZE_SEED_SALT)
        val total = geometry.totalLengthM
        for (i in 0 until activeN) {
            sA[i] = (startS + gauss() * INIT_S_SD).coerceIn(0.0, total)
            kA[i] = gauss() * K_PRIOR_SD + kPrior
            bA[i] = gauss() * B_PRIOR_SD_RAD
            wA[i] = 1.0 / activeN
        }
        updateStats()
    }

    /**
     * Evaluates the adaptive particle budget (plan W18-03):
     * - Shrinks to [MIN_PARTICLE_COUNT] (96) by systematic subsampling after
     *   `sigma < 1.0 m` and `quality01 >= 0.7` continuously for `10 s`.
     * - Grows on relock (`inRelock == true`) or `sigma > 3.0 m` by jittered
     *   resampling up to [MAX_PARTICLE_COUNT] (512).
     */
    fun stepAdaptiveBudget(dtSec: Double, quality01: Double, inRelock: Boolean = false) {
        if (!dtSec.isFinite() || dtSec < 0.0) return
        if (inRelock || sigma > GROW_SIGMA_M) {
            calmSeconds = 0.0
            if (activeN < MAX_PARTICLE_COUNT) resizeParticles(MAX_PARTICLE_COUNT)
            return
        }
        if (sigma >= SHRINK_SIGMA_M && activeN < PARTICLE_COUNT) {
            calmSeconds = 0.0
            resizeParticles(PARTICLE_COUNT)
            return
        }
        if (sigma < SHRINK_SIGMA_M && quality01.isFinite() && quality01 >= SHRINK_MIN_QUALITY) {
            calmSeconds += dtSec
            if (calmSeconds >= SHRINK_HOLD_SEC && activeN > MIN_PARTICLE_COUNT) {
                resizeParticles(MIN_PARTICLE_COUNT)
            }
        } else {
            calmSeconds = 0.0
        }
    }

    /**
     * Resizes [activeN] to [targetN] (clamped to `96..512`, allocation-free):
     * - Shrinking subsamples `0 until target` and moment-matches to the
     *   pre-shrink posterior mean and σ while banking `target until oldN`.
     * - Growing wakes banked particles (advancing them by [dormantOdoM]) and
     *   splits particles antithetically (`±dz`, half weight each) up to 512 so
     *   posterior mean, weights, and σ stay continuous.
     */
    fun resizeParticles(targetN: Int) {
        val target = targetN.coerceIn(MIN_PARTICLE_COUNT, MAX_PARTICLE_COUNT)
        if (target == activeN) return
        val oldN = activeN
        val total = geometry.totalLengthM
        val preMeanS = meanS
        val preMeanK = meanK
        var preVarS = 0.0
        var preVarK = 0.0
        for (i in 0 until oldN) {
            val ds = sA[i] - preMeanS
            val dk = kA[i] - preMeanK
            preVarS += wA[i] * ds * ds
            preVarK += wA[i] * dk * dk
        }
        val preStdS = sqrt(preVarS)
        val preStdK = sqrt(preVarK)

        if (target < oldN) {
            bankedN = maxOf(bankedN, oldN)
            dormantOdoM = 0.0
            var wSum = 0.0
            for (i in 0 until target) wSum += wA[i]
            if (wSum <= 0.0 || !wSum.isFinite()) {
                for (i in 0 until target) wA[i] = 1.0 / target
            } else {
                for (i in 0 until target) wA[i] /= wSum
            }
            var subMeanS = 0.0
            for (i in 0 until target) subMeanS += wA[i] * sA[i]
            val shiftS = preMeanS - subMeanS
            for (i in 0 until target) sA[i] = (sA[i] + shiftS).coerceIn(0.0, total)
            activeN = target
            updateStats()
            return
        }

        val wakeEnd = minOf(target, bankedN)
        if (wakeEnd > oldN) {
            for (i in oldN until wakeEnd) {
                if (dormantOdoM > 0.0) {
                    val z = (resizeRandom.nextDouble() - 0.5) * 2.0
                    sA[i] = (sA[i] + kA[i] * dormantOdoM + z * S_PROCESS_REL * dormantOdoM)
                        .coerceIn(0.0, total)
                }
            }
            var wokeMeanS = 0.0
            for (i in oldN until wakeEnd) wokeMeanS += sA[i]
            wokeMeanS /= (wakeEnd - oldN)
            val shiftS = preMeanS - wokeMeanS
            for (i in oldN until wakeEnd) sA[i] = (sA[i] + shiftS).coerceIn(0.0, total)
            val activeShare = oldN.toDouble() / wakeEnd
            val wokeWeight = (1.0 - activeShare) / (wakeEnd - oldN)
            for (i in 0 until oldN) wA[i] *= activeShare
            for (i in oldN until wakeEnd) wA[i] = wokeWeight
        }
        dormantOdoM = 0.0

        val sourceN = maxOf(oldN, wakeEnd)
        if (target > sourceN) {
            // Antithetic split for additional particles so mean and weights are preserved.
            for (i in sourceN until target) {
                val j = i - sourceN
                val z1 = (resizeRandom.nextDouble() - 0.5) * 2.0 * GROW_S_JITTER_M
                val z2 = (resizeRandom.nextDouble() - 0.5) * 2.0 * K_JITTER_SD
                val z3 = (resizeRandom.nextDouble() - 0.5) * 2.0 * B_JITTER_SD_RAD
                val baseS = sA[j]
                val baseK = kA[j]
                val baseB = bA[j]
                sA[j] = (baseS - z1).coerceIn(0.0, total)
                sA[i] = (baseS + z1).coerceIn(0.0, total)
                kA[j] = (baseK - z2).coerceIn(STRIDE_K_MIN, STRIDE_K_MAX)
                kA[i] = (baseK + z2).coerceIn(STRIDE_K_MIN, STRIDE_K_MAX)
                bA[j] = wrapPi(baseB - z3)
                bA[i] = wrapPi(baseB + z3)
                val halfW = wA[j] * 0.5
                wA[j] = halfW
                wA[i] = halfW
            }
        }
        var normSum = 0.0
        for (i in 0 until target) normSum += wA[i]
        if (normSum > 0.0 && normSum.isFinite()) {
            for (i in 0 until target) wA[i] /= normSum
        } else {
            for (i in 0 until target) wA[i] = 1.0 / target
        }
        activeN = target
        bankedN = maxOf(bankedN, target)
        updateStats()
    }

    /** Predict per odometer delta: `s += k·L + N(0, 0.05·L)`; `b` random-walks. */
    fun predict(deltaM: Double) {
        if (!deltaM.isFinite() || deltaM <= 0.0) return
        val total = geometry.totalLengthM
        val n = activeN
        if (n < bankedN) dormantOdoM += deltaM
        val baseCount = minOf(n, PARTICLE_COUNT)
        for (i in 0 until baseCount) {
            sA[i] = (sA[i] + kA[i] * deltaM + gauss() * S_PROCESS_REL * deltaM)
                .coerceIn(0.0, total)
            bA[i] += gauss() * B_RW_RAD
        }
        for (i in baseCount until n) {
            val z1 = (resizeRandom.nextDouble() - 0.5) * 2.0
            val z2 = (resizeRandom.nextDouble() - 0.5) * 2.0
            sA[i] = (sA[i] + kA[i] * deltaM + z1 * S_PROCESS_REL * deltaM)
                .coerceIn(0.0, total)
            bA[i] += z2 * B_RW_RAD
        }
        // Fixed-capacity RNG burn: resizes change [activeN], so without this
        // the seeded stream desyncs between filters of different sizes
        // (s-process + b-walk draw per particle → 2 per slot).
        for (i in baseCount until PARTICLE_COUNT) {
            gauss()
            gauss()
        }
        updateStats()
    }

    /** Widens posterior σ in quadrature by [extraSigmaM] without shifting [meanS] (W16-04). */
    fun inflateUncertainty(extraSigmaM: Double) {
        if (!extraSigmaM.isFinite() || extraSigmaM <= 0.0) return
        suspectVarianceM2 += extraSigmaM * extraSigmaM
        updateStats()
    }

    /** Applies one observation (single, never a list — no allocation). */
    fun observe(obs: Observation) {
        when (obs) {
            is Observation.Heading -> observeHeading(obs.yawPlusOffsetRad, obs.quality01)
            is Observation.Turn -> observeTurn(obs.dir)
            is Observation.Anchor -> anchorAt(obs.nodeS)
            is Observation.Level -> observeLevel(obs.nodeS)
            is Observation.Magnetic -> observeMagnetic(obs.s)
            is Observation.Fingerprint -> observeFingerprint(obs.nodeS, obs.similarity01)
            is Observation.TurnSignature -> observeTurnSignature(obs.deltasRad, obs.stepM)
            is Observation.VisualSpeed -> observeVisualSpeed(obs)
            is Observation.ViewFix -> observeViewFix(obs)
        }
    }

    private fun observeVisualSpeed(obs: Observation.VisualSpeed) {
        if (!VisualSpeedEstimator.applyToParticles(obs, kA, wA, activeN)) return
        normalizeAndMaybeResample()
    }

    private fun observeViewFix(obs: Observation.ViewFix) {
        if (!ViewFixMatcher.applyToParticles(obs, sA, bA, wA, activeN)) return
        normalizeAndMaybeResample()
    }

    internal fun applyStrideCorrection(deltaSM: Double) {
        if (!deltaSM.isFinite() || deltaSM == 0.0) return
        val total = geometry.totalLengthM
        for (i in 0 until activeN) {
            sA[i] = (sA[i] + deltaSM).coerceIn(0.0, total)
        }
        updateStats()
    }

    /** Heading likelihood: von Mises on `(yaw + yawOffset + b − routeHeading(s))`, capped by `legSigmaRad`. */
    fun observeHeading(yawPlusOffsetRad: Double, quality01: Double) {
        val baseKappa = KAPPA_MAX * quality01.coerceIn(0.0, 1.0)
        if (baseKappa <= 0.0) return
        headingObservationCount++
        val n = activeN
        for (i in 0 until n) {
            val legSigma = geometry.legSigmaAt(sA[i])
            val kappa = if (legSigma.isFinite() && legSigma > 0.0) {
                minOf(baseKappa, 1.0 / (legSigma * legSigma + 1.0 / KAPPA_MAX))
            } else {
                baseKappa
            }
            val err = wrapPi(yawPlusOffsetRad + bA[i] - geometry.headingAt(sA[i]))
            wA[i] *= exp(kappa * (cos(err) - 1.0))
        }
        normalizeAndMaybeResample()
    }

    /** Turn-event likelihood: Gaussian (σ [TURN_SIGMA_M]) on distance to nearest matching turn. */
    fun observeTurn(dir: TurnDir) {
        val n = activeN
        for (i in 0 until n) {
            val nodeS = geometry.nearestTurnS(sA[i], dir)
            val like = if (nodeS.isNaN()) {
                TURN_FLOOR
            } else {
                val d = sA[i] - nodeS
                exp(-d * d / (2.0 * TURN_SIGMA_M * TURN_SIGMA_M)) + TURN_FLOOR
            }
            wA[i] *= like
        }
        normalizeAndMaybeResample()
    }

    /** Lost → relock uniform re-spread across the route (W12-06). */
    fun respreadUniform() {
        val total = geometry.totalLengthM
        spareValid = false
        val n = activeN
        for (i in 0 until n) {
            sA[i] = (random.nextDouble() * total).coerceIn(0.0, total)
            wA[i] = 1.0 / n
        }
        updateStats()
    }

    /** Turn-signature DTW likelihood (W12-06). */
    fun observeTurnSignature(deltasRad: DoubleArray, stepM: Double) {
        val len = deltasRad.size
        if (len == 0 || len > MAX_SIGNATURE_STEPS || stepM <= 0.0) return
        val n = activeN
        for (i in 0 until n) {
            var prev = geometry.headingAt(sA[i] - len * stepM)
            for (j in 0 until len) {
                val next = geometry.headingAt(sA[i] - (len - j - 1) * stepM)
                sigExp[j] = wrapPi(next - prev)
                prev = next
            }
            val cost = Dtw.distance(len, len, DTW_BAND_STEPS, signatureDtw) { row, column ->
                val error = wrapPi(deltasRad[row] - sigExp[column])
                error * error
            }
            wA[i] *= exp(-cost / (2.0 * len * RELOCK_SIGMA_RAD * RELOCK_SIGMA_RAD)) + TURN_FLOOR
        }
        normalizeAndMaybeResample()
    }

    /** Landmark confirm: hard anchor in `nodeS ± ANCHOR_HALF_M`. */
    fun anchorAt(nodeS: Double) {
        val total = geometry.totalLengthM
        suspectVarianceM2 = 0.0
        val n = activeN
        for (i in 0 until n) {
            sA[i] = (nodeS + (random.nextDouble() * 2.0 - 1.0) * ANCHOR_HALF_M).coerceIn(0.0, total)
            wA[i] = 1.0 / n
        }
        updateStats()
    }

    fun observeLevel(nodeS: Double) = anchorAt(nodeS)

    /** Magnetic-signature soft Gaussian fix (W12-04). */
    fun observeMagnetic(s: Double) {
        if (!s.isFinite()) return
        val n = activeN
        for (i in 0 until n) {
            val d = sA[i] - s
            wA[i] *= exp(-d * d / (2.0 * MAG_SIGMA_M * MAG_SIGMA_M)) + MAG_FLOOR
        }
        normalizeAndMaybeResample()
    }

    /** Fingerprint soft match (W12-05). */
    fun observeFingerprint(nodeS: Double, similarity01: Double) {
        val sim = similarity01.coerceIn(0.0, 1.0)
        if (sim <= 0.0) return
        val n = activeN
        for (i in 0 until n) {
            val d = sA[i] - nodeS
            val like = FINGERPRINT_FLOOR +
                (1.0 - FINGERPRINT_FLOOR) * sim *
                exp(-d * d / (2.0 * FINGERPRINT_SIGMA_M * FINGERPRINT_SIGMA_M))
            wA[i] *= like
        }
        normalizeAndMaybeResample()
    }

    private fun normalizeAndMaybeResample() {
        val n = activeN
        var sum = 0.0
        for (i in 0 until n) sum += wA[i]
        if (sum <= 0.0 || sum.isNaN()) {
            for (i in 0 until n) wA[i] = 1.0 / n
            updateStats()
            return
        }
        var sumSq = 0.0
        for (i in 0 until n) {
            wA[i] /= sum
            sumSq += wA[i] * wA[i]
        }
        updateStats(sumSq)
        if (effectiveN < n / 2.0) systematicResample()
    }

    private fun updateStats() {
        val n = activeN
        var sumSq = 0.0
        for (i in 0 until n) sumSq += wA[i] * wA[i]
        updateStats(sumSq)
    }

    private fun updateStats(sumSq: Double) {
        val n = activeN
        var mean = 0.0
        var kMean = 0.0
        for (i in 0 until n) {
            mean += wA[i] * sA[i]
            kMean += wA[i] * kA[i]
        }
        var variance = 0.0
        for (i in 0 until n) {
            val d = sA[i] - mean
            variance += wA[i] * d * d
        }
        meanS = mean
        meanK = kMean
        sigma = sqrt(variance + suspectVarianceM2)
        effectiveN = if (sumSq > 0.0) 1.0 / sumSq else n.toDouble()
    }

    private fun systematicResample() {
        val n = activeN
        cumA[0] = wA[0]
        for (i in 1 until n) cumA[i] = cumA[i - 1] + wA[i]
        var u = random.nextDouble() / n
        val step = 1.0 / n
        var j = 0
        for (i in 0 until n) {
            while (j < n - 1 && u > cumA[j]) j++
            tmpS[i] = sA[j]
            tmpK[i] = kA[j]
            tmpB[i] = bA[j]
            u += step
        }
        for (i in 0 until n) {
            sA[i] = tmpS[i]
            kA[i] = tmpK[i] + gauss() * K_JITTER_SD
            bA[i] = tmpB[i] + gauss() * B_JITTER_SD_RAD
            wA[i] = 1.0 / n
        }
        resampleCount++
        updateStats()
    }

    private fun gauss(): Double {
        if (spareValid) {
            spareValid = false
            return spareValue
        }
        var u1 = random.nextDouble()
        while (u1 <= 0.0) u1 = random.nextDouble()
        val u2 = random.nextDouble()
        val r = sqrt(-2.0 * ln(u1))
        val theta = 2.0 * PI * u2
        spareValue = r * sin(theta)
        spareValid = true
        return r * cos(theta)
    }

    companion object {
        const val MIN_PARTICLE_COUNT = 96
        const val PARTICLE_COUNT = 256
        const val MAX_PARTICLE_COUNT = 512

        const val SHRINK_SIGMA_M = 1.0
        const val GROW_FROM_MIN_SIGMA_M = 1.5
        const val SHRINK_MIN_QUALITY = 0.7
        const val SHRINK_HOLD_SEC = 10.0
        const val GROW_SIGMA_M = 3.0
        private const val GROW_S_JITTER_M = 0.08

        const val DEFAULT_SEED = 0xEC4012L
        private const val RESIZE_SEED_SALT = 0x517CC1B727220A95L
        const val K_PRIOR_SD = 0.08
        val B_PRIOR_SD_RAD = 5.0 * PI / 180.0
        const val INIT_S_SD = 0.5
        const val S_PROCESS_REL = 0.05
        val B_RW_RAD = 0.3 * PI / 180.0
        const val K_JITTER_SD = 0.005
        val B_JITTER_SD_RAD = 0.2 * PI / 180.0
        const val KAPPA_MAX = 8.0
        const val TURN_SIGMA_M = 2.0
        const val TURN_FLOOR = 1e-4
        const val RELOCK_SIGMA_RAD = 0.6
        const val DTW_BAND_STEPS = 1
        const val MAX_SIGNATURE_STEPS = 8
        const val ANCHOR_HALF_M = 1.0
        const val MAG_SIGMA_M = 2.0
        const val MAG_FLOOR = 0.2
        const val FINGERPRINT_FLOOR = 0.5
        const val FINGERPRINT_SIGMA_M = 6.0
        const val STRIDE_K_MIN = 0.15
        const val STRIDE_K_MAX = 1.5
        private const val PI = kotlin.math.PI
    }
}
