package app.simt.echo.media

import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.capture.isFaceFree
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import kotlinx.serialization.Serializable
import okio.Path
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Silent video clip metadata (implementation plan W3-01). [fps] is derived:
 * `frameCount * 1000 / durationMs`.
 */
data class ClipInfo(
    val path: Path,
    val frameCount: Int,
    val durationMs: Long,
) {
    val fps: Double = if (durationMs > 0L) frameCount * 1000.0 / durationMs else 0.0
}

/**
 * Manifest seam (plan W3-01, shape §5.5): the deterministic JSON the app
 * posts to `POST /v1/jobs` alongside keyframes and clips. Built from frozen
 * shapes only — geometry and node identity come from the sensor-owned
 * [EchoRoute]; nothing here may mutate them.
 */

@Serializable
data class ManifestConsent(
    val sendFramesToCloudAi: Boolean,
    val atEpochMs: Long,
)

/** Per-frame quality flag the worker understands: only `"bad"` drops the frame. */
const val FRAME_QUALITY_GOOD = "good"

/** Per-frame quality flag the worker understands: `"bad"` drops the frame before SAM spend. */
const val FRAME_QUALITY_BAD = "bad"

/**
 * Per-frame upload flags (plan W11-04), keyed by keyframe id. The worker
 * (`samLens.frameFailsFlags`) drops a candidate before any SAM spend when
 * `faceFree == false`, `quality == "bad"`, or `exposureOk == false` —
 * keeps it when the flags are absent. Defaults are therefore keep: a
 * keyframe with no scan behind it (e.g. video fallback) uploads exactly
 * as before, and only a frame the on-device detector actually failed is
 * flagged.
 */
data class FrameFlags(
    val quality: String = FRAME_QUALITY_GOOD,
    val faceFree: Boolean = true,
    val exposureOk: Boolean = true,
)

@Serializable
data class ManifestKeyframe(
    val id: String,
    val tMs: Long,
    /** [FRAME_QUALITY_GOOD] unless the frame carries no usable signal. */
    val quality: String = FRAME_QUALITY_GOOD,
    /** False when the on-device scan found (or could not rule out) a face. */
    val faceFree: Boolean = true,
    /** False when the frame failed the on-device exposure gate. */
    val exposureOk: Boolean = true,
)

@Serializable
data class ManifestClip(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val frameCount: Int,
    val durationMs: Long,
)

@Serializable
data class ManifestApproachSample(
    val tMs: Long,
    val d: Double,
)

@Serializable
data class ManifestCheckpoint(
    val nodeId: String,
    val type: String,
    val turn: String?,
    val atDistanceM: Double,
    val tMs: Long,
    val heroKeyframeId: String,
    val keyframes: List<ManifestKeyframe>,
    val clip: ManifestClip?,
    val approach: List<ManifestApproachSample>,
)

/** The §5.5 manifest body; the worker re-parses and validates it with zod. */
@Serializable
data class Manifest(
    val schema: Int = 1,
    val draftId: String,
    val consent: ManifestConsent,
    val routeContext: String,
    val checkpoints: List<ManifestCheckpoint>,
)

/** Upload field id for one planned keyframe (the W1-05 file minus ext, flattened). */
fun keyframeId(spec: KeyframeSpec, index: Int): String =
    spec.fileName(index).removeSuffix(".jpg").replace('/', '_')

/** Upload field id + draft-relative file for every planned keyframe. */
fun keyframeFiles(specs: List<KeyframeSpec>): Map<String, String> =
    specs.flatMap { spec ->
        spec.timesMs.indices.map { i -> keyframeId(spec, i) to spec.fileName(i) }
    }.toMap()

/** Upload field id for one node's clip. */
fun clipId(nodeId: String): String = "clip_$nodeId"

/** Draft-relative file holding one node's cut clip. */
fun clipFileName(nodeId: String): String = "kf/${clipId(nodeId)}.mp4"

/**
 * One node's clip window: `[t − 4000, t + 1000]` (plan W3-01), clamped to
 * `[0, durationMs]` when a positive [durationMs] is known. A 5 s window at
 * ≤ 10 fps is ≈ 50 frames.
 */
data class ClipWindow(
    val startMs: Long,
    val endMs: Long,
)

fun clipWindow(tMs: Long, durationMs: Long? = null): ClipWindow {
    val start = (tMs - 4_000L).coerceAtLeast(0L)
    var end = tMs + 1_000L
    if (durationMs != null && durationMs > 0L) end = end.coerceAtMost(durationMs)
    if (end < start) end = start
    return ClipWindow(start, end)
}

/**
 * Human route summary for the AI: "Indoor walk, <len> m, <turns> turns,
 * <levels> level changes" (plan W3-01). Length is rounded to whole metres.
 */
fun routeContext(route: EchoRoute): String {
    val len = route.totalLengthM.roundToInt()
    val turns = route.nodes.count { it.type == NodeType.TURN }
    val levels = route.nodes.count { it.type == NodeType.LEVEL_CHANGE }
    return "Indoor walk, $len m, $turns turns, $levels level changes"
}

/**
 * Approach samples: [CaptureLog] distance interpolated at `videoStartNs + tMs`
 * every 500 ms across [window] (plan W3-01). Times are video-relative
 * milliseconds; distances are metres. Empty when the log has no samples.
 */
fun approachSamples(log: CaptureLog, window: ClipWindow, stepMs: Long = 500L): List<ManifestApproachSample> {
    if (log.samples.isEmpty() || window.endMs <= window.startMs) return emptyList()
    val samples = log.samples.sortedBy { it.tNs }
    val out = mutableListOf<ManifestApproachSample>()
    var t = window.startMs
    while (t <= window.endMs) {
        out += ManifestApproachSample(t, distanceAt(log, samples, t))
        t += stepMs
    }
    return out
}

/** Linear interpolation of [CaptureLog] distance at video-relative [tMs]. */
internal fun distanceAt(log: CaptureLog, samples: List<CaptureSample>, tMs: Long): Double {
    val absNs = log.videoStartNs + tMs * 1_000_000L
    if (absNs <= samples.first().tNs) return samples.first().d
    if (absNs >= samples.last().tNs) return samples.last().d
    var i = 0
    while (i < samples.size - 1 && samples[i + 1].tNs < absNs) i++
    val a = samples[i]
    val b = samples[i + 1]
    val spanNs = b.tNs - a.tNs
    if (spanNs <= 0L) return a.d
    val f = (absNs - a.tNs).toDouble() / spanNs
    return a.d + (b.d - a.d) * f
}

/**
 * Builds the §5.5 [Manifest] for one draft. Checkpoints are all non-START
 * nodes with a capture time; [clips] maps `nodeId` → [ForgedClip] (the clip
 * and the window it covers) and is empty when SAM is disabled (then `clip` is
 * omitted, plan W3-01). [frameFlags]
 * carries the W11-04 per-frame flags by keyframe id; ids absent from the
 * map keep the keep-defaults (today's manifests behave exactly as before).
 * Keyframes flagged `faceFree = false` are dropped from their checkpoint
 * (the hero moves to the nearest kept slot); a checkpoint left with no
 * keyframe is dropped whole, clip included.
 */
object ManifestBuilder {

    fun build(
        route: EchoRoute,
        log: CaptureLog,
        specs: List<KeyframeSpec>,
        clips: Map<String, ForgedClip>,
        consent: ManifestConsent,
        frameFlags: Map<String, FrameFlags> = emptyMap(),
    ): Manifest {
        val specByNode = specs.associateBy { it.nodeId }
        val checkpoints = route.nodes.mapNotNull { node ->
            checkpoint(node, specByNode[node.id], clips, log, frameFlags)
        }
        return Manifest(
            draftId = log.draftId,
            consent = consent,
            routeContext = routeContext(route),
            checkpoints = checkpoints,
        )
    }

    private fun checkpoint(
        node: RouteNode,
        spec: KeyframeSpec?,
        clips: Map<String, ForgedClip>,
        log: CaptureLog,
        frameFlags: Map<String, FrameFlags>,
    ): ManifestCheckpoint? {
        val t = node.captureTMs ?: return null
        if (node.type == NodeType.START) return null
        val spec = spec ?: KeyframeSpec(node.id, keyframeTimes(node), heroIndex(node.type, 0))
        // Manifest keyframe ids are the W1-05 file names minus the extension
        // and with the directory flattened (see [keyframeId]); the worker
        // maps them to uploaded files by id.
        // A keyframe flagged `faceFree = false` is withheld from the upload,
        // so it must not be listed either: the worker rejects a manifest id
        // with no staged bytes (400 `upload_incomplete`).
        val kept = spec.timesMs.indices.filter { i -> (frameFlags[keyframeId(spec, i)] ?: FrameFlags()).faceFree }
        if (kept.isEmpty()) return null
        val keyframes = kept.map { i ->
            val flags = frameFlags[keyframeId(spec, i)] ?: FrameFlags()
            ManifestKeyframe(keyframeId(spec, i), spec.timesMs[i], flags.quality, flags.faceFree, flags.exposureOk)
        }
        val heroId = keyframeId(spec, kept.minBy { abs(it - spec.heroIndex) })
        // The clip and its approach samples describe the window actually
        // forged, not the planned t-4000..t+1000 one.
        val forged = clips[node.id]
        val window = forged?.window ?: clipWindow(t)
        val clip = forged?.let {
            ManifestClip(
                id = clipId(node.id),
                startMs = window.startMs,
                endMs = window.endMs,
                frameCount = it.info.frameCount,
                durationMs = it.info.durationMs,
            )
        }
        return ManifestCheckpoint(
            nodeId = node.id,
            type = node.type.name,
            turn = node.turn?.name,
            atDistanceM = node.atDistanceM,
            tMs = t,
            heroKeyframeId = heroId,
            keyframes = keyframes,
            clip = clip,
            approach = approachSamples(log, window),
        )
    }
}

/**
 * Quality flag for one ledger frame score: [FRAME_QUALITY_BAD] when the
 * score is zero or negative, [FRAME_QUALITY_GOOD] otherwise. The W11-01
 * score is exactly 0.0 when the exposure gate fails (or for a degenerate
 * uniform plane); either way the frame carries no usable signal, so the
 * worker should spend nothing on it.
 */
fun qualityFor(score: Double): String =
    if (score <= 0.0) FRAME_QUALITY_BAD else FRAME_QUALITY_GOOD

/**
 * W11-04 per-frame flags for the upload: for every non-START node, the
 * approach strip is reduced to ledger keyframe slots ([ledgerKeyframes] —
 * the same picks `ensureKeyframes` copies into `kf/`, in the same order),
 * and each pick is flagged by its score and verdict. The map is keyed by
 * manifest keyframe id, for [ManifestBuilder.build]'s [FrameFlags]
 * parameter.
 *
 * The strip is deliberately built *unfiltered* (no [FaceDetector] passed
 * to [approachStrip]) so the picks line up 1:1 with the `kf/` bytes
 * `ensureKeyframes` wrote: a face pick flagged here is the same file the
 * upload path withholds and the worker drops. A scanned UNKNOWN verdict
 * flags `faceFree = false` (exclude-safe); a zero score flags
 * `quality = "bad"` and `exposureOk = false`. Slots the ledger cannot
 * fill stay absent from this map; the upload path layers
 * [failClosedFrameFlags] over it so an unscanned slot is withheld, not kept.
 */
fun frameFlagsFor(
    route: EchoRoute,
    specs: List<KeyframeSpec>,
    ledger: List<GlimpseEntry>,
    detector: FaceDetector,
): Map<String, FrameFlags> {
    val specByNode = specs.associateBy { it.nodeId }
    return buildMap {
        for (node in route.nodes) {
            if (node.type == NodeType.START) continue
            val spec = specByNode[node.id] ?: continue
            val picks = ledgerPicksFor(ledger, node)
            picks.forEachIndexed { index, pick ->
                if (index >= spec.timesMs.size) return@forEachIndexed
                val verdict = detector.verdictFor(pick)
                put(
                    keyframeId(spec, index),
                    FrameFlags(
                        quality = qualityFor(pick.score),
                        faceFree = verdict.isFaceFree(),
                        exposureOk = pick.score > 0.0,
                    ),
                )
            }
        }
    }
}
