package app.simt.echo.capture

import app.simt.echo.model.NodeType

/**
 * Approach strips and ledger keyframes (plan W11-02).
 *
 * After segmentation, each checkpoint at distance `d` gets an approach
 * strip: up to [APPROACH_MAX_FRAMES] ledger frames from the window
 * `[d − 8 m, d + 1 m]`, chosen by score with at least
 * [APPROACH_MIN_SPACING_M] between kept frames, returned time-ordered.
 * Keyframes are distance-based picks from the strip — nearest to
 * `d − 4 m`, `d − 1 m`, `d + 0.75 m` for TURN, `d − 3 m`, `d` for other
 * node types — replacing timestamp seeks into the video. Video stays the
 * creator's private record and the fallback source when the draft has no
 * ledger. The start photo is the best-scoring frame at `d ≤ 1 m`.
 *
 * S-reviewed storage choice (what lands in `kf/` — explicit so review
 * can audit it):
 * 1. Window first: a frame outside `[d − 8, d + 1]` is never stored for
 *    the node, however sharp.
 * 2. Score wins ties over proximity: the greedy pass keeps the highest
 *    score and drops anything nearer than [APPROACH_MIN_SPACING_M] to an
 *    already-kept frame, so a sharp frame displaces a blurry neighbour.
 * 3. Cap drops the lowest scores beyond [APPROACH_MAX_FRAMES].
 * 4. Metadata-only entries (`jpegPath == null`) carry no bytes and are
 *    excluded from the strip — nothing is stored for a frame with no file.
 * 5. The start photo fills a missing `start.jpg` only; an existing
 *    creator capture is never overwritten (see `ensureKeyframes`).
 * 6. Every slot the ledger cannot fill falls back to video extraction.
 *
 * Pure and deterministic: no coroutines, no platform imports, no RNG.
 * Consumes the W11-01 [GlimpseLedger] output; the FrameTap seam
 * ([FrameTap], [FrameSample]) is frozen and untouched here.
 */

/** Metres behind the checkpoint the approach window reaches. */
const val APPROACH_WINDOW_BEHIND_M = 8.0

/** Metres past the checkpoint the approach window reaches. */
const val APPROACH_WINDOW_AHEAD_M = 1.0

/** At most this many frames per approach strip (plan W11-02). */
const val APPROACH_MAX_FRAMES = 12

/** Minimum odometer spacing between kept strip frames, metres. */
const val APPROACH_MIN_SPACING_M = 0.4

/** Start-photo rule: only frames at or below this distance qualify. */
const val START_PHOTO_MAX_D_M = 1.0

/** TURN keyframe offsets from the node distance, metres (plan W11-02). */
val TURN_KEYFRAME_OFFSETS_M = listOf(-4.0, -1.0, 0.75)

/** Non-TURN keyframe offsets from the node distance, metres. */
val OTHER_KEYFRAME_OFFSETS_M = listOf(-3.0, 0.0)

/** Distance offsets picked from the strip for [nodeType]. */
fun keyframeOffsets(nodeType: NodeType): List<Double> =
    if (nodeType == NodeType.TURN) TURN_KEYFRAME_OFFSETS_M else OTHER_KEYFRAME_OFFSETS_M

/**
 * The approach strip for a checkpoint at distance [d]: ledger entries in
 * `[d − 8, d + 1]` (inclusive), greedily picked by score — highest first,
 * skipping anything within [APPROACH_MIN_SPACING_M] of an already-kept
 * frame — capped at [APPROACH_MAX_FRAMES], returned time-ordered
 * (`tNs` ascending).
 *
 * Ties break deterministically (lower odometer, then earlier frame), so
 * the same ledger always yields the same strip. Empty when the window
 * holds no stored frame; callers fall back to video then.
 */
fun approachStrip(
    entries: List<GlimpseEntry>,
    d: Double,
    windowBehindM: Double = APPROACH_WINDOW_BEHIND_M,
    windowAheadM: Double = APPROACH_WINDOW_AHEAD_M,
    maxFrames: Int = APPROACH_MAX_FRAMES,
    minSpacingM: Double = APPROACH_MIN_SPACING_M,
): List<GlimpseEntry> {
    val lo = d - windowBehindM
    val hi = d + windowAheadM
    val inWindow = entries.filter { it.jpegPath != null && it.odometerM in lo..hi }
    val ranked = inWindow.sortedWith(
        compareByDescending<GlimpseEntry> { it.score }
            .thenBy { it.odometerM }
            .thenBy { it.tNs },
    )
    val kept = mutableListOf<GlimpseEntry>()
    for (candidate in ranked) {
        if (kept.size >= maxFrames) break
        if (kept.any { kotlin.math.abs(it.odometerM - candidate.odometerM) < minSpacingM }) continue
        kept += candidate
    }
    return kept.sortedBy { it.tNs }
}

/**
 * Keyframe picks from [strip] for a node of [nodeType] at distance [d]:
 * the strip entry nearest each of [keyframeOffsets] (3 for TURN, 2 for
 * others), in offset order. A nearest-entry tie prefers the higher score,
 * then the earlier frame. One entry never fills two slots (first offset
 * wins), so a thin strip yields fewer picks and the caller falls back to
 * video for the rest. Empty strip → empty picks.
 */
fun ledgerKeyframes(
    strip: List<GlimpseEntry>,
    nodeType: NodeType,
    d: Double,
): List<GlimpseEntry> {
    if (strip.isEmpty()) return emptyList()
    val used = mutableSetOf<GlimpseEntry>()
    return buildList {
        for (offset in keyframeOffsets(nodeType)) {
            val target = d + offset
            val pick = strip
                .filterNot { it in used }
                .minWithOrNull(
                    compareBy<GlimpseEntry> { kotlin.math.abs(it.odometerM - target) }
                        .thenByDescending { it.score }
                        .thenBy { it.tNs },
                ) ?: break
            used += pick
            add(pick)
        }
    }
}

/**
 * Start-photo rule: the best-scoring stored frame at `d ≤ 1 m`, or null
 * when the ledger holds none. Score ties prefer the earlier frame,
 * matching the ledger's first-kept-wins discipline.
 */
fun startPhotoEntry(
    entries: List<GlimpseEntry>,
    maxDM: Double = START_PHOTO_MAX_D_M,
): GlimpseEntry? {
    val candidates = entries.filter { it.jpegPath != null && it.odometerM <= maxDM }
    if (candidates.isEmpty()) return null
    val top = candidates.maxOf { it.score }
    return candidates.filter { it.score == top }.minByOrNull { it.tNs }
}
