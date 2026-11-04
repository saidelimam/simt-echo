package app.simt.echo.transfer

import app.simt.echo.data.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The per-install bearer (review 0012, R12-02).
 *
 * The worker retired the shared `ECHO_APP_TOKEN`: every authenticated call
 * carries a 256-bit device key that `POST /v1/device` minted for this
 * install, and every job, upload and relay object is stamped with the
 * opaque id derived from it. The key lives in app-private storage — never
 * in the build, a log or an export.
 */
interface DeviceCredentials {
    /** The persisted key, minting one on first use. */
    suspend fun bearer(): String

    /** Mints and persists a fresh key, replacing the previous one. */
    suspend fun rotate(): String

    /** Forgets the local key; the caller deletes the server record. */
    suspend fun forget()
}

/** `dk_` + 43 base64url characters: the worker's minting format. */
private val DEVICE_KEY = Regex("^dk_[A-Za-z0-9_-]{43}$")

@Serializable
private data class MintResponse(val ok: Boolean = false, val device: String = "")

private val deviceJson = Json { ignoreUnknownKeys = true }

/**
 * `POST /v1/device` — the only unauthenticated route that mints a key.
 * Throws [EchoApiError] rather than returning an empty or malformed key:
 * a broken mint must surface, not silently authenticate nothing.
 */
suspend fun mintDeviceKey(http: HttpClient, baseUrl: String): String {
    if (baseUrl.isBlank()) throw EchoApiError.NotConfigured()
    val response = try {
        http.post(baseUrl.trimEnd('/') + "/v1/device")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw EchoApiError.Network(e)
    }
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) throw EchoApiError.Server(response.status.value, body)
    val key = runCatching { deviceJson.decodeFromString<MintResponse>(body).device }.getOrNull()
    if (key == null || !DEVICE_KEY.matches(key)) {
        throw EchoApiError.Server(response.status.value, body)
    }
    return key
}

/**
 * File-backed [DeviceCredentials]: one key per install under the
 * app-private data directory. First use is single-flight, so concurrent
 * requests mint once; a missing or corrupt file re-mints instead of failing
 * every call.
 */
class FileDeviceCredentials(
    private val store: FileStore,
    private val mint: suspend () -> String,
    /**
     * Checks a stored key against the server once per process (R12-09): a
     * record aged out by the `expire-devices` rule answers 401 to a key that
     * still sits in app storage, and without this the install would be locked
     * out until its data was cleared. Absent means "trust what is stored".
     */
    private val verify: (suspend (String) -> Boolean)? = null,
) : DeviceCredentials {
    private val mutex = Mutex()
    private var cached: String? = null

    override suspend fun bearer(): String = mutex.withLock {
        cached ?: readStored()?.let { stored -> if (verify == null || verify(stored)) stored else null }
            ?: mintAndStore()
    }

    override suspend fun rotate(): String = mutex.withLock { mintAndStore() }

    override suspend fun forget(): Unit = mutex.withLock {
        cached = null
        store.deleteRecursively(PATH)
    }

    private fun readStored(): String? =
        store.readText(PATH)?.trim()?.takeIf { DEVICE_KEY.matches(it) }

    private suspend fun mintAndStore(): String {
        val key = mint()
        if (!DEVICE_KEY.matches(key)) throw EchoApiError.Server(200, "malformed device key")
        cached = key
        store.writeText(PATH, key)
        return key
    }

    private companion object {
        /** Relative to the app-private data directory; never exported. */
        const val PATH = "device/credential"
    }
}

/**
 * True unless the server rejects [key] with 401. The probe is a read of a
 * session id that cannot exist, so a live key gets a 404 and nothing is
 * touched. A transport failure counts as accepted: an offline launch must
 * not burn the key it already holds.
 */
suspend fun deviceKeyAccepted(http: HttpClient, baseUrl: String, key: String): Boolean {
    if (baseUrl.isBlank()) return true
    return try {
        val response = http.get(baseUrl.trimEnd('/') + "/v1/uploads/00000000-0000-0000-0000-000000000000") {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
        response.status.value != 401
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        true
    }
}
