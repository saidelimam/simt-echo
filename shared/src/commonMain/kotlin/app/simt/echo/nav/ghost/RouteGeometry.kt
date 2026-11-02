package app.simt.echo.nav.ghost

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.TurnDir
import app.simt.echo.nav.degToRad

/**
 * Precomputed route geometry for the Ghost filter (plan W12-02).
 *
 * Everything is resolved once in the constructor (plain arrays + one id
 * map); per-update queries ([headingAt], [legStartS], [nodeS],
 * [nearestTurnS]) allocate nothing and scan at most a handful of legs.
 */
class RouteGeometry(route: EchoRoute) {

    /** Total route length, metres (sum of leg lengths). */
    val totalLengthM: Double = route.legs.sumOf { it.lengthM }

    private val nodeSById: Map<String, Double> =
        route.nodes.associate { it.id to it.atDistanceM }

    private val legStartS: DoubleArray =
        DoubleArray(route.legs.size) { i -> nodeSById[route.legs[i].fromNode] ?: 0.0 }

    private val legHeadingRad: DoubleArray =
        DoubleArray(route.legs.size) { i -> degToRad(route.legs[i].headingRelDeg) }

    private val turnS: DoubleArray
    private val turnDirs: Array<TurnDir?>

    init {
        val turns = route.nodes.filter { it.type == NodeType.TURN && it.turn != null }
        turnS = DoubleArray(turns.size) { i -> turns[i].atDistanceM }
        turnDirs = Array(turns.size) { i -> turns[i].turn }
    }

    /** Route heading (rad, route-relative frame) at along-route [s]. */
    fun headingAt(s: Double): Double {
        if (legHeadingRad.isEmpty()) return 0.0
        var idx = 0
        for (i in legStartS.indices) {
            if (s >= legStartS[i]) idx = i
        }
        return legHeadingRad[idx]
    }

    /** Along-route position of the start of leg [legIndex], metres. */
    fun legStartS(legIndex: Int): Double =
        legStartS.getOrElse(legIndex) { totalLengthM }

    /**
     * Index of the leg containing along-route [s] (W12-06 relock: the
     * found position re-derives its leg rather than trusting the stale
     * one). Clamped to the leg range; empty routes read leg 0.
     */
    fun legIndexAt(s: Double): Int {
        var idx = 0
        for (i in legStartS.indices) {
            if (s >= legStartS[i]) idx = i
        }
        return idx.coerceIn(0, (legStartS.size - 1).coerceAtLeast(0))
    }

    /** Along-route position of a node id, or null when unknown. */
    fun nodeS(nodeId: String): Double? = nodeSById[nodeId]

    /**
     * Along-route position of the nearest node whose turn is [dir], or
     * [Double.NaN] when the route has no such node. A sentinel rather than
     * a nullable: this runs per particle, and a `Double?` would box.
     */
    fun nearestTurnS(s: Double, dir: TurnDir): Double {
        var best = Double.NaN
        var bestDist = Double.MAX_VALUE
        for (i in turnS.indices) {
            if (turnDirs[i] != dir) continue
            val d = kotlin.math.abs(s - turnS[i])
            if (d < bestDist) {
                bestDist = d
                best = turnS[i]
            }
        }
        return best
    }
}
