package app.simt.echo

import app.simt.echo.model.EchoIds
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.EmbeddedPrint
import app.simt.echo.model.Landmark
import app.simt.echo.model.Confirmation
import app.simt.echo.model.LandmarkSource
import app.simt.echo.model.MagProfile
import app.simt.echo.model.NodeFingerprint
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteHealth
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.RoutePerception
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import app.simt.echo.model.WaypointPrint
import app.simt.echo.model.withPerception
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Canonical test route: start → 20 m → right → 15 m → left → 10 m →
 * destination (45 m, 3 legs). Segmenter, Navigator and Minimap tests all
 * walk this same route, so expectations are comparable across slices.
 */
object Fixtures {

    val L_ROUTE: EchoRoute = buildLRoute()

    /** L_ROUTE stamped to schema v2 with deterministic perception (W10-05). */
    val V2_ROUTE: EchoRoute = buildLRoute().withPerception(demoPerception())

    /** One deterministic int8[256] vector, varied per [salt], as base64. */
    fun printB64(salt: Int): String =
        Base64.encode(ByteArray(EmbeddedPrint.PRINT_BYTES) { i -> (i + salt * 13).toByte() })

    private fun demoPrint(salt: Int): EmbeddedPrint = EmbeddedPrint(
        embedderId = "echo-embed-01",
        projectionSeedVersion = 1,
        dataB64 = printB64(salt),
    )

    private fun demoPerception(): RoutePerception {
        val nodes = listOf(EchoIds.nodeId(1), EchoIds.nodeId(2)).mapIndexed { index, nodeId ->
            NodeFingerprint(nodeId = nodeId, prints = listOf(demoPrint(index)))
        }
        // Waypoint prints every 5 m over the 45 m fixture (10 entries).
        val waypoints = (0..45 step 5).map { d ->
            WaypointPrint(atDistanceM = d.toDouble(), print = demoPrint(100 + d))
        }
        // Mag profile every 0.5 m: 90 int16 samples of magnitude + vertical.
        val mag = MagProfile(
            magnitude = List(90) { i -> (480 + i % 7).toShort() },
            vertical = List(90) { i -> (400 + i % 5).toShort() },
        )
        return RoutePerception(
            fingerprints = nodes,
            waypointPrints = waypoints,
            magProfile = mag,
            sigilSeed = 123456789L,
            health = RouteHealth(score01 = 0.9, checkedAtEpochMs = 1_762_000_000_000L),
        )
    }

    private fun buildLRoute(): EchoRoute {
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
        return EchoRoute(
            routeId = "fixture-l-route",
            name = "L route (fixture)",
            createdAtEpochMs = 1_762_000_000_000L,
            creatorLabel = "fixture",
            motionSource = "echo_builtin_pdr",
            totalLengthM = 45.0,
            start = StartAnchor(
                description = "Entrance",
                photoAssetId = EchoIds.assetId("start"),
                alignmentHint = "Stand where the photo was taken and face the same way",
            ),
            nodes = nodes,
            legs = legs,
            path = buildPath(),
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
    private fun buildPath(): List<PathPoint> {
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
}

/**
 * The v1 reader's view of a route (W10-05): every v1 field the old app
 * knows, and nothing newer. Decoding v2 JSON into this must succeed with
 * `perception` silently dropped — the executable form of the "ignore, never
 * a crash" decision.
 */
@Serializable
data class V1RouteView(
    val schemaVersion: Int = 1,
    val routeId: String = "",
    val name: String = "",
    val totalLengthM: Double = 0.0,
    val nodes: List<RouteNode> = emptyList(),
)
