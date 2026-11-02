package app.simt.echo.nav.ghost

import app.simt.echo.capture.wrapPi
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.TurnDir
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Along-route particle filter — the Ghost navigator core (plan W12-02).
 *
 * Particles carry `s` (metres along the route polyline), `k` (stride
 * scale, prior N(1, 0.08)) and `b` (heading bias, prior N(0, 5°)) in
 * struct-of-arrays [DoubleArray]s. Outputs are the posterior mean [meanS]
 * and σ ([sigma], weighted std), which replace the linear σ and odometer
 * progress in the §6.4 state machine.
 *
 * Allocation audit (≤ 1 ms/update budget): every array is sized N in the
 * constructor and reused — [predict] and the three [observe] entry points
 * allocate nothing. The Gaussian sampler is scalar Box-Muller with a
 * primitive spare cache (no boxing). The only allocations in this class
 * happen in the constructor and [reset].
 *
 * Determinism: all randomness comes from one seeded [Random]; the same
 * seed and the same call sequence produce bit-identical traces.
 */
class GhostFilter(
    route: EchoRoute,
    seed: Long = DEFAULT_SEED,
    /**
     * Stride-scale prior mean (W12-03): the per-device learned K seeds
     * the `k` spread at construction; the default 1.0 is the unlearned
     * prior. Implausible values fall back to 1.0. Additive — sampling,
     * geometry and phases are untouched.
     */
    kPriorMean: Double = 1.0,
) {
    private val geometry = RouteGeometry(route)
    private var random = Random(seed)
    private val kPrior: Double =
        if (kPriorMean.isFinite() && kPriorMean in STRIDE_K_MIN..STRIDE_K_MAX) kPriorMean else 1.0

    private val sA = DoubleArray(PARTICLE_COUNT)
    private val kA = DoubleArray(PARTICLE_COUNT)
    private val bA = DoubleArray(PARTICLE_COUNT)
    private val wA = DoubleArray(PARTICLE_COUNT)
    private val cumA = DoubleArray(PARTICLE_COUNT)
    private val tmpS = DoubleArray(PARTICLE_COUNT)
    private val tmpK = DoubleArray(PARTICLE_COUNT)
    private val tmpB = DoubleArray(PARTICLE_COUNT)

    private var spareValid = false
    private var spareValue = 0.0

    /** Posterior mean along-route position, metres. */
    var meanS: Double = 0.0
        private set

    /** Posterior σ (weighted std), metres. Replaces the linear σ. */
    var sigma: Double = 0.0
        private set

    /** Effective sample size after the last update. */
    var effectiveN: Double = PARTICLE_COUNT.toDouble()
        private set

    /** Systematic resamples performed since construction. */
    var resampleCount: Int = 0
        private set

    init {
        reset(0.0)
    }

    /** (Re)spreads particles around route position [startS]. */
    fun reset(startS: Double) {
        spareValid = false
        for (i in 0 until PARTICLE_COUNT) {
            sA[i] = (startS + gauss() * INIT_S_SD).coerceIn(0.0, geometry.totalLengthM)
            kA[i] = gauss() * K_PRIOR_SD + kPrior
            bA[i] = gauss() * B_PRIOR_SD_RAD
            wA[i] = 1.0 / PARTICLE_COUNT
        }
        updateStats()
    }

    /**
     * Predict per odometer delta: s += k·L + N(0, 0.05·L); b takes a
     * 0.3°/step random walk. Non-positive deltas are a no-op. k is static
     * per particle — it is learned implicitly when turn/anchor
     * observations resample the matching-stride survivors.
     */
    fun predict(deltaM: Double) {
        if (deltaM <= 0.0) return
        val total = geometry.totalLengthM
        for (i in 0 until PARTICLE_COUNT) {
            sA[i] = (sA[i] + kA[i] * deltaM + gauss() * S_PROCESS_REL * deltaM)
                .coerceIn(0.0, total)
            bA[i] += gauss() * B_RW_RAD
        }
        updateStats()
    }

    /** Applies one observation (single, never a list — no allocation). */
    fun observe(obs: Observation) {
        when (obs) {
            is Observation.Heading -> observeHeading(obs.yawPlusOffsetRad, obs.quality01)
            is Observation.Turn -> observeTurn(obs.dir)
            is Observation.Anchor -> anchorAt(obs.nodeS)
        }
    }

    /**
     * Heading likelihood: von Mises on
     * (yaw + yawOffset + b − routeHeading(s)) with κ = [KAPPA_MAX]·q.
     * q = 0 is uniform (stillness/gyro-bias episodes add no information
     * instead of wrong information).
     */
    fun observeHeading(yawPlusOffsetRad: Double, quality01: Double) {
        val kappa = KAPPA_MAX * quality01.coerceIn(0.0, 1.0)
        if (kappa <= 0.0) return
        for (i in 0 until PARTICLE_COUNT) {
            val err = wrapPi(yawPlusOffsetRad + bA[i] - geometry.headingAt(sA[i]))
            wA[i] *= exp(kappa * (cos(err) - 1.0))
        }
        normalizeAndMaybeResample()
    }

    /**
     * Turn-event likelihood: Gaussian (σ [TURN_SIGMA_M]) on the distance
     * to the nearest node with the matching turn, [TURN_FLOOR] elsewhere
     * so a far turn reads as uninformative rather than collapsing the
     * filter to NaN.
     */
    fun observeTurn(dir: TurnDir) {
        for (i in 0 until PARTICLE_COUNT) {
            val nodeS = geometry.nearestTurnS(sA[i], dir)
            val like = if (nodeS == null) {
                TURN_FLOOR
            } else {
                val d = sA[i] - nodeS
                exp(-d * d / (2.0 * TURN_SIGMA_M * TURN_SIGMA_M)) + TURN_FLOOR
            }
            wA[i] *= like
        }
        normalizeAndMaybeResample()
    }

    /**
     * Landmark confirm: hard anchor — every particle re-spreads uniform
     * in [nodeS] ± [ANCHOR_HALF_M]. Stride scale and heading bias survive
     * (they are device properties, not position).
     */
    fun anchorAt(nodeS: Double) {
        val total = geometry.totalLengthM
        for (i in 0 until PARTICLE_COUNT) {
            sA[i] = (nodeS + (random.nextDouble() * 2.0 - 1.0) * ANCHOR_HALF_M)
                .coerceIn(0.0, total)
            wA[i] = 1.0 / PARTICLE_COUNT
        }
        updateStats()
    }

    private fun normalizeAndMaybeResample() {
        var sum = 0.0
        for (i in 0 until PARTICLE_COUNT) sum += wA[i]
        if (sum <= 0.0 || sum.isNaN()) {
            for (i in 0 until PARTICLE_COUNT) wA[i] = 1.0 / PARTICLE_COUNT
            updateStats()
            return
        }
        var sumSq = 0.0
        for (i in 0 until PARTICLE_COUNT) {
            wA[i] /= sum
            sumSq += wA[i] * wA[i]
        }
        updateStats(sumSq)
        if (effectiveN < PARTICLE_COUNT / 2.0) systematicResample()
    }

    private fun updateStats() {
        var sumSq = 0.0
        for (i in 0 until PARTICLE_COUNT) sumSq += wA[i] * wA[i]
        updateStats(sumSq)
    }

    private fun updateStats(sumSq: Double) {
        var mean = 0.0
        for (i in 0 until PARTICLE_COUNT) mean += wA[i] * sA[i]
        var variance = 0.0
        for (i in 0 until PARTICLE_COUNT) {
            val d = sA[i] - mean
            variance += wA[i] * d * d
        }
        meanS = mean
        sigma = sqrt(variance)
        effectiveN = if (sumSq > 0.0) 1.0 / sumSq else PARTICLE_COUNT.toDouble()
    }

    /** Systematic resampling into the reused scratch arrays. */
    private fun systematicResample() {
        cumA[0] = wA[0]
        for (i in 1 until PARTICLE_COUNT) cumA[i] = cumA[i - 1] + wA[i]
        var u = random.nextDouble() / PARTICLE_COUNT
        val step = 1.0 / PARTICLE_COUNT
        var j = 0
        for (i in 0 until PARTICLE_COUNT) {
            while (j < PARTICLE_COUNT - 1 && u > cumA[j]) j++
            tmpS[i] = sA[j]
            tmpK[i] = kA[j]
            tmpB[i] = bA[j]
            u += step
        }
        for (i in 0 until PARTICLE_COUNT) {
            sA[i] = tmpS[i]
            kA[i] = tmpK[i]
            bA[i] = tmpB[i]
            wA[i] = 1.0 / PARTICLE_COUNT
        }
        resampleCount++
        updateStats()
    }

    /** Scalar Box-Muller; primitive spare cache, no boxing/allocation. */
    private fun gauss(): Double {
        if (spareValid) {
            spareValid = false
            return spareValue
        }
        var u1 = random.nextDouble()
        while (u1 <= 0.0) u1 = random.nextDouble()
        val u2 = random.nextDouble()
        val r = sqrt(-2.0 * kotlin.math.ln(u1))
        val theta = 2.0 * PI * u2
        spareValue = r * kotlin.math.sin(theta)
        spareValid = true
        return r * kotlin.math.cos(theta)
    }

    companion object {
        /** Particle count (plan W12-02, ≤ 1 ms/update budget). */
        const val PARTICLE_COUNT = 256

        /** Deterministic default seed: unseeded filters still replay identically. */
        const val DEFAULT_SEED = 0xEC4012L

        /** Stride-scale prior N(1, 0.08). */
        const val K_PRIOR_SD = 0.08

        /** Heading-bias prior N(0, 5°), radians. */
        val B_PRIOR_SD_RAD = 5.0 * PI / 180.0

        /** Initial position spread at a known alignment, metres. */
        const val INIT_S_SD = 0.5

        /** Process noise per predict: N(0, 0.05·L). */
        const val S_PROCESS_REL = 0.05

        /** Heading-bias random walk per predict, 0.3° in radians. */
        val B_RW_RAD = 0.3 * PI / 180.0

        /** Von Mises κ at full heading quality (q = 1). */
        const val KAPPA_MAX = 8.0

        /** Turn-event Gaussian σ, metres. */
        const val TURN_SIGMA_M = 2.0

        /** Turn likelihood floor far from any matching node. */
        const val TURN_FLOOR = 1e-4

        /** Landmark hard-anchor half-window, metres. */
        const val ANCHOR_HALF_M = 1.0

        /** Learned-k prior band (W12-03): the W4-02 plausible K band. */
        const val STRIDE_K_MIN = 0.15
        const val STRIDE_K_MAX = 1.5

        private const val PI = kotlin.math.PI
    }
}
