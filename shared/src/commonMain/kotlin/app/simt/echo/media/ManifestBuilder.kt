package app.simt.echo.media

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import kotlinx.serialization.Serializable
import okio.Path
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

@Serializable
data class ManifestKeyframe(
    val id: String,
    val tMs: Long,
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
 * nodes with a capture time; [clips] maps `nodeId` → [ClipInfo] and is empty
 * when SAM is disabled (then `clip` is omitted, plan W3-01).
 */
object ManifestBuilder {

    fun build(
        route: EchoRoute,
        log: CaptureLog,
        specs: List<KeyframeSpec>,
        clips: Map<String, ClipInfo>,
        consent: ManifestConsent,
    ): Manifest {
        val specByNode = specs.associateBy { it.nodeId }
        val checkpoints = route.nodes.mapNotNull { node ->
            checkpoint(node, specByNode[node.id], clips, log)
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
        clips: Map<String, ClipInfo>,
        log: CaptureLog,
    ): ManifestCheckpoint? {
        val t = node.captureTMs ?: return null
        if (node.type == NodeType.START) return null
        val spec = spec ?: KeyframeSpec(node.id, keyframeTimes(node), heroIndex(node.type, 0))
        // Manifest keyframe ids are the W1-05 file names minus the extension
        // and with the directory flattened (`kf_<nodeId>_<i>`); the worker
        // maps them to uploaded files by id.
        val keyframes = spec.timesMs.mapIndexed { i, tMs ->
            ManifestKeyframe(spec.fileName(i).removeSuffix(".jpg").replace('/', '_'), tMs)
        }
        val clip = clips[node.id]?.let { info ->
            val window = clipWindow(t)
            ManifestClip(
                id = "clip_${node.id}",
                startMs = window.startMs,
                endMs = window.endMs,
                frameCount = info.frameCount,
                durationMs = info.durationMs,
            )
        }
        return ManifestCheckpoint(
            nodeId = node.id,
            type = node.type.name,
            turn = node.turn?.name,
            atDistanceM = node.atDistanceM,
            tMs = t,
            heroKeyframeId = if (spec.heroIndex < keyframes.size) keyframes[spec.heroIndex].id else keyframes.last().id,
            keyframes = keyframes,
            clip = clip,
            approach = approachSamples(log, clipWindow(t)),
        )
    }
}
