package app.simt.echo.media

import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import androidx.compose.ui.geometry.Rect
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
 *
 * Threading (W10-02 G1): every suspend function confines its work off the
 * caller's dispatcher — decode and file IO on IO, bitmap scaling on Default.
 * Callers (e.g. `viewModelScope` on Main) must never need their own
 * `withContext` to stay off the main thread.
 */
interface MediaTools {
    /**
     * Extract one frame from [video] at [atMs] milliseconds, rotated to
     * displayed orientation, scaled to ≤ [maxSide] px, written as JPEG
     * quality 82 to [out]. [atMs] is clamped to `[0, duration − 100]`.
     */
    suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path)

    /**
     * Extracts every frame in [frames] from [video]. The default loops over
     * [extractFrame]; the Android actual opens one retriever for the whole
     * batch instead of one per frame (W10-02 G1).
     */
    suspend fun extractFrames(video: Path, frames: List<FrameRequest>) {
        for (frame in frames) extractFrame(video, frame.atMs, frame.maxSide, frame.out)
    }

    /**
     * Video duration in milliseconds, or null when unknown. A metadata
     * probe only — never decodes frames (W10-02 G5: clip ends clamp to this).
     */
    suspend fun videoDurationMs(video: Path): Long? = null

    /**
     * Crop [box] from [input] (clamped, padded 8 %), scale to [maxSide],
     * write JPEG quality 85 to [out].
     */
    suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path)

    /**
     * Cut the silent H.264 clip `[startMs, endMs]` from [video] at ≤ [maxFps]
     * fps into [out] (plan W3-01). Returns the clip's metadata once the
     * export is final.
     */
    suspend fun cutClip(video: Path, startMs: Long, endMs: Long, maxFps: Int, out: Path): ClipInfo

    /**
     * Whether the image-sequence strip forge (plan W11-03) is available.
     * False on platforms without Media3 Transformer — callers fall back to
     * [transcodeStripClip] via [selectForgeSource].
     */
    val supportsStripForge: Boolean
        get() = false

    /**
     * Forges one clip from ordered strip JPEGs ([CLIP_FORGE_FPS] fps,
     * 480 px short side, ~600 kbps) into [out]. Output frame `i` is
     * `frames[i]`, so [buildFrameMap] over the same entries is the exact
     * sidecar. Only called when [supportsStripForge] is true; the default
     * fails loudly so a platform gap surfaces instead of a silent empty clip.
     */
    suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo =
        error("Strip forge is Android-only in v1")

    /**
     * Fallback forge (plan W11-03): transcodes `[startMs, endMs]` from
     * [video] at [CLIP_FORGE_FPS] fps, 480 px short side, into [out].
     * The default delegates to [cutClip] at the forge frame rate, so every
     * platform with clip cutting gets a working (if not 480p-downscaled)
     * fallback; Android overrides it with the Presentation + FrameDropEffect
     * path.
     */
    suspend fun transcodeStripClip(video: Path, startMs: Long, endMs: Long, out: Path): ClipInfo =
        cutClip(video, startMs, endMs, CLIP_FORGE_FPS, out)
}

/** One frame extraction inside an [MediaTools.extractFrames] batch. */
data class FrameRequest(
    val atMs: Long,
    val maxSide: Int,
    val out: Path,
)

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

/**
 * Maps a canvas-pixel [rect] onto the unit square of a [width] ×
 * [height] px canvas (the review draw overlay's drag). Inverted drags
 * (right→left, bottom→top) come back unrotated, and edges are clamped
 * to `[0, 1]`, so a drag that extends past the canvas yields a
 * full-frame crop. A zero-size canvas measures as 1 px — the overlay's
 * confirm button is disabled until the canvas lays out anyway.
 */
fun cropBoxFromRect(rect: Rect, width: Float, height: Float): CropBox {
    val w = width.coerceAtLeast(1f)
    val h = height.coerceAtLeast(1f)
    return CropBox(
        left = (minOf(rect.left, rect.right) / w).coerceIn(0f, 1f),
        top = (minOf(rect.top, rect.bottom) / h).coerceIn(0f, 1f),
        right = (maxOf(rect.left, rect.right) / w).coerceIn(0f, 1f),
        bottom = (maxOf(rect.top, rect.bottom) / h).coerceIn(0f, 1f),
    )
}
