package app.simt.echo.ui.screens.navigate

import app.simt.echo.model.Confirmation
import app.simt.echo.model.EchoIds
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.model.Landmark
import app.simt.echo.model.LandmarkSource
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir

/**
 * Guided practice tour (plan W13-13): a first-run offer that replays a
 * bundled fixture walk through the real Navigate instrument.
 *
 * The walk below is code, not data: it is never saved, so it never
 * appears in `routes/` listings (0002's demo-data purge stands) and the
 * tour runs with a null trial sink, so it never touches `trials/`
 * either. The geometry mirrors the canonical test L route (start →
 * 20 m → right → 15 m → left → 10 m → destination) so the replay
 * driver, haptic sequence and arrival states are the already-gated ones.
 */
const val PRACTICE_ROUTE_ID = "practice-tour"

/** Shell + offer label: the tour is always named Practice, never a route. */
const val PRACTICE_LABEL = "Practice"

/** First-run Home offer copy. */
const val PRACTICE_OFFER_LABEL = "Practice a guided walk"

fun practiceRoute(): SavedRoute {
    val nodes = listOf(
        RouteNode(id = EchoIds.nodeId(0), type = NodeType.START, atDistanceM = 0.0, label = "Entrance"),
        RouteNode(
            id = EchoIds.nodeId(1),
            type = NodeType.TURN,
            atDistanceM = 20.0,
            label = "Turn right",
            turn = TurnDir.RIGHT,
            landmarkIds = listOf(EchoIds.landmarkId(EchoIds.nodeId(1), 0)),
        ),
        RouteNode(
            id = EchoIds.nodeId(2),
            type = NodeType.TURN,
            atDistanceM = 35.0,
            label = "Turn left",
            turn = TurnDir.LEFT,
        ),
        RouteNode(
            id = EchoIds.nodeId(3),
            type = NodeType.DESTINATION,
            atDistanceM = 45.0,
            label = "Elevator",
        ),
    )
    val legs = listOf(
        RouteLeg(fromNode = EchoIds.nodeId(0), toNode = EchoIds.nodeId(1), lengthM = 20.0, headingRelDeg = 0.0),
        RouteLeg(fromNode = EchoIds.nodeId(1), toNode = EchoIds.nodeId(2), lengthM = 15.0, headingRelDeg = 90.0),
        RouteLeg(fromNode = EchoIds.nodeId(2), toNode = EchoIds.nodeId(3), lengthM = 10.0, headingRelDeg = 0.0),
    )
    return SavedRoute(
        routeId = PRACTICE_ROUTE_ID,
        name = "Practice walk",
        createdAtEpochMs = 0L,
        creatorLabel = "practice",
        motionSource = "replay",
        totalLengthM = 45.0,
        start = StartAnchor(
            description = "Entrance",
            photoAssetId = null,
            alignmentHint = "Stand still and face forward",
        ),
        nodes = nodes,
        legs = legs,
        path = practicePath(),
        landmarks = listOf(
            Landmark(
                id = EchoIds.landmarkId(EchoIds.nodeId(1), 0),
                nodeId = EchoIds.nodeId(1),
                title = "Red pillar",
                description = "Red pillar at the right turn",
                cue = "Turn right at the red pillar",
                source = LandmarkSource.MANUAL,
                confirmation = Confirmation.CREATOR_VERIFIED,
            ),
        ),
    )
}

/** Points every 0.5 m: north 20 m, east 15 m, north 10 m. */
private fun practicePath(): List<PathPoint> {
    val points = mutableListOf<PathPoint>()
    var d = 0.0
    while (d <= 45.0) {
        val (x, y) = when {
            d <= 20.0 -> 0.0 to d
            d <= 35.0 -> (d - 20.0) to 20.0
            else -> 15.0 to (20.0 + (d - 35.0))
        }
        points += PathPoint(d = d, x = x, y = y)
        d += 0.5
    }
    return points
}
