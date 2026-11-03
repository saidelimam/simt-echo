package app.simt.echo.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Where a peer can be reached on the local network. */
data class Endpoint(val host: String, val port: Int)

/**
 * Byte progress attributed to the offer generation that produced it
 * (plan 0005 §4.5). A transport must carry the generation of the transfer
 * it observed, so a late sample cannot be attributed to whatever offer
 * happens to be current when it is collected.
 */
data class OfferProgress(val generation: Long, val progress: TransferProgress)

/**
 * Local-network bundle transport (plan §5.1). Implementations move opaque
 * bytes only — sealing, hashing and identity live in `bundle/` and
 * `transfer/`, so a transport can be swapped (copied LAN code, Ktor,
 * relay) without touching the wire format.
 */
interface PeerTransport {
    /** Serves [payload] to whoever presents [token]; returns our endpoint. */
    suspend fun offer(payload: ByteArray, token: String): Endpoint

    /**
     * Generation-aware offer (plan 0005 §4.5): the transport tags every
     * download and progress sample it observes with [generation], so a
     * result can be attributed to its own offer rather than to whatever is
     * current when it is collected. Defaults to ignoring the tag, so fakes
     * and stubs that cannot attribute keep compiling.
     */
    suspend fun offer(payload: ByteArray, token: String, generation: Long): Endpoint =
        offer(payload, token)

    /** Downloads the payload a peer is offering at [endpoint]. */
    suspend fun fetch(endpoint: Endpoint, token: String): ByteArray

    /** Stops serving (after a successful fetch or when the UI leaves). */
    fun stopOffer()

    /**
     * Stops only the server created for [generation]. A stop for an offer
     * that has already been replaced is ignored — the delayed shutdown of
     * an old offer must never take down a newer one.
     */
    fun stopOffer(generation: Long) = stopOffer()

    /**
     * Emits once per successful peer download (W2-04 Share "Sent ✓").
     * Defaults to never emitting so fakes and platform stubs that cannot
     * observe downloads keep compiling without pretending they can.
     */
    val served: Flow<Unit> get() = emptyFlow()

    /**
     * Additive byte-count progress (W13-14): emits [TransferProgress] as
     * bytes move in the active direction — served on the offering phone,
     * received on the fetching phone. Monotonic per transfer, ends at
     * ([TransferProgress.totalBytes], itself). Defaults to never emitting
     * so fakes and stubs that cannot observe chunk flow keep compiling.
     * Wire format, keys and tokens are untouched.
     */
    val progress: Flow<TransferProgress> get() = emptyFlow()

    /** Downloads attributed to their own offer generation (plan 0005 §4.5). */
    val servedOffers: Flow<Long> get() = emptyFlow()

    /** Bytes attributed to their own offer generation (plan 0005 §4.5). */
    val offerProgress: Flow<OfferProgress> get() = emptyFlow()
}
