package app.simt.echo.nav

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Route Health score + nudges (plan §W12-07).
 *
 * Pure deterministic function over [EchoRoute]: no I/O, no platform
 * imports, commonMain only. Start at 100; subtract per issue; floor 0.
 *
 * Penalties (exact, per spec):
 * - −15 per leg longer than [LONG_LEG_M] whose endpoints both lack landmarks.
 * - −10 per TURN node without a landmark.
 * - −10 per ambiguous turn: a TURN node sharing its turn direction with
 *   another node within [AMBIGUOUS_WINDOW_M] (inclusive), with no landmark
 *   on either node. Evaluated per turn node, so a clashing pair costs −20.
 * - −5 per LEVEL_CHANGE node with a blank cue.
 * - −20 per DESTINATION node with neither landmark nor (non-blank) label.
 *
 * Nudges: one per distinct fix ("Add a landmark near 23 m", or "Add a
 * cue near 23 m" for a level change), sorted by penalty size descending
 * (ties by distance ascending), capped at three. A node that is both a bare
 * turn and an ambiguous clash costs both penalties but asks for the same
 * landmark once.
 *
 * NOTE: the plan stores this in `perception.health` on save, but W10-05
 * (the Bundle v2 freeze that introduces `EchoRoute.perception`) is still
 * queued — there is no perception field to write yet. The save wiring
 * belongs to the consumer slice once W10-05 lands; this slice exposes the
 * pure result only.
 */
const val FULL_HEALTH_SCORE = 100
const val LONG_LEG_PENALTY = 15
const val BARE_TURN_PENALTY = 10
const val AMBIGUOUS_TURN_PENALTY = 10
const val LEVEL_CUE_PENALTY = 5
const val BARE_DESTINATION_PENALTY = 20

const val LONG_LEG_M = 20.0
const val AMBIGUOUS_WINDOW_M = 6.0
const val MAX_HEALTH_NUDGES = 3

const val BAND_READY = "Ready to share"
const val BAND_FOLLOWABLE = "Followable — could be clearer"
const val BAND_ADD_LANDMARKS = "Add landmarks first"

/** Pure result of [routeHealth]: score 0..100, display band, ≤3 nudges. */
data class HealthResult(
    val score: Int,
    val band: String,
    val nudges: List<String>,
)

/** Scores [route] per the W12-07 penalty table. Deterministic, total. */
fun routeHealth(route: EchoRoute): HealthResult {
    val byId = route.nodes.associateBy { it.id }

    data class Hit(val penalty: Int, val atDistanceM: Double, val fix: String = "landmark")

    val hits = mutableListOf<Hit>()

    for (leg in route.legs) {
        if (leg.lengthM > LONG_LEG_M) {
            val from = byId[leg.fromNode]
            val to = byId[leg.toNode]
            val anchored = from?.landmarkIds?.isNotEmpty() == true ||
                to?.landmarkIds?.isNotEmpty() == true
            if (!anchored) {
                val mid = when {
                    from != null && to != null -> (from.atDistanceM + to.atDistanceM) / 2.0
                    from != null -> from.atDistanceM
                    to != null -> to.atDistanceM
                    else -> 0.0
                }
                hits += Hit(LONG_LEG_PENALTY, mid)
            }
        }
    }

    val turns = route.nodes.filter { it.type == NodeType.TURN }
    for (node in turns) {
        if (node.landmarkIds.isEmpty()) {
            hits += Hit(BARE_TURN_PENALTY, node.atDistanceM)
        }
    }
    for (node in turns) {
        val dir = node.turn ?: continue
        if (node.landmarkIds.isNotEmpty()) continue
        val clash = route.nodes.any { other ->
            other.id != node.id &&
                other.turn == dir &&
                other.landmarkIds.isEmpty() &&
                abs(other.atDistanceM - node.atDistanceM) <= AMBIGUOUS_WINDOW_M
        }
        if (clash) {
            hits += Hit(AMBIGUOUS_TURN_PENALTY, node.atDistanceM)
        }
    }

    for (node in route.nodes) {
        if (node.type == NodeType.LEVEL_CHANGE && node.cue.isBlank()) {
            hits += Hit(LEVEL_CUE_PENALTY, node.atDistanceM, fix = "cue")
        }
    }

    for (node in route.nodes) {
        if (node.type == NodeType.DESTINATION &&
            node.landmarkIds.isEmpty() &&
            node.label.isBlank()
        ) {
            hits += Hit(BARE_DESTINATION_PENALTY, node.atDistanceM)
        }
    }

    val score = max(0, FULL_HEALTH_SCORE - hits.sumOf { it.penalty })
    val band = when {
        score >= 80 -> BAND_READY
        score >= 50 -> BAND_FOLLOWABLE
        else -> BAND_ADD_LANDMARKS
    }
    val nudges = hits
        .sortedWith(compareByDescending<Hit> { it.penalty }.thenBy { it.atDistanceM })
        .map { it.fix to it.atDistanceM.roundToInt() }
        .distinct()
        .take(MAX_HEALTH_NUDGES)
        .map { (fix, metres) -> "Add a $fix near $metres m" }
    return HealthResult(score, band, nudges)
}
