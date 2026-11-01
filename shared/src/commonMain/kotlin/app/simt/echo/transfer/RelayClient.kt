package app.simt.echo.transfer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
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

    override suspend fun upload(sealed: ByteArray): String {
        val response = client.post(relayUrl) {
            header(HttpHeaders.Authorization, "Bearer $appToken")
            setBody(sealed)
        }
        check(response.status.isSuccess()) { "relay upload failed: ${response.status}" }
        return json.decodeFromString<RelayUploadResponse>(response.body()).code
    }

    override suspend fun download(code: String): ByteArray {
        val response = client.get("$relayUrl/$code") {
            header(HttpHeaders.Authorization, "Bearer $appToken")
        }
        check(response.status.isSuccess()) { "relay download failed: ${response.status}" }
        return response.body()
    }
}
