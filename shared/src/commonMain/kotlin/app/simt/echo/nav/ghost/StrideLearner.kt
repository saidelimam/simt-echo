package app.simt.echo.nav.ghost

import app.simt.echo.capture.STEP_K_MAX
import app.simt.echo.capture.STEP_K_MIN
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Self-calibrating stride from anchored legs (plan W12-03).
 *
 * Each anchored leg yields `r = leg.lengthM / follower odometer Δ` — the
 * *relative* gait between route creator and follower, which is exactly
 * what navigation needs. The posterior over K is log-normal, conjugate in
 * log space: with prior `(mu, variance)` and observation `y = ln(r)` of
 * variance `OBS_VAR_UNIT / legLengthM` (variance ∝ 1/leg length, so a
 * long leg moves the posterior more than a short one), the update is the
 * precision-weighted Gaussian step. [meanK] is clamped to the W4-02
 * plausible band ([STEP_K_MIN]..[STEP_K_MAX]); persistence is per device
 * via the additive [app.simt.echo.model.EchoConfig] keys, and the
 * [GhostFilter] `k` prior is seeded from [meanK] at construction.
 *
 * Pure and deterministic: no randomness, no platform calls, no HTTP.
 */
class StrideLearner private constructor(
    private var mu: Double,
    private var variance: Double,
    legs: Int,
) {
    constructor(
        kPrior: Double = 1.0,
        priorLegs: Int = 0,
    ) : this(
        mu = ln(kPrior.coerceIn(STEP_K_MIN, STEP_K_MAX)) -
            (PRIOR_VAR / (1 + priorLegs.coerceAtLeast(0))) / 2.0,
        variance = PRIOR_VAR / (1 + priorLegs.coerceAtLeast(0)),
        legs = priorLegs.coerceAtLeast(0),
    )

    /** Anchored legs folded in so far (prior legs + observations). */
    var legs: Int = legs
        private set

    /** Posterior mean K, clamped to the plausible band. */
    val meanK: Double
        get() = exp(mu + variance / 2.0).coerceIn(STEP_K_MIN, STEP_K_MAX)

    /**
     * Folds one anchored leg in. Non-positive or non-finite inputs carry
     * no information and are ignored (legs count unchanged) — a zero
     * odometer delta is a division-by-zero, never an observation. So is an
     * outlier: `|ln r| >` [OUTLIER_LN_R] (r outside ≈ 0.6..1.65) is a
     * mis-anchored or partially walked leg, not a gait, and a long one
     * would otherwise drag the posterior to the clamp.
     */
    fun observe(legLengthM: Double, odoDeltaM: Double) {
        if (!legLengthM.isFinite() || !odoDeltaM.isFinite()) return
        if (legLengthM <= 0.0 || odoDeltaM <= 0.0) return
        val r = legLengthM / odoDeltaM
        if (!r.isFinite() || r <= 0.0 || abs(ln(r)) > OUTLIER_LN_R) return
        val obsVar = OBS_VAR_UNIT / legLengthM
        val priorPrec = 1.0 / variance
        val obsPrec = 1.0 / obsVar
        val postVar = 1.0 / (priorPrec + obsPrec)
        mu = postVar * (priorPrec * mu + obsPrec * ln(r))
        variance = postVar
        legs += 1
    }

    /**
     * Weak per-step visual speed update (W17-02): folds `r = visualMps / nominalMps`
     * into the log-normal posterior without incrementing the anchored [legs] count.
     */
    fun observeVisualSpeed(nominalMps: Double, visualMps: Double, sigmaMps: Double) {
        if (!nominalMps.isFinite() || !visualMps.isFinite() || !sigmaMps.isFinite()) return
        if (nominalMps <= 0.0 || visualMps <= 0.0 || sigmaMps <= 0.0) return
        val r = visualMps / nominalMps
        if (!r.isFinite() || r <= 0.0 || abs(ln(r)) > OUTLIER_LN_R) return
        val relSigma = (sigmaMps / visualMps).coerceAtLeast(0.08)
        val obsVar = maxOf(WEAK_VISUAL_OBS_VAR_FLOOR, relSigma * relSigma * 4.0)
        val priorPrec = 1.0 / variance
        val obsPrec = 1.0 / obsVar
        val postVar = 1.0 / (priorPrec + obsPrec)
        mu = postVar * (priorPrec * mu + obsPrec * ln(r))
        variance = postVar
    }

    companion object {
        /** Prior log-space variance: sd ≈ 8 %, the Ghost k-prior spread. */
        const val PRIOR_VAR = 0.08 * 0.08

        /** Observation variance for a 1 m leg; scales as 1/leg length. */
        const val OBS_VAR_UNIT = 0.04

        /** Minimum variance for a single weak visual-speed observation (W17-02). */
        const val WEAK_VISUAL_OBS_VAR_FLOOR = 0.08

        /** Outlier gate on `|ln r|`: observations beyond it are rejected. */
        const val OUTLIER_LN_R = 0.5

        /**
         * Restores a persisted posterior: the stored mean (minus the
         * log-normal bias, so [meanK] round-trips exactly) plus its leg
         * count, which re-tightens the variance. Null or implausible K
         * restores the unlearned prior.
         */
        fun fromStored(k: Double?, storedLegs: Int): StrideLearner {
            val legs = storedLegs.coerceAtLeast(0)
            val variance = PRIOR_VAR / (1 + legs)
            val clamped = if (k != null && k.isFinite()) {
                k.coerceIn(STEP_K_MIN, STEP_K_MAX)
            } else {
                1.0
            }
            return StrideLearner(ln(clamped) - variance / 2.0, variance, legs)
        }
    }
}
