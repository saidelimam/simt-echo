package app.simt.echo.capture

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode

/**
 * Metres a walker must have covered past a checkpoint before it is final
 * (plan W11-09). Three things settle in that distance: the turn detector's
 * confirm-and-close lag, a mark that may still merge into a turn
 * ([EchoConfig.markMergeDistanceM]), and the approach window that reaches
 * [APPROACH_WINDOW_AHEAD_M] past the node, so its ledger strip is complete.
 * Pinned by `LiveCheckpointsTest`, which sweeps prefixes against the final route.
 */
const val LIVE_CLOSE_MARGIN_M = 6.0

/** The segmentation of a walk in progress and the checkpoints in it that can no longer change. */
data class LiveCheckpoints(
    /** The route segmented from the log so far; its DESTINATION is just where the walker stands. */
    val route: EchoRoute,
    /** Checkpoints identical, id and distance included, in the route the finished walk will produce. */
    val closed: List<RouteNode>,
)

/**
 * Segments a walk in progress and reports which checkpoints are final.
 *
 * The segmenter is the one source of node identity (ids follow distance
 * order), so the live trigger runs it on the log so far instead of
 * re-deriving turns: the answer for a prefix is trusted only up to the
 * newest real node (turn, mark) at least [LIVE_CLOSE_MARGIN_M] behind the
 * walker. Everything up to and including it is final, level changes between
 * final nodes included; a level change after it waits, because it sits
 * halfway to a node that does not exist yet. START and DESTINATION are never
 * reported: the first needs no strip, the second moves with every step.
 */
fun liveCheckpoints(log: CaptureLog, cfg: EchoConfig): LiveCheckpoints {
    val route = segment(log, cfg, log.draftId, 0L)
    val horizon = (log.samples.lastOrNull()?.d ?: return LiveCheckpoints(route, emptyList())) - LIVE_CLOSE_MARGIN_M
    val anchor = route.nodes.lastOrNull { node ->
        node.type != NodeType.LEVEL_CHANGE && node.type != NodeType.DESTINATION && node.atDistanceM <= horizon
    } ?: return LiveCheckpoints(route, emptyList())
    val closed = route.nodes.filter { node ->
        node.type != NodeType.START && node.type != NodeType.DESTINATION && node.atDistanceM <= anchor.atDistanceM
    }
    return LiveCheckpoints(route, closed)
}
