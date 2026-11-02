package app.simt.echo.transfer

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Progress stages the Processing screen renders (frozen worker contract). */
enum class JobStage {
    QUEUED,
    READING,
    SCOUTING,
    GROUNDING,
    COMPLETE,
    ERRORED,
    ;

    companion object {
        fun fromServer(raw: String?): JobStage = when (raw) {
            "reading" -> READING
            "scouting" -> SCOUTING
            "grounding" -> GROUNDING
            "complete" -> COMPLETE
            "errored" -> ERRORED
            else -> QUEUED
        }
    }
}

/** One poll answer: lifecycle status, renderable stage, optional payloads. */
data class JobPoll(
    val status: String,
    val stage: JobStage,
    /** Raw `result` JSON when complete; parsed by W3-07 (owns candidates). */
    val resultRaw: String? = null,
    val error: String? = null,
)

/** One file in the multipart upload: allow-listed id + bytes + mime. */
data class UploadFile(
    /** Must equal the manifest's keyframe/clip id (server allow-list). */
    val id: String,
    val mime: String,
    val bytes: ByteArray,
)

/** Typed client failures: callers map these to Retry / Continue without AI. */
sealed class EchoApiError(message: String) : Exception(message) {
    class ConsentRequired : EchoApiError("Consent is required before upload.")
    class InvalidManifest(val detail: String) : EchoApiError("Invalid manifest: $detail")
    class Unauthorized : EchoApiError("Not authorized by the ECHO server.")
    class RateLimited : EchoApiError("Too many requests. Try again in a minute.")
    class PayloadTooLarge : EchoApiError("Upload is larger than the server accepts.")
    /**
     * The hash-first upload session is unusable (expired, unknown, a file
     * outside the declaration, or bytes that miss their declared SHA-256).
     * Callers clear the persisted session and start a fresh one; the job
     * itself is never touched.
     */
    class UploadStale(val code: String) : EchoApiError("Upload session is stale: $code")
    class JobNotFound(val jobId: String) : EchoApiError("Job $jobId is gone.")
    class NotConfigured : EchoApiError("Cloud AI is not configured on this build.")
    class Server(val status: Int, val body: String) : EchoApiError("Server error $status.")
    class Network(cause: Throwable) : EchoApiError("Network error: ${cause.message}")
}

@Serializable
private data class SubmitResponse(val ok: Boolean = false, val jobId: String = "")

@Serializable
private data class ErrorResponse(val ok: Boolean = false, val error: String = "")

@Serializable
private data class PollResponse(
    val status: String = "queued",
    val stage: String? = null,
    val error: String? = null,
    val result: JsonElement? = null,
)

@Serializable
private data class HealthResponse(val ok: Boolean = false, val metaKeyConfigured: Boolean = false)

@Serializable
private data class UploadFileRefDto(val name: String, val sha256: String, val bytes: Long)

@Serializable
private data class CreateUploadRequest(val files: List<UploadFileRefDto>)

@Serializable
private data class CreateUploadResponse(
    val ok: Boolean = false,
    val uploadId: String = "",
    val missing: List<String> = emptyList(),
)

@Serializable
private data class UploadStatusResponse(
    val ok: Boolean = false,
    val uploadId: String = "",
    val missing: List<String> = emptyList(),
)

@Serializable
private data class CreateJobV11Response(val ok: Boolean = false, val jobId: String = "")

@Serializable
private data class JobBudgetDto(val maxSamFrames: Int)

@Serializable
private data class CreateJobV11Request(
    val manifest: JsonElement,
    val uploadId: String? = null,
    val uploadIds: List<String>? = null,
    val budget: JobBudgetDto,
    val idempotencyKey: String,
    val resumeOf: String? = null,
)

private val apiJson = Json { ignoreUnknownKeys = true }

/**
 * Narrow cloud contract the Processing flow needs (plan W3-02). The
 * production [EchoApiClient] implements it over Ktor; tests script a fake.
 * Depending on the seam (not the client) keeps orchestration tests free
 * of HTTP plumbing — the same split as `PeerTransport` behind
 * `TransferService`.
 */
interface CloudApi {
    /** POSTs the manifest + files. Returns the server's job id (202). */
    suspend fun submit(manifestJson: String, files: List<UploadFile>): String

    /** GETs one poll answer. */
    suspend fun get(jobId: String): JobPoll

    /** DELETEs a job's server files. 204 and 404 both mean gone. */
    suspend fun delete(jobId: String)

    /** Public probe: true when the server has a Meta key (SAM path). */
    suspend fun samEnabled(): Boolean
}

/**
 * Cloud API client (plan W3-02): multipart manifest+files upload, job
 * polling, deletion — all behind `Authorization: Bearer <appToken>`.
 * Pure orchestration over an injected [HttpClient]: [EchoApiClientTest]
 * drives it with a mock engine, production passes the platform engine.
 * Fail-closed: an empty token or base URL never sends a byte.
 */
class EchoApiClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val appToken: String,
) : CloudApi, V11CloudApi {
    private fun configured() {
        if (baseUrl.isBlank() || appToken.isBlank()) throw EchoApiError.NotConfigured()
        // The bearer token and the photos travel together: never in
        // cleartext. Loopback stays allowed for local worker development.
        if (!isAllowedBaseUrl(baseUrl)) throw EchoApiError.NotConfigured()
    }

    /**
     * POSTs the manifest JSON plus allow-listed files (`manifest` field,
     * then one field per file id). Returns the server's job id (202).
     */
    override suspend fun submit(manifestJson: String, files: List<UploadFile>): String {
        configured()
        val response = try {
            http.submitFormWithBinaryData(
                url = "$baseUrl/v1/jobs",
                formData = formData {
                    append(
                        "manifest",
                        manifestJson,
                        Headers.build { append(HttpHeaders.ContentType, "application/json") },
                    )
                    files.forEach { file ->
                        append(
                            file.id,
                            file.bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, file.mime)
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.id}\"")
                            },
                        )
                    }
                },
            ) {
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        if (response.status.value == 202) {
            val body = response.bodyAsText()
            val jobId = runCatching { apiJson.decodeFromString<SubmitResponse>(body).jobId }.getOrNull()
            if (jobId.isNullOrEmpty()) throw EchoApiError.Server(202, body)
            return jobId
        }
        throwSubmitError(response.status.value, response.bodyAsText())
    }

    /** GETs one poll answer. 404 surfaces as [EchoApiError.JobNotFound]. */
    override suspend fun get(jobId: String): JobPoll {
        configured()
        val response = try {
            http.get("$baseUrl/v1/jobs/$jobId") {
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        val body = response.bodyAsText()
        if (response.status.value == 404) throw EchoApiError.JobNotFound(jobId)
        if (response.status.value != 200) throw EchoApiError.Server(response.status.value, body)
        val poll = runCatching { apiJson.decodeFromString<PollResponse>(body) }.getOrNull()
            ?: throw EchoApiError.Server(200, body)
        return JobPoll(poll.status, JobStage.fromServer(poll.stage), poll.result?.toString(), poll.error)
    }

    /**
     * DELETEs a job's server files ("or now, from this screen"). 204 and
     * 404 both mean gone.
     */
    override suspend fun delete(jobId: String) {
        configured()
        val response = try {
            http.delete("$baseUrl/v1/jobs/$jobId") {
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        if (response.status.value != 204 && response.status.value != 404) {
            throw EchoApiError.Server(response.status.value, response.bodyAsText())
        }
    }

    /** Public liveness probe: true when the server has a Meta key (SAM path). */
    override suspend fun samEnabled(): Boolean {
        if (baseUrl.isBlank()) return false
        return try {
            val body = http.get("$baseUrl/v1/health").bodyAsText()
            runCatching { apiJson.decodeFromString<HealthResponse>(body).metaKeyConfigured }.getOrDefault(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Declares file hashes (`POST /v1/uploads`). Returns the upload id
     * plus the files the server still needs.
     */
    override suspend fun createUpload(files: List<UploadFileRef>): CreatedUpload {
        configured()
        val request = CreateUploadRequest(files.map { UploadFileRefDto(it.name, it.sha256, it.bytes) })
        val response = try {
            http.post("$baseUrl/v1/uploads") {
                contentType(ContentType.Application.Json)
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
                setBody(apiJson.encodeToString(CreateUploadRequest.serializer(), request))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        val body = response.bodyAsText()
        if (response.status.value != 200) throwUploadError(response.status.value, body)
        val parsed = runCatching { apiJson.decodeFromString<CreateUploadResponse>(body) }.getOrNull()
            ?: throw EchoApiError.Server(200, body)
        if (parsed.uploadId.isEmpty()) throw EchoApiError.Server(200, body)
        return CreatedUpload(parsed.uploadId, parsed.missing)
    }

    /** Files the server does not hold yet (`GET /v1/uploads/:id`). */
    override suspend fun uploadMissing(uploadId: String): List<String> {
        configured()
        val response = try {
            http.get("$baseUrl/v1/uploads/$uploadId") {
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        val body = response.bodyAsText()
        if (response.status.value != 200) throwUploadError(response.status.value, body)
        return runCatching { apiJson.decodeFromString<UploadStatusResponse>(body) }.getOrNull()?.missing
            ?: throw EchoApiError.Server(200, body)
    }

    /**
     * PUTs one file's raw bytes. The per-request deadline is sized to
     * the file ([uploadTimeoutMs]) via the platform client's `HttpTimeout`
     * plugin; the 20 s platform default still bounds every other call. A
     * hash mismatch means the bytes no longer match the declaration (e.g.
     * reforged after process death) and surfaces as
     * [EchoApiError.UploadStale].
     */
    override suspend fun putUploadFile(uploadId: String, file: UploadFile) {
        configured()
        val deadlineMs = uploadTimeoutMs(file.bytes.size.toLong())
        val response = try {
            http.put("$baseUrl/v1/uploads/$uploadId/${file.id}") {
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
                contentType(ContentType.parse(file.mime))
                setBody(file.bytes)
                timeout { requestTimeoutMillis = deadlineMs }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        if (response.status.value != 200) throwUploadError(response.status.value, response.bodyAsText())
    }

    /**
     * Creates the job against staged blobs (JSON `POST /v1/jobs` with
     * `uploadId` + `idempotencyKey`). A repeated create with the same
     * key returns the original job id without touching staging.
     */
    override suspend fun createJob(
        manifestJson: String,
        uploadId: String,
        idempotencyKey: String,
        resumeOf: String?,
    ): String {
        configured()
        val manifest = runCatching { apiJson.parseToJsonElement(manifestJson) }.getOrNull()
            ?: throw EchoApiError.InvalidManifest("unparseable manifest")
        val request = apiJson.encodeToString(
            CreateJobV11Request.serializer(),
            CreateJobV11Request(
                manifest,
                uploadId = uploadId,
                budget = JobBudgetDto(SAM_FRAME_BUDGET),
                idempotencyKey = idempotencyKey,
                resumeOf = resumeOf,
            ),
        )
        return postJobCreate(request)
    }

    /**
     * Deletes a walk-staged upload session (`DELETE /v1/uploads/:id`,
     * plan W11-09 discard). 204 and 404 both mean gone.
     */
    override suspend fun deleteUpload(uploadId: String) {
        configured()
        val response = try {
            http.delete("$baseUrl/v1/uploads/$uploadId") {
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        if (response.status.value != 204 && response.status.value != 404) {
            throw EchoApiError.Server(response.status.value, response.bodyAsText())
        }
    }

    /**
     * Creates the job against already-staged blobs from several
     * per-window upload sessions (JSON `POST /v1/jobs` with
     * `uploadIds` + `idempotencyKey`, plan W11-09 Finish).
     */
    override suspend fun createJobFromStaged(
        manifestJson: String,
        uploadIds: List<String>,
        idempotencyKey: String,
    ): String {
        configured()
        require(uploadIds.isNotEmpty()) { "createJobFromStaged: no staged uploads" }
        val manifest = runCatching { apiJson.parseToJsonElement(manifestJson) }.getOrNull()
            ?: throw EchoApiError.InvalidManifest("unparseable manifest")
        val request = apiJson.encodeToString(
            CreateJobV11Request.serializer(),
            CreateJobV11Request(
                manifest,
                uploadIds = uploadIds,
                budget = JobBudgetDto(SAM_FRAME_BUDGET),
                idempotencyKey = idempotencyKey,
            ),
        )
        return postJobCreate(request)
    }

    /**
     * One SSE read of `GET /v1/jobs/:id/events` from `lastEventId`
     * (`Last-Event-ID` header). Frames are parsed as they stream in, so a
     * checkpoint reaches the UI when the worker emits it, not when the
     * connection closes. The server holds the connection ~25 s; the read's
     * deadline is [EVENT_STREAM_TIMEOUT_MS] and the caller reconnects for
     * liveness, falling back to polling when the endpoint (or the network)
     * is down. Only a 404 with the worker's `job_not_found` body means the
     * job is gone; any other failure — including a bare 404 from a server
     * without the events route — is a stream failure, not a verdict on the
     * job.
     */
    override fun watchEvents(jobId: String, lastEventId: String?): Flow<JobEvent> = flow {
        configured()
        var downstream: Throwable? = null
        suspend fun deliver(frame: String) {
            for (event in parseSseEvents(frame)) {
                try {
                    emit(event)
                } catch (t: Throwable) {
                    downstream = t
                    throw t
                }
            }
        }
        try {
            http.prepareGet("$baseUrl/v1/jobs/$jobId/events") {
                headers.append(HttpHeaders.Accept, "text/event-stream")
                if (lastEventId != null) headers.append("Last-Event-ID", lastEventId)
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
                timeout { requestTimeoutMillis = EVENT_STREAM_TIMEOUT_MS }
            }.execute { response ->
                val status = response.status.value
                if (status != 200) {
                    val body = response.bodyAsText()
                    if (status == 404 && errorCode(body) == "job_not_found") throw EchoApiError.JobNotFound(jobId)
                    throw EchoApiError.Server(status, body)
                }
                val channel = response.bodyAsChannel()
                val frame = StringBuilder()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isNotEmpty()) {
                        frame.append(line).append('\n')
                    } else {
                        deliver(frame.toString())
                        frame.clear()
                    }
                }
                // A stream that closed mid-frame still delivers what it had.
                deliver(frame.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EchoApiError) {
            throw e
        } catch (e: Exception) {
            if (e === downstream) throw e
            throw EchoApiError.Network(e)
        }
    }

    private fun errorCode(body: String): String? =
        runCatching { apiJson.decodeFromString<ErrorResponse>(body).error }.getOrNull()

    private suspend fun postJobCreate(request: String): String {
        val response = try {
            http.post("$baseUrl/v1/jobs") {
                contentType(ContentType.Application.Json)
                headers.append(HttpHeaders.Authorization, "Bearer $appToken")
                setBody(request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EchoApiError.Network(e)
        }
        val body = response.bodyAsText()
        if (response.status.value != 202) throwJobCreateError(response.status.value, body)
        val jobId = runCatching { apiJson.decodeFromString<CreateJobV11Response>(body).jobId }.getOrNull()
        if (jobId.isNullOrEmpty()) throw EchoApiError.Server(202, body)
        return jobId
    }

    private fun isAllowedBaseUrl(url: String): Boolean {
        if (url.startsWith("https://")) return true
        if (!url.startsWith("http://")) return false
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
    }

    private fun throwUploadError(status: Int, body: String): Nothing {
        val code = runCatching { apiJson.decodeFromString<ErrorResponse>(body).error }.getOrNull()
        throw when {
            status == 401 -> EchoApiError.Unauthorized()
            status == 429 -> EchoApiError.RateLimited()
            status == 413 -> EchoApiError.PayloadTooLarge()
            code == "upload_expired" || code == "upload_not_found" ||
                code == "unknown_file" || code == "sha_mismatch" ||
                code == "upload_incomplete" -> EchoApiError.UploadStale(code ?: "unknown")
            code == "consent_required" -> EchoApiError.ConsentRequired()
            code == "invalid_manifest" || code == "invalid_request" -> EchoApiError.InvalidManifest(body)
            else -> EchoApiError.Server(status, body)
        }
    }

    private fun throwJobCreateError(status: Int, body: String): Nothing {
        val code = runCatching { apiJson.decodeFromString<ErrorResponse>(body).error }.getOrNull()
        throw when {
            status == 401 -> EchoApiError.Unauthorized()
            status == 429 -> EchoApiError.RateLimited()
            status == 413 -> EchoApiError.PayloadTooLarge()
            code == "upload_expired" || code == "upload_not_found" ||
                code == "upload_incomplete" -> EchoApiError.UploadStale(code ?: "unknown")
            code == "consent_required" -> EchoApiError.ConsentRequired()
            code == "invalid_manifest" || code == "invalid_request" -> EchoApiError.InvalidManifest(body)
            else -> EchoApiError.Server(status, body)
        }
    }

    private fun throwSubmitError(status: Int, body: String): Nothing {
        val code = runCatching { apiJson.decodeFromString<ErrorResponse>(body).error }.getOrNull()
        throw when {
            status == 401 -> EchoApiError.Unauthorized()
            status == 429 -> EchoApiError.RateLimited()
            status == 413 -> EchoApiError.PayloadTooLarge()
            code == "consent_required" -> EchoApiError.ConsentRequired()
            code == "invalid_manifest" -> EchoApiError.InvalidManifest(body)
            else -> EchoApiError.Server(status, body)
        }
    }
}
