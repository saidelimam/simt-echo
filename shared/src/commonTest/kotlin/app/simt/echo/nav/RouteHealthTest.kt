package app.simt.echo.nav

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W12-07 acceptance: every penalty, floor, band, and nudge cap on fixtures. */
class RouteHealthTest {

    private fun node(
        id: String,
        type: NodeType,
        d: Double,
        label: String = "",
        turn: TurnDir? = null,
        cue: String = "",
        landmarks: List<String> = emptyList(),
    ) = RouteNode(
        id = id,
        type = type,
        atDistanceM = d,
        label = label,
        turn = turn,
        cue = cue,
        landmarkIds = landmarks,
    )

    private fun route(nodes: List<RouteNode>, legs: List<RouteLeg> = emptyList()) = EchoRoute(
        routeId = "r",
        name = "t",
        createdAtEpochMs = 0,
        creatorLabel = "c",
        motionSource = "replay",
        totalLengthM = 0.0,
        start = StartAnchor(description = "s", photoAssetId = null, alignmentHint = "a"),
        nodes = nodes,
        legs = legs,
        path = emptyList(),
    )

    private fun leg(from: String, to: String, len: Double) =
        RouteLeg(fromNode = from, toNode = to, lengthM = len, headingRelDeg = 0.0)

    @Test
    fun cleanRouteScoresFull() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("d", NodeType.DESTINATION, 10.0, label = "Home"),
            ),
        )
        assertEquals(HealthResult(100, BAND_READY, emptyList()), routeHealth(r))
    }

    @Test
    fun longLegWithoutLandmarkCosts15() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("d", NodeType.DESTINATION, 24.0, label = "Home"),
            ),
            listOf(leg("s", "d", 24.0)),
        )
        val h = routeHealth(r)
        assertEquals(85, h.score)
        assertEquals(BAND_READY, h.band)
        assertEquals(listOf("Add a landmark near 12 m"), h.nudges)
    }

    @Test
    fun longLegAnchoredByLandmarkIsFree() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("d", NodeType.DESTINATION, 30.0, label = "Home", landmarks = listOf("l")),
            ),
            listOf(leg("s", "d", 30.0)),
        )
        assertEquals(100, routeHealth(r).score)
    }

    @Test
    fun legAtExactly20mIsFree() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("d", NodeType.DESTINATION, 20.0, label = "Home"),
            ),
            listOf(leg("s", "d", 20.0)),
        )
        assertEquals(100, routeHealth(r).score)
    }

    @Test
    fun bareTurnCosts10() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("d", NodeType.DESTINATION, 20.0, label = "Home"),
            ),
        )
        val h = routeHealth(r)
        assertEquals(90, h.score)
        assertEquals(BAND_READY, h.band)
        assertEquals(listOf("Add a landmark near 10 m"), h.nudges)
    }

    @Test
    fun turnWithLandmarkIsFree() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t", NodeType.TURN, 10.0, turn = TurnDir.LEFT, landmarks = listOf("l")),
                node("d", NodeType.DESTINATION, 20.0, label = "Home"),
            ),
        )
        assertEquals(100, routeHealth(r).score)
    }

    @Test
    fun ambiguousTurnsCostExtraPerTurn() {
        // Two bare RIGHT turns 4 m apart: 2 x bare (−10) + 2 x ambiguous (−10).
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 14.0, turn = TurnDir.RIGHT),
                node("d", NodeType.DESTINATION, 30.0, label = "Home"),
            ),
        )
        assertEquals(60, routeHealth(r).score)
    }

    @Test
    fun aBareAmbiguousTurnAsksForItsLandmarkOnce() {
        // Each turn is both bare and a clash (−20 apiece), but the fix is
        // one landmark per node: four identical-looking lines would bury
        // the third nudge slot.
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 14.0, turn = TurnDir.RIGHT),
                node("d", NodeType.DESTINATION, 30.0),
            ),
        )
        assertEquals(
            listOf(
                "Add a landmark near 30 m",
                "Add a landmark near 10 m",
                "Add a landmark near 14 m",
            ),
            routeHealth(r).nudges,
        )
        assertEquals(60 - 20, routeHealth(r).score)
    }

    @Test
    fun turnsExactly6mApartAreAmbiguous() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.LEFT),
                node("t2", NodeType.TURN, 16.0, turn = TurnDir.LEFT),
                node("d", NodeType.DESTINATION, 40.0, label = "Home"),
            ),
        )
        assertEquals(60, routeHealth(r).score)
    }

    @Test
    fun separatedTurnsAreNotAmbiguous() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 30.0, turn = TurnDir.RIGHT),
                node("d", NodeType.DESTINATION, 40.0, label = "Home"),
            ),
        )
        // Bare turns only: −20 → 80, still Ready.
        assertEquals(80, routeHealth(r).score)
    }

    @Test
    fun differentDirectionsAreNotAmbiguous() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.LEFT),
                node("t2", NodeType.TURN, 12.0, turn = TurnDir.RIGHT),
                node("d", NodeType.DESTINATION, 30.0, label = "Home"),
            ),
        )
        assertEquals(80, routeHealth(r).score)
    }

    @Test
    fun landmarkedNeighborBreaksAmbiguity() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 12.0, turn = TurnDir.RIGHT, landmarks = listOf("l")),
                node("d", NodeType.DESTINATION, 30.0, label = "Home"),
            ),
        )
        // Only the bare turn counts; no ambiguity on either side.
        assertEquals(90, routeHealth(r).score)
    }

    @Test
    fun levelChangeWithoutCueCosts5() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("l", NodeType.LEVEL_CHANGE, 8.0),
                node("d", NodeType.DESTINATION, 16.0, label = "Home"),
            ),
        )
        assertEquals(95, routeHealth(r).score)
        assertEquals(listOf("Add a cue near 8 m"), routeHealth(r).nudges)
    }

    @Test
    fun levelChangeWithCueIsFree() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("l", NodeType.LEVEL_CHANGE, 8.0, cue = "Go up 1 level"),
                node("d", NodeType.DESTINATION, 16.0, label = "Home"),
            ),
        )
        assertEquals(100, routeHealth(r).score)
    }

    @Test
    fun bareDestinationCosts20() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("d", NodeType.DESTINATION, 23.0),
            ),
        )
        val h = routeHealth(r)
        assertEquals(80, h.score)
        assertEquals(BAND_READY, h.band)
        assertEquals(listOf("Add a landmark near 23 m"), h.nudges)
    }

    @Test
    fun destinationWithLandmarkIsFree() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("d", NodeType.DESTINATION, 23.0, landmarks = listOf("l")),
            ),
        )
        assertEquals(100, routeHealth(r).score)
    }

    @Test
    fun scoreFloorsAtZero() {
        // 8 long legs (−120) + 6 bare turns (−60) + level (−5) + dest (−20).
        val nodes = mutableListOf(node("n0", NodeType.START, 0.0))
        repeat(6) { i ->
            nodes += node("t$i", NodeType.TURN, 25.0 * (i + 1), turn = TurnDir.RIGHT)
        }
        nodes += node("l", NodeType.LEVEL_CHANGE, 175.0)
        nodes += node("d", NodeType.DESTINATION, 200.0)
        val legs = nodes.zipWithNext { a, b -> leg(a.id, b.id, 25.0) }
        assertEquals(0, routeHealth(route(nodes, legs)).score)
    }

    @Test
    fun bandAt80IsReady() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 40.0, turn = TurnDir.RIGHT),
                node("d", NodeType.DESTINATION, 50.0, label = "Home"),
            ),
        )
        val h = routeHealth(r)
        assertEquals(80, h.score)
        assertEquals(BAND_READY, h.band)
    }

    @Test
    fun bandBelow80IsFollowable() {
        // Penalties are multiples of 5, so 79 is unreachable; 75 probes
        // the top of the 50–79 band.
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 40.0, turn = TurnDir.RIGHT),
                node("l", NodeType.LEVEL_CHANGE, 45.0),
                node("d", NodeType.DESTINATION, 50.0, label = "Home"),
            ),
        )
        val h = routeHealth(r)
        assertEquals(75, h.score)
        assertEquals(BAND_FOLLOWABLE, h.band)
    }

    @Test
    fun bandAt50IsFollowable() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 40.0, turn = TurnDir.RIGHT),
                node("t3", NodeType.TURN, 70.0, turn = TurnDir.LEFT),
                node("d", NodeType.DESTINATION, 90.0),
            ),
        )
        val h = routeHealth(r)
        assertEquals(50, h.score)
        assertEquals(BAND_FOLLOWABLE, h.band)
    }

    @Test
    fun bandBelow50AsksForLandmarks() {
        // 49 is unreachable (penalties are multiples of 5); 45 probes it.
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t1", NodeType.TURN, 10.0, turn = TurnDir.RIGHT),
                node("t2", NodeType.TURN, 40.0, turn = TurnDir.RIGHT),
                node("t3", NodeType.TURN, 70.0, turn = TurnDir.LEFT),
                node("l", NodeType.LEVEL_CHANGE, 80.0),
                node("d", NodeType.DESTINATION, 90.0),
            ),
        )
        val h = routeHealth(r)
        assertEquals(45, h.score)
        assertEquals(BAND_ADD_LANDMARKS, h.band)
    }

    @Test
    fun nudgesCappedAtThreeSortedLargestFirst() {
        val r = route(
            listOf(
                node("s", NodeType.START, 0.0),
                node("t", NodeType.TURN, 10.0, turn = TurnDir.LEFT),
                node("l", NodeType.LEVEL_CHANGE, 20.0),
                node("d", NodeType.DESTINATION, 100.0),
            ),
            listOf(
                leg("s", "t", 10.0),
                leg("t", "l", 10.0),
                leg("l", "d", 80.0),
            ),
        )
        // Hits: turn −10 @10, level −5 @20, leg −15 @60, dest −20 @100.
        val h = routeHealth(r)
        assertEquals(50, h.score)
        assertEquals(
            listOf(
                "Add a landmark near 100 m",
                "Add a landmark near 60 m",
                "Add a landmark near 10 m",
            ),
            h.nudges,
        )
        assertTrue(h.nudges.size <= 3)
    }
}
