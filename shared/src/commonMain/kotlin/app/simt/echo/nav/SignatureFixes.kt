package app.simt.echo.nav

import app.simt.echo.capture.MagProfiler
import app.simt.echo.capture.visual.VisualEgo
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.MagProfile
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.nav.ghost.DepthSignatureMatch
import app.simt.echo.nav.ghost.DepthSignatureTracker
import app.simt.echo.nav.ghost.FixGate
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.MagneticMatch
import app.simt.echo.nav.ghost.Observation
import app.simt.echo.platform.MotionUpdate

/**
 * Auxiliary signature fix sources (W12-04 magnetic, W17-04 depth): live
 * profiles matched against the route's stored ones, at most once per
 * 0.5 m odometer bucket, applying soft filter observations — never a
 * phase change, never an anchor event — gated by [FixGate] against
 * re-matching the same window. Depth also feeds the relock search as a
 * fourth source after turn signature, fingerprints and magnetic.
 *
 * The [Navigator] owns the filter and the phases; this owns the
 * profilers, the match buckets and the fix gates.
 */
internal class SignatureFixes(
    private val route: EchoRoute,
    private val relock: Relock,
) {

    /**
     * Live magnetic signature (W12-04): the last 10 m observed, bucketed
     * from motion updates carrying magnetometer data. Matched against the
     * route's stored mag profile at most once per 0.5 m bucket; silent
     * when the route has no profile or the device has no magnetometer.
     */
    private val magProfiler = MagProfiler()
    private var lastMagMatchBucket: Int = -1
    private val magFixGate = FixGate()

    /**
     * Live depth-profile signature (W17-04): accumulates (ρ, s_odometer)
     * buckets from visual ego frames and matches against the route's
     * ParallaxProfile by DTW (including as a fourth Relock source).
     */
    private val depthTracker = DepthSignatureTracker()
    private var lastDepthMatchBucket: Int = -1
    private val depthFixGate = FixGate()

    /** Alignment reset ([Navigator.onAligned]): the next alignment re-learns the depth window. */
    fun reset() {
        depthTracker.reset()
        lastDepthMatchBucket = -1
    }

    /**
     * Magnetic-signature fix (W12-04): feeds the live mag samples into the
     * profiler and, at most once per 0.5 m bucket, matches the last 10 m
     * against the route's stored mag profile around the Ghost belief. A
     * passing match is a soft filter observation — never a phase change,
     * never an anchor event — and only when [magFixGate] finds it new
     * information (consecutive buckets re-match ~95 % the same window).
     * Silent on LINEAR (no filter), without a route profile, or without
     * magnetometer data.
     */
    fun onMagnetic(g: GhostFilter?, update: MotionUpdate, odoM: Double) {
        g ?: return
        val mag = update.magMag ?: return
        val vert = update.magVert ?: return
        val profile = route.perception?.magProfile ?: return
        magProfiler.onSample(odoM, mag, vert)
        val bucket = (odoM / MagProfile.MAG_STEP_M).toInt()
        if (bucket == lastMagMatchBucket) return
        lastMagMatchBucket = bucket
        val (obsMag, obsVert) = magProfiler.lastWindow(MagneticMatch.QUERY_M, odoM) ?: return
        MagneticMatch.match(profile, obsMag, obsVert, g.meanS, g.sigma)?.let {
            if (magFixGate.accept(odoM, it.estimatedS)) g.observe(Observation.Magnetic(it.estimatedS))
        }
    }

    /**
     * Depth-profile signature update (W17-04): accumulates `ρ = expansionPerSec / nominalMps`
     * into 0.5 m odometer buckets and matches the last window against the
     * route's [ParallaxProfile]. Feeds Relock as a fourth source after
     * turn signature, fingerprints and magnetic. True when an observation
     * was applied.
     */
    fun onDepth(
        g: GhostFilter?,
        ego: VisualEgo,
        nominalMps: Double,
        odoM: Double,
        relocking: Boolean,
    ): Boolean {
        g ?: return false
        val parallax = route.perception?.parallax ?: return false
        if (!ego.confidence01.isFinite() || ego.confidence01 < 0.5) return false
        if (!ego.expansionPerSec.isFinite() || ego.expansionPerSec <= 0.0) return false
        val speed = if (nominalMps.isFinite() && nominalMps > 0.15) nominalMps else 1.0
        val rho = ego.expansionPerSec / speed
        depthTracker.onSample(odoM, rho)
        val bucket = (odoM / ParallaxProfile.STEP_M).toInt()
        if (bucket == lastDepthMatchBucket) return false
        lastDepthMatchBucket = bucket
        val windowM = if (relocking) {
            (odoM - relock.startOdoM).coerceIn(DepthSignatureMatch.MIN_RELOCK_QUERY_M, DepthSignatureMatch.QUERY_M)
        } else {
            DepthSignatureMatch.QUERY_M
        }
        val obsRho = depthTracker.lastWindow(windowM, odoM) ?: return false
        val fix = DepthSignatureMatch.match(parallax, obsRho, g.meanS, g.sigma) ?: return false
        if (relocking || depthFixGate.accept(odoM, fix.estimatedS)) {
            g.observe(Observation.Magnetic(fix.estimatedS))
            return true
        }
        return false
    }
}
