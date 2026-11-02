package app.simt.echo.ui.screens.processing

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.transfer.SAM_FRAME_BUDGET

/**
 * Processing "Constellation" state (plan W13-06): the route drawn as a
 * constellation, one star per checkpoint, driven ONLY by the frozen
 * W10-06 job events the ViewModel consumes — never by timers, progress
 * interpolation, or stage inference.
 *
 * Honest light: a star reflects the last-seen `checkpoint` event for its
 * node and nothing else. `stage`, `complete`, `error`, `usage` and
 * `unknown` events never move a star; `complete`/`error` only change the
 * [ProcessingPhase]. A restart resumes at QUEUED until live events land
 * again — the server replays from `Last-Event-ID`, so already-seen
 * checkpoints are not re-announced.
 */

/** One checkpoint's star. Shape + label always differ; hue never alone. */
enum class StarState {
    /** No checkpoint event yet: small dim dot, labelled "Queued". */
    QUEUED,

    /** `scouted`: four-point sparkle, labelled "Scouting", twinkles on FULL. */
    SCOUTING,

    /** `grounded`: bright disc with a mask-outline ring, labelled "Grounded". */
    GROUNDED,

    /** `skipped`: hollow ring, labelled "Skipped". */
    SKIPPED,
}

/** Screen word for a star state — the label half of the encoding. */
fun starStateLabel(state: StarState): String = when (state) {
    StarState.QUEUED -> "Queued"
    StarState.SCOUTING -> "Scouting"
    StarState.GROUNDED -> "Grounded"
    StarState.SKIPPED -> "Skipped"
}

/**
 * Maps a frozen checkpoint `status` (`worker/src/types.ts`
 * `CheckpointEventStatusSchema`: scouted | grounded | skipped) to its
 * star state. Anything else — including a missing status — is null, and
 * the caller leaves the star untouched.
 */
fun starStateForStatus(status: String): StarState? = when (status) {
    "scouted" -> StarState.SCOUTING
    "grounded" -> StarState.GROUNDED
    "skipped" -> StarState.SKIPPED
    else -> null
}

/** One plotted star: unit-space position plus its live state. */
data class ConstellationStar(
    val nodeId: String,
    val label: String,
    val x01: Float,
    val y01: Float,
    val state: StarState,
)

/** Design frame budget from the plan's cost gate (≤ 120 billed frames). */
const val CONSTELLATION_FRAME_BUDGET = SAM_FRAME_BUDGET

/** Meter text, e.g. "38 / 120 frames". [used] is max-seen billed frames. */
fun constellationBudgetText(used: Int, budgetExhausted: Boolean = false): String {
    val base = "$used / $CONSTELLATION_FRAME_BUDGET frames"
    return if (budgetExhausted) "$base · frame limit reached" else base
}

/** Everything the constellation view renders. Empty stars = nothing drawn. */
data class ConstellationUiState(
    val stars: List<ConstellationStar> = emptyList(),
    val framesUsed: Int = 0,
    val budgetExhausted: Boolean = false,
)

/**
 * Builds the star skeleton for [route] in node order: every checkpoint
 * (non-START node with a capture time — the same predicate the upload
 * allow-list uses) placed by interpolating the recorded path at its
 * distance, normalised to unit space. Unknown nodes in [states] stay
 * QUEUED.
 */
fun constellationStars(
    route: EchoRoute,
    states: Map<String, StarState> = emptyMap(),
): List<ConstellationStar> {
    val checkpoints = route.nodes.filter { it.type != NodeType.START && it.captureTMs != null }
    if (checkpoints.isEmpty()) return emptyList()
    val positions = interpolatePath(route)
    return checkpoints.map { node ->
        val (x, y) = positions[node.atDistanceM] ?: (0.5f to 0.5f)
        ConstellationStar(
            nodeId = node.id,
            label = node.label.ifEmpty { node.id },
            x01 = x,
            y01 = y,
            state = states[node.id] ?: StarState.QUEUED,
        )
    }
}

/** Interpolates the route path at each checkpoint distance, unit-space. */
private fun interpolatePath(route: EchoRoute): Map<Double, Pair<Float, Float>> {
    val path = route.path
    if (path.isEmpty()) return emptyMap()
    val xs = path.map { it.x }
    val ys = path.map { it.y }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    fun normX(x: Double): Float =
        if (maxX > minX) ((x - minX) / (maxX - minX)).toFloat() else 0.5f
    fun normY(y: Double): Float =
        if (maxY > minY) ((y - minY) / (maxY - minY)).toFloat() else 0.5f
    val sorted = path.sortedBy { it.d }
    return route.nodes.associate { node ->
        val d = node.atDistanceM
        val at = when {
            d <= sorted.first().d -> sorted.first().x to sorted.first().y
            d >= sorted.last().d -> sorted.last().x to sorted.last().y
            else -> {
                val hi = sorted.first { it.d >= d }
                val lo = sorted.last { it.d <= d }
                if (hi.d > lo.d) {
                    val f = (d - lo.d) / (hi.d - lo.d)
                    (lo.x + (hi.x - lo.x) * f) to (lo.y + (hi.y - lo.y) * f)
                } else {
                    hi.x to hi.y
                }
            }
        }
        node.atDistanceM to (normX(at.first) to normY(at.second))
    }
}
