package app.simt.echo.transfer

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hash-first upload + event-stream seam (plan W11-08, worker API v1.1
 * freeze W10-06). The legacy multipart `submit` stays on [CloudApi] for
 * v1 servers; [V11CloudApi] adds the resumable path:
 * `POST /v1/uploads`, `PUT` only `missing[]`, then job create with
 * `idempotencyKey = sha256(manifest)`.
 */

/** Fallback poll gaps once the SSE stream fails: 2 → 4 → 8 → 16 s. */
val FALLBACK_POLL_DELAYS_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L, 16_000L)

/**
 * Per-file PUT deadline: 30 s floor plus 1 s per 100 KB chunk
 * (ceil), so a 480 p strip and a multi-MB clip each get a budget
 * sized to the bytes. Everything else keeps the 20 s client default
 * in the platform `HttpClients`.
 */
fun uploadTimeoutMs(bytes: Long): Long {
    val size = maxOf(0L, bytes)
    val chunks = (size + 102_400L - 1L) / 102_400L
    return maxOf(30_000L, 30_000L + chunks * 1_000L)
}

/** One file declared at upload creation: name + content hash + size. */
data class UploadFileRef(
    val name: String,
    val sha256: String,
    val bytes: Long,
)

/** Answer to `POST /v1/uploads` / `GET /v1/uploads/:id`. */
data class CreatedUpload(
    val uploadId: String,
    val missing: List<String>,
)

/**
 * Persisted hash-first upload session (`upload.json`, plan W11-08): the
 * server's upload id plus the exact manifest it was created for, so a
 * restart resumes with `PUT missing[]` instead of re-uploading. Cleared
 * when the job is created; the manifest bytes are stored verbatim so the
 * resumed create sends the identical `idempotencyKey`.
 */
@Serializable
data class UploadRecord(
    val uploadId: String,
    val manifestJson: String,
    val manifestSha256: String,
    val updatedAtMs: Long,
)

/**
 * v1.1 capability over [CloudApi]. The production [EchoApiClient]
 * implements it; scripted fakes that only know the legacy `submit`
 * keep working through the legacy path in the ViewModel.
 */
interface V11CloudApi : CloudApi {
    /** Declares file hashes; returns the upload id + files still missing. */
    suspend fun createUpload(files: List<UploadFileRef>): CreatedUpload

    /** Files the server does not hold yet (resume probe). */
    suspend fun uploadMissing(uploadId: String): List<String>

    /**
     * PUTs one file's bytes with a per-request deadline of
     * [uploadTimeoutMs]. A hash mismatch surfaces as
     * [EchoApiError.UploadStale] so the caller restarts the session.
     */
    suspend fun putUploadFile(uploadId: String, file: UploadFile)

    /** Creates the job against staged blobs; repeats are idempotent. */
    suspend fun createJob(manifestJson: String, uploadId: String, idempotencyKey: String): String

    /**
     * Deletes a walk-staged upload session (plan W11-09 discard).
     * Unknown or expired ids are already gone — never fatal.
     */
    suspend fun deleteUpload(uploadId: String)

    /**
     * Creates the job against already-staged blobs from several
     * per-window upload sessions (plan W11-09 Finish): no PUT, only
     * the create. Repeats are idempotent on [idempotencyKey].
     */
    suspend fun createJobFromStaged(manifestJson: String, uploadIds: List<String>, idempotencyKey: String): String

    /** Cold SSE stream of job events from `lastEventId` (null = from head). */
    fun watchEvents(jobId: String, lastEventId: String?): Flow<JobEvent>
}

/** One v1.1 job event (frozen `JobEvent` union, W10-06). */
sealed interface JobEvent {
    val id: String

    data class Stage(override val id: String, val stage: JobStage) : JobEvent

    /**
     * Partial result for one checkpoint. Candidates ride as the raw
     * JSON array so this module never depends on the AI merge layer;
     * the ViewModel decodes them into `AiCandidate`s.
     */
    data class Checkpoint(
        override val id: String,
        val nodeId: String,
        val status: String,
        val candidatesRaw: String,
    ) : JobEvent

    /**
     * Metered cost so far (frozen W10-06 `usage` shape,
     * `worker/src/types.ts` `UsageEventSchema`). The wire object is
     * closed but every field is optional — absent when SAM never ran —
     * so each payload field is nullable here too. The Processing
     * constellation (W13-06) reads the frame fields for its budget
     * meter; anything else ignores them.
     */
    data class Usage(
        override val id: String,
        val samFrames: Int? = null,
        val samFramesBilled: Int? = null,
        val budgetExhausted: Boolean = false,
    ) : JobEvent

    data class Complete(override val id: String, val resultRaw: String?) : JobEvent

    data class Error(override val id: String, val message: String) : JobEvent

    data class Unknown(override val id: String) : JobEvent
}

private val eventJson = Json { ignoreUnknownKeys = true }

/**
 * Parses one `text/event-stream` body into job events. Frames are
 * blank-line separated `id:`/`data:` blocks; `[DONE]` and malformed
 * frames are skipped, never fatal. Pure for deterministic tests.
 */
fun parseSseEvents(body: String): List<JobEvent> {
    val events = mutableListOf<JobEvent>()
    val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
    var frameId: String? = null
    val dataLines = mutableListOf<String>()
    fun dispatch() {
        if (dataLines.isEmpty()) {
            frameId = null
            return
        }
        val data = dataLines.joinToString("\n")
        dataLines.clear()
        val id = frameId
        frameId = null
        if (data == "[DONE]") return
        parseEventData(id, data)?.let(events::add)
    }
    for (line in normalized.split('\n')) {
        if (line.isBlank()) {
            dispatch()
            continue
        }
        if (line.startsWith(":")) continue
        when {
            line.startsWith("id:") -> frameId = line.removePrefix("id:").trim().ifEmpty { null }
            line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            line.startsWith("event:") -> Unit
        }
    }
    dispatch()
    return events
}

private fun parseEventData(frameId: String?, data: String): JobEvent? {
    val obj = runCatching { eventJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    fun str(key: String): String? = obj[key]?.jsonPrimitive?.takeIf { it.isString }?.content
    val id = frameId ?: str("id") ?: return null
    return when (str("type")) {
        "stage" -> JobEvent.Stage(id, JobStage.fromServer(str("stage")))
        "checkpoint" -> {
            val nodeId = str("nodeId") ?: return null
            val raw = runCatching { obj["candidates"]?.jsonArray?.toString() }.getOrNull() ?: "[]"
            JobEvent.Checkpoint(id, nodeId, str("status") ?: "", raw)
        }
        "usage" -> {
            val uso = obj["usage"]?.jsonObjectOrNull()
            JobEvent.Usage(
                id,
                samFrames = uso?.intOrNull("samFrames"),
                samFramesBilled = uso?.intOrNull("samFramesBilled"),
                budgetExhausted = uso?.boolOrNull("budgetExhausted") ?: false,
            )
        }
        "complete" -> JobEvent.Complete(id, obj["result"]?.toString())
        "error" -> JobEvent.Error(id, str("error") ?: "unknown")
        else -> JobEvent.Unknown(id)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun JsonObject.intOrNull(key: String): Int? =
    runCatching { get(key)?.jsonPrimitive?.intOrNull }.getOrNull()?.takeIf { it >= 0 }

private fun JsonObject.boolOrNull(key: String): Boolean? =
    runCatching { get(key)?.jsonPrimitive?.booleanOrNull }.getOrNull()
