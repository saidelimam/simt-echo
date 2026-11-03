package app.simt.echo.capture.visual

import app.simt.echo.capture.HeadingQuality
import app.simt.echo.capture.circularMean
import app.simt.echo.capture.wrapPi
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RoutePerception
import app.simt.echo.model.withPerception
import app.simt.echo.nav.ghost.ViewFixMatcher
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline Route Polish at Review (plan W18-01).
 *
 * Uses the whole recorded trace forward and backward to:
 * 1. Remove per-leg heading drift using visual yaw residuals (`yawResidualRadPerSec`)
 *    with the W12-01 stillness drift estimator as prior, keeping leg lengths in
 *    the creator's PDR metres untouched.
 * 2. Compute and store per-leg heading uncertainty (`legSigmaRad` in
 *    [ParallaxProfile]), which the follower [app.simt.echo.nav.ghost.GhostFilter]
 *    uses to cap its heading κ so the route is not treated as exact (H6).
 * 3. Detect round-trip loop closure when start and destination view strips
 *    match (`ViewFixMatcher`) and the endpoints are near each other,
 *    distributing the closure error across legs proportionally to
 *    `legLengthM * legSigmaRad^2`.
 * 4. Ensure idempotence: polishing an already-polished route (`legSigmaRad`
 *    already populated) returns the route unchanged.
 *
 * Note on the optional soft Manhattan prior (§8 W18-01): evaluated against
 * non-orthogonal routes (e.g., 45° diagonal legs) where a 90°-grid pull biases
 * true diagonal headings; therefore omitted so non-orthogonal scenarios never
 * regress.
 */
object RoutePolish {

    /** Visual yaw-residual sample recorded at odometer distance [distanceM] and time [tSec]. */
    data class VisualYawSample(
        val distanceM: Double,
        val tSec: Double,
        val yawResidualRadPerSec: Double,
        val yawSigmaRadPerSec: Double = 0.01,
        val confidence01: Double = 1.0,
    )

    /** Result of [polish]: the updated [EchoRoute] and measured factual summary. */
    data class Result(
        val route: EchoRoute,
        val totalDriftRemovedDeg: Double,
        val loopClosed: Boolean,
        val summaryCopy: String?,
    )

    /**
     * Polishes [route] offline using [visualSamples] and optional
     * [stillnessDriftPriorRadPerSec]. Idempotent: if [route] already carries
     * `perception.parallax.legSigmaRad` matching `legs.size`, returns [route]
     * untouched.
     */
    fun polish(
        route: EchoRoute,
        visualSamples: List<VisualYawSample> = emptyList(),
        stillnessDriftPriorRadPerSec: Double = 0.0,
        walkingSpeedMps: Double = DEFAULT_WALKING_SPEED_MPS,
    ): Result {
        val existingParallax = route.perception?.parallax
        if (route.legs.isEmpty()) {
            return Result(route, 0.0, false, null)
        }
        if (existingParallax != null && existingParallax.legSigmaRad.size == route.legs.size) {
            return Result(route, 0.0, false, null)
        }

        val nodeDist = route.nodes.associate { it.id to it.atDistanceM }
        val legCount = route.legs.size
        val legStarts = DoubleArray(legCount) { i -> nodeDist[route.legs[i].fromNode] ?: 0.0 }
        val legEnds = DoubleArray(legCount) { i ->
            nodeDist[route.legs[i].toNode] ?: (legStarts[i] + route.legs[i].lengthM)
        }
        val rawLegHeadingsRad = DoubleArray(legCount) { i -> route.legs[i].headingRelDeg * PI / 180.0 }

        val priorRate = stillnessDriftPriorRadPerSec.coerceIn(
            -HeadingQuality.MAX_DRIFT_RAD_PER_SEC,
            HeadingQuality.MAX_DRIFT_RAD_PER_SEC,
        )
        val speed = if (walkingSpeedMps.isFinite() && walkingSpeedMps > 0.2) walkingSpeedMps else DEFAULT_WALKING_SPEED_MPS

        // Forward-backward smoothed drift rate per leg (rad/s) and per-leg heading sigma (rad).
        val legDriftRates = DoubleArray(legCount)
        val legSigmas = DoubleArray(legCount)

        for (i in 0 until legCount) {
            val s0 = legStarts[i]
            val s1 = legEnds[i]
            val inLeg = visualSamples.filter {
                it.distanceM in s0..s1 &&
                    it.confidence01 >= HeadingQuality.MIN_VISUAL_CONFIDENCE &&
                    it.yawResidualRadPerSec.isFinite() &&
                    it.yawSigmaRadPerSec.isFinite() &&
                    it.yawSigmaRadPerSec > 0.0
            }
            if (inLeg.isEmpty()) {
                legDriftRates[i] = priorRate
                val durationSec = (s1 - s0).coerceAtLeast(1.0) / speed
                legSigmas[i] = ( DEFAULT_UNOBSERVED_SIGMA_RAD + 0.0004 * durationSec ).coerceIn(MIN_LEG_SIGMA_RAD, MAX_LEG_SIGMA_RAD)
            } else {
                var wSum = PRIOR_WEIGHT
                var rateSum = PRIOR_WEIGHT * priorRate
                for (s in inLeg) {
                    val w = s.confidence01 / (s.yawSigmaRadPerSec * s.yawSigmaRadPerSec + 1e-4)
                    val obsBias = (-s.yawResidualRadPerSec).coerceIn(
                        -HeadingQuality.MAX_DRIFT_RAD_PER_SEC * 2.0,
                        HeadingQuality.MAX_DRIFT_RAD_PER_SEC * 2.0,
                    )
                    wSum += w
                    rateSum += w * obsBias
                }
                val meanRate = (rateSum / wSum).coerceIn(
                    -HeadingQuality.MAX_DRIFT_RAD_PER_SEC * 2.0,
                    HeadingQuality.MAX_DRIFT_RAD_PER_SEC * 2.0,
                )
                legDriftRates[i] = meanRate

                var varSum = 0.0
                for (s in inLeg) {
                    val obsBias = -s.yawResidualRadPerSec
                    val d = obsBias - meanRate
                    varSum += d * d
                }
                val rmsRate = sqrt(varSum / inLeg.size)
                val durationSec = (s1 - s0).coerceAtLeast(1.0) / speed
                legSigmas[i] = (BASE_POLISHED_SIGMA_RAD + rmsRate * durationSec * 0.5)
                    .coerceIn(MIN_LEG_SIGMA_RAD, MAX_LEG_SIGMA_RAD)
            }
        }

        // Forward + backward pass over legDriftRates so adjacent legs share smooth gyro bias.
        val smoothedRates = legDriftRates.copyOf()
        if (legCount >= 2) {
            for (i in 1 until legCount) {
                smoothedRates[i] = 0.7 * smoothedRates[i] + 0.3 * smoothedRates[i - 1]
            }
            for (i in legCount - 2 downTo 0) {
                smoothedRates[i] = 0.7 * smoothedRates[i] + 0.3 * smoothedRates[i + 1]
            }
        }

        // Integrate cumulative drift offset at the midpoint of each leg and subtract it.
        val correctedHeadingsRad = DoubleArray(legCount)
        var cumulativeDriftRad = 0.0
        for (i in 0 until legCount) {
            val legLen = route.legs[i].lengthM.coerceAtLeast(0.0)
            val dtSec = legLen / speed
            val midDrift = cumulativeDriftRad + 0.5 * smoothedRates[i] * dtSec
            correctedHeadingsRad[i] = wrapPi(rawLegHeadingsRad[i] - midDrift)
            cumulativeDriftRad += smoothedRates[i] * dtSec
        }

        // Rebuild path from corrected leg headings (preserving step distances d).
        var rebuiltPath = rebuildPathFromLegs(route.path, legStarts, legEnds, correctedHeadingsRad, route.totalLengthM)

        // Loop closure check: when start and destination view strips match (or round-trip geometry closes),
        // distribute the closure error across legs proportionally to legLengthM * legSigmaRad^2.
        var loopClosed = false
        val strips = existingParallax?.viewStrips.orEmpty()
        val startStrip = strips.firstOrNull { it.atDistanceM <= 2.0 }
        val endStrip = strips.lastOrNull { it.atDistanceM >= route.totalLengthM - 4.0 && it !== startStrip }
        val viewsMatch = if (startStrip != null && endStrip != null) {
            val sBytes = runCatching { Base64.decode(startStrip.dataB64) }.getOrNull()
            val eBytes = runCatching { Base64.decode(endStrip.dataB64) }.getOrNull()
            if (sBytes != null && eBytes != null && sBytes.size == 96 && eBytes.size == 96) {
                val matcher = ViewFixMatcher(existingParallax)
                matcher.scoreStripPair(eBytes, sBytes, recordPeaks = false) >= LOOP_CLOSURE_MIN_SCORE
            } else false
        } else false

        if (viewsMatch && rebuiltPath.size >= 2 && legCount >= 2) {
            val endPt = rebuiltPath.last()
            val gapM = hypot(endPt.x, endPt.y)
                if (hypot(endPt.x, endPt.y) > 0.05 && hypot(endPt.x, endPt.y) <= route.totalLengthM * 0.25) {
                    loopClosed = true
                    val weights = DoubleArray(legCount) { i ->
                        route.legs[i].lengthM.coerceAtLeast(0.5) * legSigmas[i] * legSigmas[i]
                    }
                    val totalW = weights.sum().coerceAtLeast(1e-9)
                    val endX = endPt.x
                    val endY = endPt.y

                    var curX = 0.0
                    var curY = 0.0
                    var cumW = 0.0
                    for (i in 0 until legCount) {
                        val len = route.legs[i].lengthM
                        val h = correctedHeadingsRad[i]
                        val rawNextX = curX + len * sin(h)
                        val rawNextY = curY + len * cos(h)
                        cumW += weights[i]
                        val frac = cumW / totalW
                        val targetX = rawNextX - frac * endX
                        val targetY = rawNextY - frac * endY
                        val dx = targetX - curX
                        val dy = targetY - curY
                        if (hypot(dx, dy) > 1e-6) {
                            correctedHeadingsRad[i] = atan2(dx, dy)
                        }
                        curX += len * sin(correctedHeadingsRad[i])
                        curY += len * cos(correctedHeadingsRad[i])
                    }
                }
        }

        val updatedLegs = route.legs.mapIndexed { i, leg ->
            val deg = correctedHeadingsRad[i] * 180.0 / PI
            val rounded = (deg * 100.0).roundToInt() / 100.0
            if (abs(rounded - leg.headingRelDeg) < 1e-4) leg else leg.copy(headingRelDeg = rounded)
        }
        val headingsChanged = updatedLegs.indices.any { i -> updatedLegs[i].headingRelDeg != route.legs[i].headingRelDeg }
        if (headingsChanged || loopClosed) {
            val finalLegHeadingsRad = DoubleArray(legCount) { i -> updatedLegs[i].headingRelDeg * PI / 180.0 }
            rebuiltPath = rebuildPathFromLegs(
                route.path,
                legStarts,
                legEnds,
                finalLegHeadingsRad,
                route.totalLengthM,
                closeLoopEndpoint = loopClosed,
            )
        } else {
            rebuiltPath = route.path
        }

        val totalBuckets = maxOf(1, (route.totalLengthM / ParallaxProfile.STEP_M).roundToInt())
        val updatedParallax = (existingParallax ?: ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(totalBuckets)),
            trackability = Base64.encode(ByteArray(totalBuckets)),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )).copy(
            legSigmaRad = legSigmas.map { (it * 10_000.0).roundToInt() / 10_000.0 },
        )

        val basePerception = route.perception ?: RoutePerception()
        val polishedRoute = route.copy(
            legs = updatedLegs,
            path = rebuiltPath,
        ).withPerception(basePerception.copy(parallax = updatedParallax))

        val driftDeg = abs(cumulativeDriftRad) * 180.0 / PI
        val roundedDeg = (driftDeg * 10.0).roundToInt() / 10.0
        val copy = if (roundedDeg >= 0.1 || loopClosed) {
            "polished: heading drift −${roundedDeg}°" + if (loopClosed) " · loop closed" else ""
        } else {
            null
        }
        return Result(
            route = polishedRoute,
            totalDriftRemovedDeg = roundedDeg,
            loopClosed = loopClosed,
            summaryCopy = copy,
        )
    }

    private fun rebuildPathFromLegs(
        originalPath: List<PathPoint>,
        legStarts: DoubleArray,
        legEnds: DoubleArray,
        legHeadingsRad: DoubleArray,
        totalLengthM: Double,
        closeLoopEndpoint: Boolean = false,
    ): List<PathPoint> {
        if (originalPath.isEmpty()) return emptyList()
        val points = ArrayList<PathPoint>(originalPath.size)
        var x = 0.0
        var y = 0.0
        points.add(PathPoint(d = originalPath.first().d, x = 0.0, y = 0.0))

        for (pIdx in 1 until originalPath.size) {
            val prevD = originalPath[pIdx - 1].d
            val curD = originalPath[pIdx].d
            val ds = (curD - prevD).coerceAtLeast(0.0)
            val midD = 0.5 * (prevD + curD)
            var legIdx = 0
            for (i in legStarts.indices) {
                if (midD >= legStarts[i] && midD <= legEnds[i] + 1e-6) {
                    legIdx = i
                    break
                } else if (midD >= legStarts[i]) {
                    legIdx = i
                }
            }
            val h = legHeadingsRad[legIdx]
            x += ds * sin(h)
            y += ds * cos(h)
            points.add(PathPoint(d = curD, x = x, y = y))
        }

        if (closeLoopEndpoint && points.size >= 2 && totalLengthM > 0.0) {
            val errX = points.last().x
            val errY = points.last().y
            for (i in 1 until points.size) {
                val frac = (points[i].d / totalLengthM).coerceIn(0.0, 1.0)
                points[i] = points[i].copy(
                    x = points[i].x - frac * errX,
                    y = points[i].y - frac * errY,
                )
            }
        }
        return points
    }

    private const val DEFAULT_WALKING_SPEED_MPS = 1.0
    private const val PRIOR_WEIGHT = 1.0
    const val DEFAULT_UNOBSERVED_SIGMA_RAD = 0.14 // ~8°
    const val BASE_POLISHED_SIGMA_RAD = 0.05 // ~2.9°
    const val MIN_LEG_SIGMA_RAD = 0.03
    const val MAX_LEG_SIGMA_RAD = 0.50
    private const val LOOP_CLOSURE_MIN_SCORE = 0.65
}
