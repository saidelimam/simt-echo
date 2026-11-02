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
}
