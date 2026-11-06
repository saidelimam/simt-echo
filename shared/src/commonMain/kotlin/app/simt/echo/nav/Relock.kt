package app.simt.echo.nav

import app.simt.echo.capture.wrapPi
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.RouteGeometry
import kotlin.math.abs

/**
 * Lost → relock search (W12-06): where the search began, the (dist, yaw,
 * tNs) trail feeding the turn-signature DTW (kept to the ≤ 10 s / 8 m
 * window), and the 1 m bucket of the last signature observation so each
 * metre feeds the filter at most once.
 *
 * The [Navigator] owns the filter and the state a lock applies to; this
 * owns the evidence — `converged` decides when σ < 3 m is a real lock,
 * `fallbackDue` when the 20 m give-up fires.
 */
internal class Relock(private val geometry: RouteGeometry) {

    private var startOdo: Double = 0.0
    private val trail = mutableListOf<RelockSample>()
    private var lastSignatureBucket: Int = -1

    /** Raw odometer where the search began (the depth query reads it mid-relock). */
    val startOdoM: Double get() = startOdo

    /** [Navigator.onLost]: a uniform re-spread begins the search at [odoM]. */
    fun begin(odoM: Double) {
        startOdo = odoM
        trail.clear()
        lastSignatureBucket = (odoM / RELOCK_SIGNATURE_STEP_M).toInt()
    }

    /** Drops the trail; the bucket resets so the next search re-feeds. */
    fun clear() {
        trail.clear()
        lastSignatureBucket = -1
    }

    /**
     * One relock motion update: extends the signature trail and, at most
     * once per metre walked, returns the turn-signature observation the
     * filter applies. Null on the metres already fed.
     */
    fun onMotion(odoM: Double, yawRad: Double, tNs: Long): DoubleArray? {
        trail += RelockSample(odoM, yawRad, tNs)
        trail.removeAll { odoM - it.d > RELOCK_WINDOW_M || tNs - it.tNs > RELOCK_WINDOW_NS }
        val bucket = (odoM / RELOCK_SIGNATURE_STEP_M).toInt()
        if (bucket == lastSignatureBucket) return null
        lastSignatureBucket = bucket
        return signature(odoM)
    }

    /**
     * σ < 3 m completes the search; a posterior crowded against either
     * route end is indistinguishable from clamp pile-up after a uniform
     * re-spread (predict pins overshooting particles at the boundary, so
     * σ collapses without evidence) — keep searching rather than announce
     * a lock the evidence cannot support; the 20 m fallback stays honest.
     */
    fun converged(sigmaM: Double, meanS: Double): Boolean {
        if (!sigmaM.isFinite() || sigmaM >= RELOCK_SIGMA_M) return false
        return meanS >= RELOCK_EDGE_M && meanS <= geometry.totalLengthM - RELOCK_EDGE_M
    }

    /** The relock gives up after 20 m walked without convergence. */
    fun fallbackDue(odoM: Double): Boolean = odoM - startOdo >= RELOCK_FALLBACK_M

    /**
     * Observed heading-change sequence ending at the walker's current
     * position: yaw deltas per [RELOCK_SIGNATURE_STEP_M] over the last up
     * to 8 whole metres walked since the search began (a sliding window,
     * never anchored to where the search started). Null until at least one
     * full metre is walked. Rotation-invariant (yaw deltas), so the
     * unknown alignment offset cancels.
     */
    private fun signature(distM: Double): DoubleArray? {
        val walked = distM - startOdo
        val steps = walked.toInt().coerceAtMost(GhostFilter.MAX_SIGNATURE_STEPS)
        if (steps < 1) return null
        val base = distM - steps * RELOCK_SIGNATURE_STEP_M
        return DoubleArray(steps) { k ->
            wrapPi(yawAt(base + (k + 1) * RELOCK_SIGNATURE_STEP_M) - yawAt(base + k * RELOCK_SIGNATURE_STEP_M))
        }
    }

    /** Yaw of the nearest relock-trail sample to along-walk [d]. */
    private fun yawAt(d: Double): Double {
        var best = trail.lastOrNull()?.yaw ?: 0.0
        var bestDist = Double.MAX_VALUE
        for (sample in trail) {
            val dd = abs(sample.d - d)
            if (dd < bestDist) {
                bestDist = dd
                best = sample.yaw
            }
        }
        return best
    }

    companion object {
        /** Relock lock criterion: σ below 3 m announces "Found you". */
        const val RELOCK_SIGMA_M = 3.0

        /**
         * Relock end margin: a lock crowded within 3 m of either route end
         * reads as clamp pile-up, not evidence — the search continues.
         */
        const val RELOCK_EDGE_M = 3.0

        /** Relock gives up after 20 m walked without convergence. */
        const val RELOCK_FALLBACK_M = 20.0

        /** Turn-signature window: the next ≤ 8 m of heading changes. */
        const val RELOCK_WINDOW_M = 8.0

        /** Turn-signature window: the next ≤ 10 s of heading changes, ns. */
        const val RELOCK_WINDOW_NS = 10_000_000_000L

        /** Turn-signature resolution: one heading change per metre. */
        const val RELOCK_SIGNATURE_STEP_M = 1.0
    }
}

/**
 * One relock-trail sample (W12-06): where the walker was, which way
 * they faced, and when — the signature window reads yaw deltas off
 * these, pruned to ≤ 10 s / 8 m.
 */
internal data class RelockSample(val d: Double, val yaw: Double, val tNs: Long)
