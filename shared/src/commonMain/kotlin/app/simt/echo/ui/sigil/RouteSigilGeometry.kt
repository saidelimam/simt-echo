package app.simt.echo.ui.sigil

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Route sigil geometry (plan W13-03, docs/research/luminous-freeze.md §6):
// every route's own walk, drawn as a glyph. Pure and deterministic — the
// same route renders the same sigil on the creator's and the follower's
// phone, because it is computed from what both phones hold (path + id).

const val SIGIL_POINTS = 64

/** Accent channel for a sigil. Cyan is reserved for live state and never used. */
enum class SigilAccent { EMBER, ROSE, VIOLET, GOLD }

/** A marker on the sigil: a turn or the destination, in sigil space. */
data class SigilMark(val x: Float, val y: Float, val destination: Boolean)

/**
 * The glyph in unit space: x right, y down (screen convention), the walk
 * heading up, everything inside the unit circle. [xs]/[ys] run from start
 * to destination.
 */
class SigilGeometry(
    val xs: FloatArray,
    val ys: FloatArray,
    val marks: List<SigilMark>,
    val accent: SigilAccent,
)

/** Stable across platforms (unlike relying on a runtime's String.hashCode). */
internal fun fnv1a(text: String): Int {
    var hash = 0x811C9DC5.toInt()
    for (ch in text) {
        hash = hash xor ch.code
        hash *= 0x01000193
    }
    return hash
}

fun sigilAccentFor(routeId: String): SigilAccent {
    val values = SigilAccent.entries
    return values[(fnv1a(routeId) ushr 1) % values.size]
}

fun routeSigil(route: EchoRoute, points: Int = SIGIL_POINTS): SigilGeometry {
    val path = route.path.takeIf { it.size >= 2 && it.last().d > it.first().d } ?: pathFromLegs(route)
    val accent = sigilAccentFor(route.routeId)
    if (path.size < 2) {
        // A walk with no length (e.g. recorded standing still): one dot.
        return SigilGeometry(FloatArray(1), FloatArray(1), listOf(SigilMark(0f, 0f, destination = true)), accent)
    }
    val total = path.last().d - path.first().d
    val xs = DoubleArray(points)
    val ys = DoubleArray(points)
    for (i in 0 until points) {
        val d = path.first().d + total * i / (points - 1)
        sampleAt(path, d, xs, ys, i)
    }
    val turnDistances = route.nodes
        .filter { it.type == NodeType.TURN || it.type == NodeType.DESTINATION }
        .map { (it.atDistanceM to (it.type == NodeType.DESTINATION)) }
    val markXs = DoubleArray(turnDistances.size)
    val markYs = DoubleArray(turnDistances.size)
    turnDistances.forEachIndexed { i, (d, _) ->
        sampleAt(path, d.coerceIn(path.first().d, path.last().d), markXs, markYs, i)
    }

    // Rotate so start → destination points up (screen −y), then flip y to
    // screen convention; centre the bounding box; scale into the unit circle.
    val dx = xs.last() - xs.first()
    val dy = ys.last() - ys.first()
    val angle = if (hypot(dx, dy) < 1e-6) 0.0 else atan2(dy, dx) - kotlin.math.PI / 2
    val c = cos(-angle)
    val s = sin(-angle)
    fun rx(x: Double, y: Double) = x * c - y * s
    fun ry(x: Double, y: Double) = -(x * s + y * c)
    val outX = DoubleArray(points) { rx(xs[it], ys[it]) }
    val outY = DoubleArray(points) { ry(xs[it], ys[it]) }
    val cx = (outX.min() + outX.max()) / 2
    val cy = (outY.min() + outY.max()) / 2
    var radius = 0.0
    for (i in 0 until points) radius = maxOf(radius, hypot(outX[i] - cx, outY[i] - cy))
    val scale = if (radius < 1e-6) 1.0 else 1.0 / radius
    return SigilGeometry(
        xs = FloatArray(points) { ((outX[it] - cx) * scale).toFloat() },
        ys = FloatArray(points) { ((outY[it] - cy) * scale).toFloat() },
        marks = turnDistances.indices.map { i ->
            SigilMark(
                x = ((rx(markXs[i], markYs[i]) - cx) * scale).toFloat(),
                y = ((ry(markXs[i], markYs[i]) - cy) * scale).toFloat(),
                destination = turnDistances[i].second,
            )
        },
        accent = accent,
    )
}

/** Linear interpolation of the path at distance [d], written into slot [i]. */
private fun sampleAt(path: List<PathPoint>, d: Double, xs: DoubleArray, ys: DoubleArray, i: Int) {
    var k = 1
    while (k < path.size - 1 && path[k].d < d) k++
    val a = path[k - 1]
    val b = path[k]
    val span = b.d - a.d
    val t = if (span <= 0.0) 0.0 else ((d - a.d) / span).coerceIn(0.0, 1.0)
    xs[i] = a.x + (b.x - a.x) * t
    ys[i] = a.y + (b.y - a.y) * t
}

/** Received routes may carry legs without a dense path: walk the legs. */
private fun pathFromLegs(route: EchoRoute): List<PathPoint> {
    if (route.legs.isEmpty()) return emptyList()
    val out = mutableListOf(PathPoint(d = 0.0, x = 0.0, y = 0.0))
    var x = 0.0
    var y = 0.0
    var d = 0.0
    for (leg in route.legs) {
        if (leg.lengthM <= 0.0) continue
        val rad = leg.headingRelDeg * kotlin.math.PI / 180.0
        x += sin(rad) * leg.lengthM
        y += cos(rad) * leg.lengthM
        d += leg.lengthM
        out += PathPoint(d = d, x = x, y = y)
    }
    return out
}
