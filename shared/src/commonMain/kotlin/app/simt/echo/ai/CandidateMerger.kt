package app.simt.echo.ai

import app.simt.echo.media.CropBox
import app.simt.echo.model.Confirmation
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.LandmarkSource
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * One worker candidate (frozen `result.json` shape, plan §5.5). Parsed with
 * [EchoJson] (`ignoreUnknownKeys`), so the worker may add fields without
 * breaking Review. [box] is the 0–1000 grid of a [frameW]×[frameH] frame;
 * [source] stays a raw string so an unknown future source degrades to
 * box-only instead of failing the whole parse.
 */
@Serializable
data class AiCandidate(
    val candidateId: String = "",
    val title: String = "",
    val description: String = "",
    val cue: String = "",
    val samPhrase: String = "",
    val signText: String? = null,
    val privacyRisk: Boolean = false,
    val keyframeId: String = "",
    val box: List<Int> = emptyList(),
    val frameW: Int = 1000,
    val frameH: Int = 1000,
    val maskSvgPath: String? = null,
    val maskW: Int? = null,
    val maskH: Int? = null,
    val samConfidence: Double? = null,
    val trackability: Double? = null,
    val visibleFromM: Double? = null,
    val distinctiveness: Double = 0.0,
    val permanence: Double = 0.0,
    val rank: Double = 0.0,
    val source: String = "AI_BOX_ONLY",
    val models: String = "",
)

@Serializable
data class AiCheckpoint(
    val nodeId: String = "",
    val candidates: List<AiCandidate> = emptyList(),
)

@Serializable
data class AiModels(
    val scout: String = "",
    val ground: String = "",
)

/** Whole worker result (plan §5.5 `result.json`). */
@Serializable
data class AiJobResult(
    val jobId: String = "",
    val models: AiModels = AiModels(),
    val checkpoints: List<AiCheckpoint> = emptyList(),
)

/**
 * Merge worker candidates into Review (plan W3-07). Pure: parsing and
 * ranking here, persistence in [app.simt.echo.ui.screens.review.ReviewViewModel].
 *
 * The merge never touches geometry — it returns a side map of node id to
 * ranked candidates and leaves the [EchoRoute] alone. Nodes, legs and path
 * are sensor-owned (plan §0 rule); a candidate for an unknown node id is
 * dropped, never attached. Accepted candidates become [Confirmation] —
 * see `ReviewViewModel.useSuggestion`; unreviewed ones are never exported
 * because they never enter the route.
 */
object CandidateMerger {
    /** Parses raw `result.json`; null when it is not a result (fail-closed). */
    fun parseResult(raw: String): AiJobResult? =
        runCatching { EchoJson.decodeFromString<AiJobResult>(raw) }
            .getOrNull()
            ?.takeIf { it.jobId.isNotEmpty() }

    /**
     * Node id → candidates, rank-descending. Drops candidates for unknown
     * nodes and malformed boxes; nodes without candidates are absent.
     */
    fun merge(route: EchoRoute, result: AiJobResult): Map<String, List<AiCandidate>> {
        val known = route.nodes.map { it.id }.toSet()
        return result.checkpoints
            .filter { it.nodeId in known }
            .associate { checkpoint ->
                checkpoint.nodeId to checkpoint.candidates
                    .filter { it.box.size == 4 }
                    .sortedByDescending { it.rank }
            }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Candidate box → normalised [CropBox] for [MediaTools.cropJpeg].
     * Converts through pixel space (`kfW/frameW`, `kfH/frameH` — never
     * assumes keyframe and SAM frame share a size), then normalises by the
     * keyframe dims and clamps to 0..1.
     */
    fun candidateCropBox(candidate: AiCandidate, kfW: Int, kfH: Int): CropBox {
        val frameW = candidate.frameW.coerceAtLeast(1)
        val frameH = candidate.frameH.coerceAtLeast(1)
        val width = kfW.coerceAtLeast(1).toFloat()
        val height = kfH.coerceAtLeast(1).toFloat()
        // Pixel roundtrip, written out so the size mapping stays explicit:
        // frame px → keyframe px → normalised.
        fun norm(grid: Int, frame: Int, kf: Float, dim: Float): Float {
            val framePx = grid / 1000f * frame
            val kfPx = framePx * kf / frame
            return (kfPx / dim).coerceIn(0f, 1f)
        }
        val l = norm(candidate.box[0], frameW, width, width)
        val t = norm(candidate.box[1], frameH, height, height)
        val r = norm(candidate.box[2], frameW, width, width)
        val b = norm(candidate.box[3], frameH, height, height)
        return CropBox(
            left = minOf(l, r),
            top = minOf(t, b),
            right = maxOf(l, r),
            bottom = maxOf(t, b),
        )
    }

    /** Worker source → landmark source; unknown degrades to box-only. */
    fun landmarkSourceOf(candidate: AiCandidate): LandmarkSource = when (candidate.source) {
        "AI_GROUNDED" -> LandmarkSource.AI_GROUNDED
        else -> LandmarkSource.AI_BOX_ONLY
    }

    /**
     * Stat chips, only when SAM ran (`SAM 0.86 · tracked 78% · visible ~9 m`).
     * Null on the box-only path — the card shows no chips then.
     */
    fun statLine(candidate: AiCandidate): String? {
        val confidence = candidate.samConfidence ?: return null
        val parts = mutableListOf("SAM ${format2(confidence)}")
        candidate.trackability?.let { parts += "tracked ${(it * 100).toInt()}%" }
        candidate.visibleFromM?.let { parts += "visible ~${it.toInt()} m" }
        return parts.joinToString(" · ")
    }

    /** Provenance footnote: the models that ran (`Spark + SAM 3.1`). */
    fun modelsLine(result: AiJobResult): String =
        listOf(result.models.scout, result.models.ground)
            .filter { it.isNotEmpty() && it != "none" }
            .joinToString(" + ")
            .ifEmpty { "cloud AI" }

    private fun format2(value: Double): String =
        ((value * 100).roundToInt() / 100.0).toString()
}
