package app.simt.echo.transfer

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
) : CloudApi {
    private fun configured() {
        if (baseUrl.isBlank() || appToken.isBlank()) throw EchoApiError.NotConfigured()
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
        } catch (e: Exception) {
            false
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
