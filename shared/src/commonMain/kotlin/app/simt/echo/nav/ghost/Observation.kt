package app.simt.echo.nav.ghost

import app.simt.echo.model.TurnDir

/**
 * One filter observation (plan W12-02).
 *
 * The sealed set grows in later slices — barometer (W12-08), magnetic
 * DTW offset (W12-04), visual fingerprint match (W12-05) — without
 * changing the [GhostFilter] update seam: one observation applied per
 * call, never a list, so applying observations allocates nothing.
 */
sealed interface Observation {

    /**
     * Heading likelihood input: device yaw plus the Navigator alignment
     * offset, the route heading at the particle, and W12-01 quality q.
     * The filter maps q to a von Mises κ internally.
     */
    data class Heading(
        val yawPlusOffsetRad: Double,
        val quality01: Double,
    ) : Observation

    /** A settled turn from TurnDetector: which way the walker turned. */
    data class Turn(val dir: TurnDir) : Observation

    /** A confirmed landmark: hard anchor at the node's along-route [s]. */
    data class Anchor(val nodeS: Double) : Observation

    /**
     * A barometer-confirmed level change (W12-08): |Δh| from the
     * leg-start baseline crossed 2.2 m, so the filter anchors at the
     * LEVEL_CHANGE node's along-route [s] like a landmark confirm.
     */
    data class Level(val nodeS: Double) : Observation

    /**
     * A magnetic-signature fix (W12-04): the last 10 m observed match the
     * route's stored mag profile at along-route [s] (the walker's
     * estimated current position from [MagneticMatch]). Soft — a Gaussian
     * likelihood, never a hard anchor: steel signatures drift with doors
     * and furniture.
     */
    data class Magnetic(val s: Double) : Observation

    /**
     * A visual-fingerprint match (W12-05): cosine ≥ τ against a stored
     * print within s ± (3σ + 5 m). Low weight by construction (see
     * [GhostFilter.observeFingerprint]) — the user, or a consistent turn
     * event, confirms; never a silent auto-confirm.
     */
    data class Fingerprint(val nodeS: Double, val similarity01: Double) : Observation

    /**
     * A turn-signature window (W12-06): the observed yaw changes per
     * [stepM] metres over the last stretch walked, ending at the walker's
     * current position, fused by
     * [GhostFilter.observeTurnSignature] (DTW over the heading-change
     * sequence). Rotation-invariant, so the unknown alignment offset
     * cancels — the same single-observation seam as every other input.
     */
    data class TurnSignature(val deltasRad: DoubleArray, val stepM: Double) : Observation

    /**
     * Route-conditioned visual speed observation (W17-02): Gaussian likelihood
     * on each particle's stride scale `k` (`k * nominalMps` against `visualMps`
     * with standard deviation `sigmaMps`).
     */
    data class VisualSpeed(
        val nominalMps: Double,
        val visualMps: Double,
        val sigmaMps: Double,
    ) : Observation
}
