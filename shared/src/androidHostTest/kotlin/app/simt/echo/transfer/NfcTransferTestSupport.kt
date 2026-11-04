package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.platform.nfc.NfcInvitationReader
import app.simt.echo.platform.nfc.NfcTransceiver
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Doubles shared by the two NFC transfer suites: the radio, the in-memory LAN,
 * the relay bucket, and the phones built on them.
 *
 * Only the radio and the network are faked — the presenter, the reader, the
 * service and the bundle codec under test are the real ones. One copy, so
 * neither suite owns a variant and each suite file stays reviewable.
 */
// ── fakes: the radio and the loopback network, nothing else ──

/**
 * The peer's side of the radio: every command goes to the real presenter
 * and comes back verbatim. [failOnCommand] cuts the field at that exact
 * exchange, which is how every chunk boundary gets tested.
 */
internal class ScriptedTransceiver(
    private val respond: (ByteArray) -> ByteArray,
    private val failOnCommand: Int = -1,
) : NfcTransceiver {
    var commands = 0
    val transcript = mutableListOf<ByteArray>()

    override suspend fun transceive(command: ByteArray): ByteArray {
        commands++
        transcript += command.copyOf()
        if (commands == failOnCommand) error("field lost")
        return respond(command)
    }

    override fun close() = Unit
}

/** A radio that never answers until its connection is closed. */
internal class BlockingTransceiver : NfcTransceiver {
    private val released = CompletableDeferred<Unit>()
    var closed = false
        private set

    override suspend fun transceive(command: ByteArray): ByteArray {
        released.await()
        return ByteArray(2)
    }

    override fun close() {
        closed = true
        released.complete(Unit)
    }
}

/** In-memory LAN: the "network" between the two services. */
internal class LoopbackTransport : PeerTransport {
    var servedPayload: ByteArray? = null
    var servedToken: String? = null
    var fetches = 0
    val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 8)

    override val progress: Flow<TransferProgress> get() = progressFlow

    override suspend fun offer(payload: ByteArray, token: String): Endpoint {
        servedPayload = payload
        servedToken = token
        return Endpoint("192.168.1.20", 51234)
    }

    override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
        fetches++
        check(token == servedToken) { "token mismatch" }
        return servedPayload ?: error("nothing served")
    }

    override fun stopOffer() = Unit
}

/** A LAN that cannot reach the peer at all: campus Wi-Fi client isolation. */
internal class IsolatedTransport : PeerTransport {
    var fetches = 0

    override val progress: Flow<TransferProgress> = MutableSharedFlow(extraBufferCapacity = 1)

    override suspend fun offer(payload: ByteArray, token: String): Endpoint =
        error("the receiver never offers")

    override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
        fetches++
        throw IOException("no route to host")
    }

    override fun stopOffer() = Unit
}

/** The cloud relay as the two installs see it: one bucket, one code. */
internal class RelayStore(private val code: String = "ABCD234567") : RelayClient {
    var uploaded: ByteArray? = null
    var uploads = 0
    var downloads = 0

    override suspend fun upload(sealed: ByteArray): String {
        uploads++
        uploaded = sealed.copyOf()
        return code
    }

    override suspend fun download(code: String): ByteArray {
        downloads++
        return uploaded ?: error("nothing was uploaded")
    }
}

internal class Phone(val routes: RouteRepository, val store: FileStore)

internal val AtMs = { 1_762_000_000_000L }

internal fun sender(
    transport: PeerTransport,
    fileSystem: FakeFileSystem,
    relay: RelayClient? = null,
    peerRelayQualified: Boolean = false,
): Pair<TransferService, Phone> {
    val store = FileStore(fileSystem, "/sender".toPath())
    val routes = RouteRepository(store)
    routes.save(Fixtures.L_ROUTE)
    fileSystem.createDirectories("/sender-camera".toPath())
    fileSystem.write("/sender-camera/start.jpg".toPath()) { write(byteArrayOf(9, 8, 7)) }
    val service = TransferService(
        transport = transport,
        routes = routes,
        store = store,
        randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
        nowMs = AtMs,
        relay = relay,
        peerRelayQualified = peerRelayQualified,
        ioDispatcher = Dispatchers.Unconfined,
    )
    return service to Phone(routes, store)
}

internal fun receiver(
    transport: PeerTransport,
    relay: RelayClient? = null,
    peerRelayQualified: Boolean = false,
): Pair<TransferService, Phone> {
    val store = FileStore(FakeFileSystem(), "/receiver".toPath())
    val routes = RouteRepository(store)
    val service = TransferService(
        transport = transport,
        routes = routes,
        store = store,
        nowMs = AtMs,
        relay = relay,
        peerRelayQualified = peerRelayQualified,
        ioDispatcher = Dispatchers.Unconfined,
    )
    return service to Phone(routes, store)
}

/** The published snapshot the presenter serves for one offer. */
internal fun snapshotOf(uri: String, generation: Long = 1L, expiresInMs: Long = 60_000L): NfcOfferSnapshot =
    NfcOfferSnapshot(
        generation = generation,
        offerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        invitation = uri.encodeToByteArray(),
        expiresAtMonotonicMs = 5_000 + expiresInMs,
    )

internal fun presenter(
    snapshot: () -> NfcOfferSnapshot?,
    nowMs: () -> Long = { 5_000 },
    interactive: () -> Boolean = { true },
) = NfcApduPresenter(snapshot = snapshot, nowMs = nowMs, interactive = interactive)

internal class Reading(scope: kotlinx.coroutines.CoroutineScope) {
    val events = mutableListOf<NfcEvent>()
    val reader = NfcInvitationReader(
        scope = scope,
        events = { events += it },
        dispatcher = Dispatchers.Unconfined,
    )

    fun invitation(): String? = events.filterIsInstance<NfcEvent.Invitation>().singleOrNull()?.uri

    fun failure(): PairingFailure? =
        events.filterIsInstance<NfcEvent.ReadFailed>().singleOrNull()?.failure
}

internal fun ByteArray.hex(): String = joinToString(" ") { b ->
    val v = b.toInt() and 0xFF
    v.toString(16).uppercase().padStart(2, '0')
}
