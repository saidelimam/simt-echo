package app.simt.echo.transfer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Internet relay for sealed bundles (plan W4-05).
 *
 * Zero-knowledge by construction: implementations move the sealed `ECX1`
 * bytes only. The AES key never leaves the QR (`k=`); the worker stores
 * ciphertext under the returned code (`c=`) for 30 minutes.
 */
interface RelayClient {
    /** Uploads sealed bytes; returns the relay code for the QR. */
    suspend fun upload(sealed: ByteArray): String

    /** Downloads the sealed bytes stored under [code]. */
    suspend fun download(code: String): ByteArray

    /**
     * Additive byte-count progress (W13-14): same contract as
     * [app.simt.echo.platform.PeerTransport.progress] — uploads count
     * bytes sent, downloads bytes received; monotonic per transfer, ends
     * at total. Defaults to never emitting so fakes keep compiling.
     */
    val progress: Flow<app.simt.echo.platform.TransferProgress> get() = emptyFlow()
}

@Serializable
private data class RelayUploadResponse(val code: String, val expiresAt: Long)

/**
 * [RelayClient] over the Worker's `/v1/relay` endpoints.
 *
 * Endpoint and app token are injected — never hard-coded — so W3-02's
 * API configuration owns where these values come from; until then
 * [app.simt.echo.AppGraph] leaves the relay unconfigured and Share simply
 * does not offer it.
 */
class KtorRelayClient(
    private val client: HttpClient,
    baseUrl: String,
    private val appToken: String,
) : RelayClient {

    private val relayUrl = baseUrl.trimEnd('/') + "/v1/relay"
    private val json = Json { ignoreUnknownKeys = true }
    private val progressFlow = newProgressFlow()

    /** Re-emits measured upload/download progress (W13-14). */
    override val progress: Flow<app.simt.echo.platform.TransferProgress> get() = progressFlow

    override suspend fun upload(sealed: ByteArray): String {
        val total = sealed.size.toLong()
        val response = client.post(relayUrl) {
            header(HttpHeaders.Authorization, "Bearer $appToken")
            setBody(sealed)
        }
        check(response.status.isSuccess()) { "relay upload failed: ${response.status}" }
        // One observed unit: the sealed bytes left as a whole, so progress
        // jumps 0 → total only after the server confirms receipt.
        progressFlow.tryEmit(app.simt.echo.platform.TransferProgress(total, total))
        return json.decodeFromString<RelayUploadResponse>(response.body()).code
    }

    override suspend fun download(code: String): ByteArray {
        val response = client.get("$relayUrl/$code") {
            header(HttpHeaders.Authorization, "Bearer $appToken")
        }
        check(response.status.isSuccess()) { "relay download failed: ${response.status}" }
        return response.readWithProgress { progressFlow.tryEmit(it) }
    }
}
