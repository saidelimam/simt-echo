package app.simt.echo.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Where a peer can be reached on the local network. */
data class Endpoint(val host: String, val port: Int)

/**
 * Local-network bundle transport (plan §5.1). Implementations move opaque
 * bytes only — sealing, hashing and identity live in `bundle/` and
 * `transfer/`, so a transport can be swapped (copied LAN code, Ktor,
 * relay) without touching the wire format.
 */
interface PeerTransport {
    /** Serves [payload] to whoever presents [token]; returns our endpoint. */
    suspend fun offer(payload: ByteArray, token: String): Endpoint

    /** Downloads the payload a peer is offering at [endpoint]. */
    suspend fun fetch(endpoint: Endpoint, token: String): ByteArray

    /** Stops serving (after a successful fetch or when the UI leaves). */
    fun stopOffer()

    /**
     * Emits once per successful peer download (W2-04 Share "Sent ✓").
     * Defaults to never emitting so fakes and platform stubs that cannot
     * observe downloads keep compiling without pretending they can.
     */
    val served: Flow<Unit> get() = emptyFlow()
}
