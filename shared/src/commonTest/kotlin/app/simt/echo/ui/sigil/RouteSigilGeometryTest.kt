package app.simt.echo.ui.sigil

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** W13-03 sigils (luminous-freeze §6): deterministic, upright, normalised, never Cyan. */
class RouteSigilGeometryTest {

    /** An L walk: 20 m forward (+y), then 10 m right (+x). */
    private fun lRoute(id: String = "route-a", path: List<PathPoint> = lPath()) = EchoRoute(
        routeId = id,
        name = "L",
        createdAtEpochMs = 0,
        creatorLabel = "creator",
        motionSource = "test",
        totalLengthM = 30.0,
        start = StartAnchor(description = "", photoAssetId = null, alignmentHint = ""),
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Start"),
            RouteNode(id = "n1", type = NodeType.TURN, atDistanceM = 20.0, label = "", turn = TurnDir.RIGHT),
            RouteNode(id = "n2", type = NodeType.DESTINATION, atDistanceM = 30.0, label = "Door"),
        ),
        legs = listOf(
            RouteLeg(fromNode = "n0", toNode = "n1", lengthM = 20.0, headingRelDeg = 0.0),
            RouteLeg(fromNode = "n1", toNode = "n2", lengthM = 10.0, headingRelDeg = 90.0),
        ),
        path = path,
    )

    private fun lPath(): List<PathPoint> =
        (0..40).map { PathPoint(d = it * 0.5, x = 0.0, y = it * 0.5) } +
            (1..20).map { PathPoint(d = 20.0 + it * 0.5, x = it * 0.5, y = 20.0) }

    @Test
    fun sameRouteSameSigil() {
        val a = routeSigil(lRoute())
        val b = routeSigil(lRoute())
        assertContentEquals(a.xs, b.xs)
        assertContentEquals(a.ys, b.ys)
        assertEquals(a.accent, b.accent)
        assertEquals(SIGIL_POINTS, a.xs.size)
    }

    @Test
    fun glyphFitsTheUnitCircleAndTouchesIt() {
        val g = routeSigil(lRoute())
        val radii = g.xs.indices.map { hypot(g.xs[it], g.ys[it]) }
        assertTrue(radii.all { it <= 1.0001f }, "inside the unit circle")
        assertTrue(abs(radii.max() - 1f) < 1e-3f, "scaled to use the whole seal")
    }

    @Test
    fun theWalkHeadsUp() {
        val g = routeSigil(lRoute())
        assertTrue(g.ys.last() < g.ys.first(), "destination is above the start on screen")
        assertTrue(abs(g.xs.last() - g.xs.first()) < 1e-3f, "start → destination is vertical")
    }

    @Test
    fun marksCoverTurnsAndTheDestination() {
        val marks = routeSigil(lRoute()).marks
        assertEquals(2, marks.size)
        assertEquals(listOf(false, true), marks.map { it.destination })
    }

    @Test
    fun legsStandInWhenThereIsNoPath() {
        val fromLegs = routeSigil(lRoute(path = emptyList()))
        val fromPath = routeSigil(lRoute())
        for (i in fromPath.xs.indices) {
            assertTrue(abs(fromLegs.xs[i] - fromPath.xs[i]) < 0.05f, "leg-built sigil matches the walked one at $i")
        }
    }

    @Test
    fun aWalkWithNoLengthIsOneDot() {
        val g = routeSigil(lRoute(path = listOf(PathPoint(0.0, 0.0, 0.0))).copy(legs = emptyList()))
        assertEquals(1, g.xs.size)
    }

    @Test
    fun accentIsStableAndSpreadAcrossTheFourChannels() {
        assertEquals(sigilAccentFor("route-a"), sigilAccentFor("route-a"))
        val seen = (0 until 64).map { sigilAccentFor("route-$it") }.toSet()
        assertEquals(SigilAccent.entries.toSet(), seen, "all four non-live channels appear")
        assertNotEquals(fnv1a("a"), fnv1a("b"))
    }

    @Test
    fun traceTimeStaysInsideTheFreezeWindow() {
        val ms = sigilTraceMs(routeSigil(lRoute()))
        assertTrue(ms in 600..1200, "trace $ms ms")
    }
}
