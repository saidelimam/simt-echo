package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W20-03 gate: cancellation is not a transport failure, and an unqualified
 * relay is never used.
 *
 * Every case here is a real failure mode the plan names: a cancelled fetch
 * that quietly downloaded the bundle from the relay instead, a stale
 * completion that republished a QR nothing serves, and a relay upload sent
 * to a backend whose `getRelay` would refuse another install's code.
 */
class TransferCancellationTest {

    private class FakeLan(
        /** What the receiver gets; the offering phone fills it on offer. */
        var payload: ByteArray? = null,
        var token: String? = null,
        var fetchCalls: Int = 0,
        var stopCalls: Int = 0,
        var failFetch: Boolean = false,
        var fetchGate: CompletableDeferred<Unit>? = null,
    ) : PeerTransport {
        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            this.payload = payload
            this.token = token
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
            fetchCalls++
            fetchGate?.await()
            if (failFetch) throw IllegalStateException("LAN unreachable")
            check(token == this.token) { "token mismatch" }
            return payload ?: error("nothing served")
        }

        override fun stopOffer() {
            stopCalls++
        }
    }

    private class FakeRelay(private val code: String = "ABCD2345", var uploadGate: CompletableDeferred<Unit>? = null) : RelayClient {
        val stored = mutableMapOf<String, ByteArray>()
        var uploads = 0
        var downloads = 0
        var progressFlow: MutableList<TransferProgress> = mutableListOf()

        override suspend fun upload(sealed: ByteArray): String {
            uploads++
            uploadGate?.await()
            stored[code] = sealed
            return code
        }

        override suspend fun download(code: String): ByteArray {
            downloads++
            return stored[code] ?: error("relay: no such code")
        }

        override val progress: Flow<TransferProgress> get() = kotlinx.coroutines.flow.emptyFlow()
    }

    private data class Rig(
        val routes: RouteRepository,
        val store: FileStore,
        val lan: FakeLan,
        val relay: FakeRelay,
    )

    private fun rig(relay: FakeRelay = FakeRelay(), lan: FakeLan = FakeLan()): Rig {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        return Rig(routes, store, lan, relay)
    }

    private fun sender(rig: Rig, qualified: Boolean = true): TransferService = TransferService(
        transport = rig.lan,
        routes = rig.routes,
        store = rig.store,
        randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
        nowMs = { 1_762_000_000_000L },
        relay = rig.relay,
        peerRelayQualified = qualified,
    )

    private fun receiver(rig: Rig, qualified: Boolean = true): TransferService = TransferService(
        transport = rig.lan,
        routes = RouteRepository(FileStore(FakeFileSystem(), "/phoneB".toPath())),
        store = FileStore(FakeFileSystem(), "/phoneB".toPath()),
        nowMs = { 1_762_000_000_000L },
        relay = rig.relay,
        peerRelayQualified = qualified,
    )

    // ── cancellation ─────────────────────────────────────────────

    @Test
    fun aCancelledFetchNeverFallsBackToTheRelay() = runTest {
        val rig = rig()
        val offer = sender(rig).offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        val gate = CompletableDeferred<Unit>()
        rig.lan.fetchGate = gate
        var thrown: Throwable? = null

        val job = launch {
            try {
                receiver(rig).fetchPreview(offer.qrUri)
            } catch (failure: Throwable) {
                thrown = failure
                throw failure
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(thrown is CancellationException, "cancellation must propagate: $thrown")
        assertEquals(0, rig.relay.downloads, "a cancelled fetch must not download from the relay")
    }

    @Test
    fun aCancelledOfferNeverPublishesAndStopsWhatItStarted() = runTest {
        val rig = rig()
        rig.relay.uploadGate = CompletableDeferred()
        val service = sender(rig)
        var thrown: Throwable? = null

        val job = launch {
            try {
                service.offer(Fixtures.L_ROUTE.routeId, useRelay = true)
            } catch (failure: Throwable) {
                thrown = failure
                throw failure
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(thrown is CancellationException, "cancellation must propagate: $thrown")
        assertNull(service.currentOffer, "a cancelled offer is never published")
        assertTrue(rig.lan.stopCalls >= 1, "the server it started was closed")
    }

    // ── validation before networking ─────────────────────────────

    @Test
    fun anInvalidInvitationCausesNoNetworkWorkAtAll() = runTest {
        val rig = rig()
        val service = receiver(rig)

        assertFailsWith<IllegalArgumentException> {
            service.fetchPreview(
                "simt-echo://r?v=1&h=127.0.0.1&p=1&t=AQIDBAUGBwgJCgsMDQ4PEA" +
                    "&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&s=0123456789abcdef&n=x",
            )
        }
        assertEquals(0, rig.lan.fetchCalls, "no LAN attempt for a rejected invitation")
        assertEquals(0, rig.relay.downloads, "no relay attempt for a rejected invitation")
    }

    @Test
    fun anOversizeInvitationIsRejectedBeforeNetworking() = runTest {
        val rig = rig()
        val service = receiver(rig)
        val padded = "simt-echo://r?v=1&n=" + "p".repeat(PairingInvitation.MAX_UTF8_BYTES)
        assertFailsWith<IllegalArgumentException> { service.fetchPreview(padded) }
        assertEquals(0, rig.lan.fetchCalls)
    }

    // ── integrity failures are never masked ──────────────────────

    @Test
    fun aFailedFingerprintIsNeverMaskedByARelayDownload() = runTest {
        val rig = rig()
        val offer = sender(rig).offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        val payload = QrPayload.parse(offer.qrUri).getOrThrow()
        val forged = payload.copy(sha8Hex = "ffffffffffffffff").build()

        // The bytes arrive; only verification fails. A relay is configured
        // and qualified, yet fetching a second copy would hide the tamper.
        assertFailsWith<IllegalStateException> { receiver(rig).fetchPreview(forged) }
        assertEquals(0, rig.relay.downloads)
    }

    // ── relay qualification ──────────────────────────────────────

    @Test
    fun aConfiguredRelayIsNotAutomaticallyUsable() = runTest {
        val rig = rig()
        val service = sender(rig, qualified = false)
        assertTrue(service.relayConfigured, "the relay is configured on this build")
        assertFalse(service.relayAvailable, "but peer delivery is not qualified")

        assertFailsWith<IllegalStateException> {
            service.offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        }
        assertEquals(0, rig.relay.uploads, "nothing may be uploaded for an unqualified relay")
    }

    @Test
    fun anUnqualifiedRelayIsNotUsedAsAFallbackEvenWithACode() = runTest {
        val rig = rig()
        // Sender is qualified so the QR carries `c=`; the receiver is not.
        val offer = sender(rig, qualified = true).offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        assertEquals("ABCD2345", QrPayload.parse(offer.qrUri).getOrThrow().relayCode)

        rig.lan.failFetch = true
        assertFailsWith<IllegalStateException> { receiver(rig, qualified = false).fetchPreview(offer.qrUri) }
        assertEquals(0, rig.relay.downloads, "an unqualified relay is never dialled")
    }

    @Test
    fun aQualifiedRelayStillRescuesAnUnreachablePeer() = runTest {
        val rig = rig()
        val offer = sender(rig, qualified = true).offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        rig.lan.failFetch = true
        val body = receiver(rig, qualified = true).fetchPreview(offer.qrUri)
        assertEquals(Fixtures.L_ROUTE, body.route)
        assertEquals(1, rig.relay.downloads)
    }
}
