package app.simt.echo.ui.screens.review

import app.simt.echo.ai.AiCandidate
import app.simt.echo.model.EchoJson
import app.simt.echo.model.PathPoint
import kotlin.math.abs

/**
 * Review "Route story" pure model (plan W13-05).
 *
 * The film strip is distance-ordered ledger frames: scrubbing it moves a
 * single selection ([ReviewUiState.selectedNodeId]) that the minimap
 * cursor and the node timeline both read, so the three stay linked
 * through one value. Health nudges ("Add a landmark near 23 m") parse
 * back to a distance and jump to the nearest node.
 *
 * Creator-verification invariant: everything here is geometry-free. The
 * partial-checkpoint merge returns suggestion maps only — nodes, legs
 * and path are never in its signature, so AI arrivals cannot move them.
 *
 * V bindings (N/A in CI — no device): the film-strip scrub drives the
 * minimap cursor; the mask trace runs [MASK_TRACE_MS]; the inspector
 * shared-element key is `reviewKeyframe:<nodeId>`; nudge buttons jump
 * the selection. Capture on-device per plan §9.
 */

/** Landmark-mask trace-in duration (luminous-freeze §4 "trace"). */
const val MASK_TRACE_MS = 600L

/** One film-strip cell: a node keyframe ordered by walked distance. */
data class FilmFrame(
    val nodeId: String,
    val atDistanceM: Double,
    val label: String,
)

/** Distance-ordered frames for the strip (stable for ties). */
fun filmStripFrames(nodes: List<ReviewNode>): List<FilmFrame> =
    nodes.map { FilmFrame(it.node.id, it.node.atDistanceM, it.node.label) }
        .sortedBy { it.atDistanceM }

/** Strip index of the selection; 0 when nothing is selected yet. */
fun scrubSelectedIndex(frames: List<FilmFrame>, selectedNodeId: String?): Int {
    if (frames.isEmpty()) return 0
    if (selectedNodeId == null) return 0
    return frames.indexOfFirst { it.nodeId == selectedNodeId }.coerceAtLeast(0)
}

/** Path-point index nearest to [distanceM]: the minimap cursor. */
fun pathIndexForDistance(path: List<PathPoint>, distanceM: Double): Int {
    if (path.isEmpty()) return 0
    var best = 0
    var bestGap = abs(path[0].d - distanceM)
    for (i in 1 until path.size) {
        val gap = abs(path[i].d - distanceM)
        if (gap < bestGap) {
            bestGap = gap
            best = i
        }
    }
    return best
}

/** Nearest node id to [distanceM], or null when there are no nodes. */
fun nearestNodeId(nodes: List<ReviewNode>, distanceM: Double): String? =
    nodes.minByOrNull { abs(it.node.atDistanceM - distanceM) }?.node?.id

private val NUDGE_DISTANCE = Regex("""near (\d+) m""")

/**
 * Jump target for a health nudge: parses the "near XX m" distance and
 * returns the nearest node id. Null when the text carries no distance
 * or there are no nodes — the caller keeps the current selection.
 */
fun nudgeTargetNodeId(nodes: List<ReviewNode>, nudge: String): String? {
    val distance = NUDGE_DISTANCE.find(nudge)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        ?: return null
    return nearestNodeId(nodes, distance)
}

/**
 * Null-safe decode of a `checkpoint` event's candidate array (plan
 * W11-08 partials flow): null, blank or garbled payloads yield an empty
 * list, never a throw — the event is skipped, the stream continues.
 */
fun parseCheckpointCandidates(raw: String?): List<AiCandidate> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { EchoJson.decodeFromString<List<AiCandidate>>(raw) }.getOrNull()
        ?: emptyList()
}

/**
 * Merges one partial arrival into the suggestion map. Same shape rules
 * as [app.simt.echo.ai.CandidateMerger.merge] — unknown nodes dropped,
 * malformed boxes dropped, `privacyRisk` never listed, rank-descending —
 * applied incrementally: newcomers append (deduped by candidate id) so
 * the creator's current card position survives later arrivals.
 */
fun mergePartialSuggestions(
    existing: Map<String, List<AiCandidate>>,
    nodeId: String,
    incoming: List<AiCandidate>,
    knownIds: Set<String>,
): Map<String, List<AiCandidate>> {
    if (nodeId !in knownIds) return existing
    val fresh = incoming
        .filter { it.box.size == 4 && it.box[2] > it.box[0] && it.box[3] > it.box[1] }
        .filter { !it.privacyRisk }
        .filter { it.candidateId.isNotEmpty() }
    if (fresh.isEmpty()) return existing
    val seen = existing[nodeId].orEmpty().map { it.candidateId }.toSet()
    val combined = (existing[nodeId].orEmpty() + fresh.filter { it.candidateId !in seen })
        .sortedByDescending { it.rank }
    if (combined.isEmpty()) return existing
    return existing + (nodeId to combined)
}
