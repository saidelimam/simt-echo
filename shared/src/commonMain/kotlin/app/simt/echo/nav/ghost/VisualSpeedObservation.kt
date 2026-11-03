package app.simt.echo.nav.ghost

import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.model.ParallaxProfile
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Route-conditioned visual speed estimator (plan W17-02).
 *
 * Divides the follower's measured `expansionPerSec` by the creator's stored
 * expansion-per-metre profile `ρ(s)` over `s ± (3σ + 2 m)` to recover the
 * follower's speed `v_visual = expansionPerSec / ρ(s)` in the creator's own
 * metres without metric depth calibration.
 *
 * Pure and allocation-free on the hot path: decodes `inverseDepth` and
 * `trackability` once at construction and reuses primitive scratch arrays.
 */
class VisualSpeedEstimator(
    private val profile: ParallaxProfile?,
    val liveEstimatorId: String = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
) {
    private val compatible: Boolean
    private val stepM: Double
    private val rhoBuckets: DoubleArray
    private val tauBuckets: DoubleArray
    private val scratchRhos: DoubleArray
    private val sortScratch: DoubleArray

    init {
        if (profile != null && profile.compatibleWith(liveEstimatorId) &&
            profile.inverseDepth.isNotBlank() && profile.trackability.isNotBlank()
        ) {
            val depthBytes = runCatching { Base64.decode(profile?.inverseDepth.orEmpty()) }.getOrNull()
            val trackBytes = runCatching { Base64.decode(profile?.trackability.orEmpty()) }.getOrNull()
            if (depthBytes != null && trackBytes != null && depthBytes.size == trackBytes.size && depthBytes.isNotEmpty()) {
                compatible = true
                stepM = profile.stepM
                rhoBuckets = DoubleArray(depthBytes.size) { i -> ParallaxProfiler.dequantiseRho(depthBytes[i]) }
                tauBuckets = DoubleArray(trackBytes.size) { i -> (trackBytes[i].toInt() and 0xFF) / 255.0 }
                scratchRhos = DoubleArray(depthBytes.size)
                sortScratch = DoubleArray(depthBytes.size)
            } else {
                compatible = false
                stepM = ParallaxProfile.STEP_M
                rhoBuckets = DoubleArray(0)
                tauBuckets = DoubleArray(0)
                scratchRhos = DoubleArray(0)
                sortScratch = DoubleArray(0)
            }
        } else {
            compatible = false
            stepM = ParallaxProfile.STEP_M
            rhoBuckets = DoubleArray(0)
            tauBuckets = DoubleArray(0)
            scratchRhos = DoubleArray(0)
            sortScratch = DoubleArray(0)
        }
    }

    val isCompatible: Boolean get() = compatible

    /**
     * Evaluates a follower visual ego frame at estimated route position [sM]
     * with position uncertainty [sigmaSM] and nominal step speed [nominalMps].
     * Returns [Observation.VisualSpeed] when all gates pass, or `null` when
     * incompatible, blank-walled, low-usability or stationary.
     */
    fun evaluate(
        sM: Double,
        sigmaSM: Double,
        nominalMps: Double,
        expansionPerSec: Double,
        confidence01: Double,
        usability01: Double,
    ): Observation.VisualSpeed? {
        if (!compatible || rhoBuckets.isEmpty()) return null
        if (!sM.isFinite() || !sigmaSM.isFinite() || sigmaSM < 0.0) return null
        if (!nominalMps.isFinite() || nominalMps <= MIN_NOMINAL_MPS) return null
        if (!expansionPerSec.isFinite() || expansionPerSec <= MIN_EXPANSION_PER_SEC) return null
        if (!confidence01.isFinite() || confidence01 < MIN_FOLLOWER_TAU) return null
        if (!usability01.isFinite() || usability01 < MIN_USABILITY) return null

        val halfWindowM = 3.0 * sigmaSM + WINDOW_PAD_M
        val b0 = ((sM - halfWindowM) / stepM).toInt().coerceIn(0, rhoBuckets.lastIndex)
        val b1 = ((sM + halfWindowM) / stepM).toInt().coerceIn(b0, rhoBuckets.lastIndex)

        var count = 0
        var tauSum = 0.0
        var rhoSum = 0.0
        var firstKnownBucket = -1
        var lastKnownBucket = -1

        for (b in b0..b1) {
            val rho = rhoBuckets[b]
            val tau = tauBuckets[b]
            if (rho.isFinite() && rho > 0.0 && tau >= MIN_ROUTE_TAU) {
                scratchRhos[count++] = rho
                tauSum += tau
                rhoSum += rho
                if (firstKnownBucket < 0) firstKnownBucket = b
                lastKnownBucket = b
            }
        }
        if (count == 0) return null
        val meanTau = tauSum / count
        if (meanTau < MIN_ROUTE_TAU) return null

        val medianRho = medianOf(scratchRhos, count)
        if (!medianRho.isFinite() || medianRho <= 1e-6) return null

        val visualMps = expansionPerSec / medianRho
        if (!visualMps.isFinite() || visualMps !in MIN_VISUAL_MPS..MAX_VISUAL_MPS) return null

        val meanRho = rhoSum / count
        var rhoVar = 0.0
        for (i in 0 until count) {
            val d = scratchRhos[i] - meanRho
            rhoVar += d * d
        }
        val rhoSpread = sqrt(rhoVar / count) / medianRho

        val dRhoDs = if (lastKnownBucket > firstKnownBucket) {
            val spanM = (lastKnownBucket - firstKnownBucket) * stepM
            abs(rhoBuckets[lastKnownBucket] - rhoBuckets[firstKnownBucket]) / spanM
        } else {
            0.0
        }
        val slopeRel = (dRhoDs * sigmaSM) / medianRho
        val confRel = (1.0 - confidence01.coerceIn(0.0, 1.0)) * 0.25 + BASE_REL_SIGMA
        val totalRelSigma = sqrt(confRel * confRel + rhoSpread * rhoSpread + slopeRel * slopeRel)
        val sigmaMps = (visualMps * totalRelSigma).coerceIn(MIN_SIGMA_MPS, MAX_SIGMA_MPS)

        return Observation.VisualSpeed(
            nominalMps = nominalMps,
            visualMps = visualMps,
            sigmaMps = sigmaMps,
        )
    }

    private fun medianOf(source: DoubleArray, count: Int): Double {
        for (i in 0 until count) sortScratch[i] = source[i]
        for (i in 1 until count) {
            val v = sortScratch[i]
            var hole = i
            while (hole > 0 && sortScratch[hole - 1] > v) {
                sortScratch[hole] = sortScratch[hole - 1]
                hole--
            }
            sortScratch[hole] = v
        }
        return if (count % 2 == 1) {
            sortScratch[count / 2]
        } else {
            0.5 * (sortScratch[count / 2 - 1] + sortScratch[count / 2])
        }
    }

    companion object {
        const val MIN_ROUTE_TAU = 0.40
        const val MIN_FOLLOWER_TAU = 0.40
        const val MIN_USABILITY = 0.50
        const val WINDOW_PAD_M = 2.0
        private const val MIN_NOMINAL_MPS = 0.15
        private const val MIN_EXPANSION_PER_SEC = 0.01
        private const val MIN_VISUAL_MPS = 0.20
        private const val MAX_VISUAL_MPS = 3.00
        private const val BASE_REL_SIGMA = 0.08
        private const val MIN_SIGMA_MPS = 0.06
        private const val MAX_SIGMA_MPS = 0.80
        private const val VISUAL_SPEED_FLOOR = 0.05

        /**
         * Applies a Gaussian stride-scale likelihood (`k * nominalMps` vs
         * `visualMps` with std `sigmaMps`) across particle stride scales [kA]
         * and weights [wA] for `0 until activeCount`.
         */
        internal fun applyToParticles(
            obs: Observation.VisualSpeed,
            kA: DoubleArray,
            wA: DoubleArray,
            activeCount: Int,
        ): Boolean {
            if (!obs.nominalMps.isFinite() || obs.nominalMps <= 0.0 ||
                !obs.visualMps.isFinite() || obs.visualMps <= 0.0 ||
                !obs.sigmaMps.isFinite() || obs.sigmaMps <= 0.0
            ) return false
            val invTwoVar = 1.0 / (2.0 * obs.sigmaMps * obs.sigmaMps)
            for (i in 0 until activeCount) {
                val predictedMps = kA[i] * obs.nominalMps
                val diff = predictedMps - obs.visualMps
                wA[i] *= exp(-diff * diff * invTwoVar) + VISUAL_SPEED_FLOOR
            }
            return true
        }
    }
}
