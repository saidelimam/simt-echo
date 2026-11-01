package app.simt.echo.ai

import kotlinx.serialization.Serializable

/**
 * Local-model seam (plan §7.5 / W5-01).
 *
 * One interface for every local provider — an OpenAI-compatible LAN
 * server today, the on-phone LiteRT engine behind the same shape —
 * so `AiRouter` and Review never branch on which model ran. Providers
 * return the same [AiCandidate] the worker emits (boxes in the
 * 0–1000 grid frame, `source = AI_BOX_ONLY` semantics applied by the
 * router), and failures are typed [LocalModelException]s, never raw
 * model text leaking into the UI.
 */
interface LocalVision {
    /** Model identifier, e.g. `muse-glimmer-30b` or `gemma-4-e2b (on phone)`. */
    val modelId: String

    /** Proposes up to 3 landmarks for one checkpoint's hero keyframe. */
    suspend fun propose(checkpoint: LocalCheckpoint): List<AiCandidate>
}

/** One checkpoint handed to a local provider: sensor-measured context
 * plus the hero keyframe bytes (the only image a local call sends). */
data class LocalCheckpoint(
    val nodeId: String,
    /** The node's keyframe id (the candidate's `keyframeId`). */
    val keyframeId: String,
    /** Sensor-measured context line; directions/distances are facts, not hints. */
    val context: String,
    val heroJpeg: ByteArray,
)

/** Typed local-model failure: unavailable, unreachable, or unparseable. */
class LocalModelException(message: String) : Exception(message)

/** The shared landmark prompt (worker §7.1, single-checkpoint form). */
const val LOCAL_SYSTEM_PROMPT: String =
    "You help pedestrians follow an indoor route recorded by another person. " +
        "You get one photo taken while walking toward a checkpoint. The travel direction " +
        "is MEASURED BY SENSORS and given to you; never infer or change directions or " +
        "distances. Propose up to 3 landmarks a stranger can spot from several metres away. " +
        "Prefer permanent structure: pillars, doors, elevators, stairs, ramps, signs, painted " +
        "floor markings, pipes, fire equipment, distinctive walls. Never use vehicles, people, " +
        "faces, license plates or temporary objects. samPhrase: 1-4 word concrete noun phrase " +
        "with colour/material, no positions or verbs. box: [x1,y1,x2,y2] on a 0-1000 grid of " +
        "the image. distinctiveness: how unique it is along this route. permanence: 1 = " +
        "building structure, 0 = movable. Copy legible sign text exactly into signText, else " +
        "null. Set privacyRisk true if the crop would show people, faces, plates or screens. " +
        "cue: one short instruction combining the given direction and the landmark. If nothing " +
        "suitable is visible, return an empty candidates list. Answer with JSON only."

/** First balanced `{…}` block in [text] (models wrap JSON in prose). */
fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            c == '\\' && inString -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' -> depth++
            c == '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

/** Parses one checkpoint reply into a DTO, or throws typed. */
fun parseLocalCheckpoint(text: String): LocalCheckpointDto {
    val json = extractJsonObject(text)
        ?: throw LocalModelException("Local model did not answer with JSON.")
    return runCatching {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<LocalCheckpointDto>(json)
    }.getOrElse {
        throw LocalModelException("Local model JSON did not match the landmark schema.")
    }
}

/** One candidate as a local model returns it (worker SCHEMA candidate). */
@Serializable
data class LocalCandidateDto(
    val title: String = "",
    val samPhrase: String = "",
    val description: String = "",
    val box: List<Int> = emptyList(),
    val distinctiveness: Double = 0.0,
    val permanence: Double = 0.0,
    val signText: String? = null,
    val ambiguous: Boolean = false,
    val privacyRisk: Boolean = false,
    val cue: String = "",
)

@Serializable
data class LocalCheckpointDto(
    val nodeId: String = "",
    val candidates: List<LocalCandidateDto> = emptyList(),
)

private fun clampBox(box: List<Int>): List<Int> {
    val xs = listOf(box[0].coerceIn(0, 1000), box[2].coerceIn(0, 1000)).sorted()
    val ys = listOf(box[1].coerceIn(0, 1000), box[3].coerceIn(0, 1000)).sorted()
    return listOf(xs[0], ys[0], xs[1], ys[1])
}

/**
 * Shapes raw local candidates exactly like the worker pipeline does:
 * malformed boxes dropped, boxes clamped to the grid with inverted
 * edges fixed, top 2 by distinctiveness·permanence. Pure and shared by
 * every provider, so local output is indistinguishable downstream.
 */
fun cleanLocalCandidates(
    checkpoint: LocalCheckpoint,
    modelId: String,
    raw: List<LocalCandidateDto>,
): List<AiCandidate> =
    raw.filter { it.box.size == 4 && it.title.isNotEmpty() }
        .map { dto ->
            AiCandidate(
                candidateId = "${checkpoint.nodeId}_local",
                title = dto.title,
                description = dto.description,
                cue = dto.cue,
                samPhrase = dto.samPhrase,
                signText = dto.signText,
                privacyRisk = dto.privacyRisk,
                keyframeId = checkpoint.keyframeId,
                box = clampBox(dto.box),
                frameW = 1000,
                frameH = 1000,
                distinctiveness = dto.distinctiveness.coerceIn(0.0, 1.0),
                permanence = dto.permanence.coerceIn(0.0, 1.0),
                rank = dto.distinctiveness * dto.permanence,
                source = "AI_BOX_ONLY",
                models = "$modelId (local)",
            )
        }
        .sortedByDescending { it.rank }
        .take(2)
        .mapIndexed { index, candidate -> candidate.copy(candidateId = "${checkpoint.nodeId}_local_$index") }
