package app.simt.echo.media

import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import okio.Path

/**
 * Normalised crop region in [0, 1]²; corners may exceed the bounds before
 * [padBox] clamps them.
 */
data class CropBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * One node's keyframe plan: the video timestamps to extract and which
 * index is the hero (the frame the AI pipeline receives).
 */
data class KeyframeSpec(
    val nodeId: String,
    val timesMs: List<Long>,
    val heroIndex: Int,
) {
    /** `kf/<nodeId>_<i>.jpg` (plan W1-05). */
    fun fileName(index: Int): String = "kf/${nodeId}_$index.jpg"
}

/**
 * Media seam (plan W1-05): frame extraction and cropping against the
 * captured video. The Android actual uses [android.media.MediaMetadataRetriever];
 * tests drive the pure helpers below and a fake [MediaTools].
 */
interface MediaTools {
    /**
     * Extract one frame from [video] at [atMs] milliseconds, rotated to
     * displayed orientation, scaled to ≤ [maxSide] px, written as JPEG
     * quality 82 to [out]. [atMs] is clamped to `[0, duration − 100]`.
     */
    suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path)

    /**
     * Crop [box] from [input] (clamped, padded 8 %), scale to [maxSide],
     * write JPEG quality 85 to [out].
     */
    suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path)
}

// ── Pure keyframe helpers (tested in commonTest) ──────────────────────────

/**
 * Video timestamps for [node]'s keyframes (plan W1-05):
 * TURN → `t−2000, t−500, t+1500`; CHECKPOINT/LEVEL/DESTINATION → `t−1500, t`.
 * START uses `start.jpg` and has no video keyframes.
 */
fun keyframeTimes(node: RouteNode): List<Long> {
    val t = node.captureTMs ?: return emptyList()
    return when (node.type) {
        NodeType.START -> emptyList()
        NodeType.TURN -> listOf(t - 2000L, t - 500L, t + 1500L)
        else -> listOf(t - 1500L, t)
    }
}

/**
 * Hero index: 1 for TURN (the middle frame), last for all others.
 * Zero when [count] is zero.
 */
fun heroIndex(nodeType: NodeType, count: Int): Int = when {
    count <= 0 -> 0
    nodeType == NodeType.TURN -> 1.coerceAtMost(count - 1)
    else -> count - 1
}

/**
 * Builds the [KeyframeSpec] for every node in [nodes]. START nodes have
 * an empty [KeyframeSpec.timesMs] (they use `start.jpg`).
 */
fun keyframeSpecs(nodes: List<RouteNode>): List<KeyframeSpec> = nodes.map { node ->
    val times = keyframeTimes(node)
    KeyframeSpec(
        nodeId = node.id,
        timesMs = times,
        heroIndex = heroIndex(node.type, times.size),
    )
}

/**
 * Clamps [atMs] to `[0, durationMs − 100]`; a non-positive duration
 * yields zero (the only safe frame).
 */
fun clampFrameTime(atMs: Long, durationMs: Long): Long =
    if (durationMs <= 0L) 0L else atMs.coerceIn(0L, durationMs - 100L)

/**
 * Pads [box] by [padFraction] on every side, then clamps to `[0, 1]`.
 * The pad widens the crop so a tight detection box keeps context.
 */
fun padBox(box: CropBox, padFraction: Float = 0.08f): CropBox {
    val w = box.right - box.left
    val h = box.bottom - box.top
    return CropBox(
        left = (box.left - w * padFraction).coerceIn(0f, 1f),
        top = (box.top - h * padFraction).coerceIn(0f, 1f),
        right = (box.right + w * padFraction).coerceIn(0f, 1f),
        bottom = (box.bottom + h * padFraction).coerceIn(0f, 1f),
    )
}
