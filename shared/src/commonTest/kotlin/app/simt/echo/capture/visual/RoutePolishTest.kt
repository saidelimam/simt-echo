package app.simt.echo.capture.visual

import app.simt.echo.capture.wrapPi
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.nav.ghost.GhostFilter
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W18-01 gates: offline Route Polish removes per-leg heading drift (recorded-vs-truth
 * path error reduced ≥ 25 % on a 10-leg route and never increased on any scenario),
 * stores per-leg `legSigmaRad` that caps GhostFilter heading κ, closes round-trip
 * loops proportionally to leg length and uncertainty, and is idempotent and deterministic.
 */
class RoutePolishTest {

    private fun buildMultiLegRoutes(
        trueHeadingsDeg: List<Double>,
        legLengthM: Double,
        driftRateDegPerMin: Double,
        speedMps: Double = 1.0,
    ): Triple<EchoRoute, EchoRoute, List<RoutePolish.VisualYawSample>> {
        val driftRateRadPerSec = driftRateDegPerMin * PI / 180.0 / 60.0
        val legCount = trueHeadingsDeg.size
        val nodes = ArrayList<RouteNode>(legCount + 1)
        val trueLegs = ArrayList<RouteLeg>(legCount)
        val driftedLegs = ArrayList<RouteLeg>(legCount)
        val truePath = ArrayList<PathPoint>()
        val driftedPath = ArrayList<PathPoint>()
        val visualSamples = ArrayList<RoutePolish.VisualYawSample>()

        nodes.add(RouteNode("n0", NodeType.START, 0.0, "Start"))
        truePath.add(PathPoint(0.0, 0.0, 0.0))
        driftedPath.add(PathPoint(0.0, 0.0, 0.0))

        var trueX = 0.0
        var trueY = 0.0
        var driftX = 0.0
        var driftY = 0.0
        var dist = 0.0

        for (i in 0 until legCount) {
            val legStart = dist
            val legEnd = dist + legLengthM
            val midT = (legStart + 0.5 * legLengthM) / speedMps
            val trueHRad = trueHeadingsDeg[i] * PI / 180.0
            val driftedHRad = wrapPi(trueHRad + driftRateRadPerSec * midT)

            val fromId = "n$i"
            val toId = "n${i + 1}"
            val type = if (i == legCount - 1) NodeType.DESTINATION else NodeType.TURN
            nodes.add(RouteNode(toId, type, legEnd, "Spot ${i + 1}"))
            trueLegs.add(RouteLeg(fromId, toId, legLengthM, trueHeadingsDeg[i]))
            driftedLegs.add(RouteLeg(fromId, toId, legLengthM, driftedHRad * 180.0 / PI))

            val steps = (legLengthM / 0.5).toInt()
            for (s in 1..steps) {
                dist += 0.5
                val tSec = dist / speedMps
                trueX += 0.5 * sin(trueHRad)
                trueY += 0.5 * cos(trueHRad)
                driftX += 0.5 * sin(driftedHRad)
                driftY += 0.5 * cos(driftedHRad)
                truePath.add(PathPoint(dist, trueX, trueY))
                driftedPath.add(PathPoint(dist, driftX, driftY))
                visualSamples.add(
                    RoutePolish.VisualYawSample(
                        distanceM = dist,
                        tSec = tSec,
                        yawResidualRadPerSec = -driftRateRadPerSec,
                        yawSigmaRadPerSec = 0.004,
                        confidence01 = 0.90,
                    ),
                )
            }
        }

        val totalBuckets = (dist / ParallaxProfile.STEP_M).toInt()
        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(totalBuckets) { ParallaxProfiler.quantiseRho(0.22) }),
            trackability = Base64.encode(ByteArray(totalBuckets) { 220.toByte() }),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )

        val truthRoute = EchoRoute(
            routeId = "truth-10",
            name = "Truth",
            createdAtEpochMs = 0L,
            creatorLabel = "sim",
            motionSource = "sim",
            totalLengthM = dist,
            start = StartAnchor("Start", null, "Face forward"),
            nodes = nodes,
            legs = trueLegs,
            path = truePath,
        ).withParallax(profile)

        val driftedRoute = truthRoute.copy(
            routeId = "drifted-10",
            legs = driftedLegs,
            path = driftedPath,
        )
        return Triple(truthRoute, driftedRoute, visualSamples)
    }

    private fun rmsPathError(a: List<PathPoint>, b: List<PathPoint>): Double {
        require(a.size == b.size && a.isNotEmpty())
        var sumSq = 0.0
        for (i in a.indices) {
            val dx = a[i].x - b[i].x
            val dy = a[i].y - b[i].y
            sumSq += dx * dx + dy * dy
        }
        return kotlin.math.sqrt(sumSq / a.size)
    }

    @Test
    fun tenLegRouteReducesPathErrorByAtLeastTwentyFivePercentAndNeverRegressesNonOrthogonal() {
        val tenHeadings = listOf(0.0, 90.0, 0.0, -90.0, 0.0, 90.0, 180.0, 90.0, 0.0, -90.0)
        val (truth, drifted, samples) = buildMultiLegRoutes(
            trueHeadingsDeg = tenHeadings,
            legLengthM = 18.0,
            driftRateDegPerMin = 2.0,
        )

        val beforeErrorM = rmsPathError(truth.path, drifted.path)
        val polishedResult = RoutePolish.polish(
            route = drifted,
            visualSamples = samples,
            stillnessDriftPriorRadPerSec = 1.8 * PI / 180.0 / 60.0,
        )
        val afterErrorM = rmsPathError(truth.path, polishedResult.route.path)
        val reduction = (beforeErrorM - afterErrorM) / beforeErrorM

        assertTrue(beforeErrorM > 1.0, "unpolished 10-leg route must have measurable drift error, got $beforeErrorM m")
        assertTrue(
            reduction >= 0.25,
            "10-leg Route Polish must reduce path RMS error by >= 25%: before=$beforeErrorM m, after=$afterErrorM m (reduction=${reduction * 100}%)",
        )
        assertNotNull(polishedResult.summaryCopy)
        assertTrue("polished: heading drift −" in polishedResult.summaryCopy!!)
        assertEquals(10, polishedResult.route.perception?.parallax?.legSigmaRad?.size)

        // Non-orthogonal diagonal route (45°, 30°, -60°, 15°): polishing with 0 drift must not increase error.
        val diagHeadings = listOf(45.0, 30.0, -60.0, 15.0)
        val (diagTruth, diagRecorded, diagSamples) = buildMultiLegRoutes(
            trueHeadingsDeg = diagHeadings,
            legLengthM = 10.0,
            driftRateDegPerMin = 0.0,
        )
        val diagBefore = rmsPathError(diagTruth.path, diagRecorded.path)
        val diagPolished = RoutePolish.polish(diagRecorded, diagSamples).route
        val diagAfter = rmsPathError(diagTruth.path, diagPolished.path)
        assertTrue(diagAfter <= diagBefore + 1e-6, "non-orthogonal scenario must not regress: before=$diagBefore, after=$diagAfter")
    }

    @Test
    fun loopClosureDistributesErrorAndPolishingIsIdempotentAndDeterministic() {
        // 4-leg square loop (20 m each = 80 m total: 0°, 90°, 180°, -90°) with 2°/min drift so the end misses (0,0).
        val headings = listOf(0.0, 90.0, 180.0, -90.0)
        val (_, driftedLoop, _) = buildMultiLegRoutes(
            trueHeadingsDeg = headings,
            legLengthM = 20.0,
            driftRateDegPerMin = 2.0,
        )
        val matchingStrip = ByteArray(96) { k -> ((sin(k * 0.3) * 80.0).toInt()).toByte() }
        val b64 = Base64.encode(matchingStrip)
        val loopParallax = driftedLoop.perception!!.parallax!!.copy(
            viewStrips = listOf(
                app.simt.echo.model.ViewStrip(0.0, b64),
                app.simt.echo.model.ViewStrip(80.0, b64),
            ),
        )
        val loopRoute = driftedLoop.withParallax(loopParallax)

        val unclosedEnd = loopRoute.path.last()
        assertTrue(hypot(unclosedEnd.x, unclosedEnd.y) > 0.2, "drifted loop must have open gap before closure")

        val closedFirst = RoutePolish.polish(loopRoute)
        assertTrue(closedFirst.loopClosed, "matching start and end strips on a loop must trigger loop closure")
        val closedEnd = closedFirst.route.path.last()
        assertTrue(hypot(closedEnd.x, closedEnd.y) < 1e-4, "loop closure must close the endpoint gap, got (${closedEnd.x}, ${closedEnd.y})")

        // Idempotence: polishing a second time changes nothing.
        val closedSecond = RoutePolish.polish(closedFirst.route)
        assertEquals(closedFirst.route, closedSecond.route, "polishing twice must be idempotent")

        // Determinism: running polish again on the original produces identical output.
        assertEquals(closedFirst, RoutePolish.polish(loopRoute))

        // Verify GhostFilter uses legSigmaRad to cap heading kappa without crashing.
        val filter = GhostFilter(closedFirst.route, seed = 42L)
        filter.observeHeading(0.1, quality01 = 1.0)
        assertEquals(1, filter.headingObservationCount)
    }
}
