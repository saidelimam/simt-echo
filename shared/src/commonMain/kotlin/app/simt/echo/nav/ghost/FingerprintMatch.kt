package app.simt.echo.nav.ghost

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.EmbeddedPrint
import kotlin.io.encoding.Base64
import kotlin.math.sqrt

/**
 * Visual-fingerprint matching (plan W12-05): cosine similarity of a live
 * int8[256] query against the route's stored prints, gated twice —
 * identity gate (same `embedderId` + projection-seed version, so a model
 * swap never cross-matches stale prints) and distance gate (only prints
 * within [matchRadiusM] of the filter mean, i.e. s ± (3σ + 5 m)).
 *
 * A match ≥ [COSINE_TAU] is a low-weight [Observation.Fingerprint] plus a
 * user-confirm chip ([FingerprintPrompt.chipCopy], e.g. "That's the Red
 * pillar?") and a soft haptic — and nothing else. Confirmation comes only
 * from the user ([isConfirmedBy] with `userConfirmed`) or a consistent
 * turn event (`consistentTurnEvent`): **never a silent auto-confirm**.
 * The pipeline physically cannot confirm on match alone — there is no
 * code path from [matchFingerprints] to an anchor.
 *
 * Privacy: candidates come from [RoutePerception][app.simt.echo.model.RoutePerception]
 * (bundle body) only. Nothing here references `transfer/`.
 */
object FingerprintMatch {

    /**
     * Cosine threshold τ for a fingerprint match.
     *
     * TBD-tuning note: the plan bar is precision ≥ 0.95 at τ on the
     * recorded pair set (same spot, creator + follower phones, different
     * light); 0.82 is the starting value until the benchmark table in
     * [FingerprintCapture] is measured.
     */
    const val COSINE_TAU = 0.82

    /** Slack beyond 3σ in the candidate window, metres. */
    const val WINDOW_SLACK_M = 5.0

    /** Candidate half-window around the filter mean: 3σ + 5 m. */
    fun matchRadiusM(sigma: Double): Double = 3.0 * sigma + WINDOW_SLACK_M

    /** Cosine similarity of two int8 vectors in [−1, 1]; 0 when degenerate. */
    fun cosineSimilarity(a: ByteArray, b: ByteArray): Double {
        require(a.size == EmbeddedPrint.PRINT_BYTES && b.size == EmbeddedPrint.PRINT_BYTES) {
            "fingerprint cosine: ${a.size} vs ${b.size} bytes, need ${EmbeddedPrint.PRINT_BYTES}"
        }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return (dot / sqrt(na * nb)).coerceIn(-1.0, 1.0)
    }

    /**
     * Best in-window, identity-gated candidate at cosine ≥ [COSINE_TAU],
     * or null. Ties prefer the nearer candidate, then the higher
     * similarity, so the same inputs always yield the same hit.
     */
    fun matchFingerprints(
        query: ByteArray,
        queryEmbedderId: String,
        querySeedVersion: Int,
        meanS: Double,
        sigma: Double,
        candidates: List<FingerprintCandidate>,
        tau: Double = COSINE_TAU,
    ): FingerprintHit? {
        require(query.size == EmbeddedPrint.PRINT_BYTES) {
            "fingerprint query: ${query.size} bytes, need ${EmbeddedPrint.PRINT_BYTES}"
        }
        val radius = matchRadiusM(sigma)
        return candidates
            .asSequence()
            .filter { it.embedderId == queryEmbedderId && it.projectionSeedVersion == querySeedVersion }
            .filter { kotlin.math.abs(it.atDistanceM - meanS) <= radius }
            .mapNotNull { candidate ->
                val sim = cosineSimilarity(query, candidate.vector)
                if (sim >= tau) FingerprintHit(candidate.atDistanceM, sim, candidate.label) else null
            }
            .minWithOrNull(
                compareBy<FingerprintHit> { kotlin.math.abs(it.nodeS - meanS) }
                    .thenByDescending { it.similarity01 },
            )
    }

    /**
     * The confirm rule, as a function so tests pin it: a fingerprint hit
     * confirms only by explicit user tap or by a consistent turn event.
     * `false, false` — a bare match — never confirms.
     */
    fun isConfirmedBy(userConfirmed: Boolean, consistentTurnEvent: Boolean): Boolean =
        userConfirmed || consistentTurnEvent

    /**
     * Matchable candidates for a route: node prints at their node
     * distance plus waypoint prints at theirs. Labels prefer the
     * node's landmark title (the chip copy), else the node label.
     */
    fun candidatesFor(route: EchoRoute): List<FingerprintCandidate> {
        val perception = route.perception ?: return emptyList()
        val nodeSById = route.nodes.associate { it.id to it.atDistanceM }
        val landmarkTitleByNode = route.landmarks
            .filter { it.title.isNotBlank() }
            .associate { it.nodeId to it.title }
        val out = mutableListOf<FingerprintCandidate>()
        for (nodePrint in perception.fingerprints) {
            val s = nodeSById[nodePrint.nodeId] ?: continue
            val node = route.nodes.firstOrNull { it.id == nodePrint.nodeId }
            val label = landmarkTitleByNode[nodePrint.nodeId]
                ?: node?.label?.takeIf { it.isNotBlank() }
                ?: "landmark"
            for (print in nodePrint.prints) {
                out += FingerprintCandidate(s, print.embedderId, print.projectionSeedVersion, decode(print), label)
            }
        }
        for (waypoint in perception.waypointPrints) {
            out += FingerprintCandidate(
                atDistanceM = waypoint.atDistanceM,
                embedderId = waypoint.print.embedderId,
                projectionSeedVersion = waypoint.print.projectionSeedVersion,
                vector = decode(waypoint.print),
                label = "landmark",
            )
        }
        return out
    }

    /** Decodes one stored print to its int8[256] vector. */
    fun decode(print: EmbeddedPrint): ByteArray = Base64.decode(print.dataB64)
}

/** One stored print positioned along the route, ready to match. */
data class FingerprintCandidate(
    val atDistanceM: Double,
    val embedderId: String,
    val projectionSeedVersion: Int,
    val vector: ByteArray,
    val label: String,
)

/** A gated match: where it fires and how strongly. */
data class FingerprintHit(
    val nodeS: Double,
    val similarity01: Double,
    val label: String,
)

/**
 * Minimal user-confirm chip state (W12-05 hook; the full W13-08
 * instrument renders it). The chip asks, never asserts — confirmation
 * still flows through [FingerprintMatch.isConfirmedBy].
 */
data class FingerprintPrompt(
    val label: String,
    val nodeS: Double,
) {
    /** e.g. "That's the Red pillar?" */
    fun chipCopy(): String = "That's the $label?"
}
