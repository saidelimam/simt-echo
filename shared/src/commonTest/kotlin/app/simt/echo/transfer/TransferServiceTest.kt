package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.platform.bytesPerSecond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** W2-03 gate: offer → QR → receive saves the route; tamper fails. */
class TransferServiceTest {

    private class FakeTransport : PeerTransport {
        var servedPayload: ByteArray? = null
        var servedToken: String? = null
        var stopCalls = 0
        var failFetch = false
        val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)

        override val progress: Flow<TransferProgress> get() = progressFlow

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            servedPayload = payload
            servedToken = token
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
            if (failFetch) throw IllegalStateException("LAN unreachable")
            check(token == servedToken) { "token mismatch" }
            val bytes = servedPayload ?: error("nothing served")
            emitChunked(bytes)
            return bytes
        }

        /** Chunked honest emits: monotonic, ending exactly at total. */
        suspend fun emitChunked(bytes: ByteArray) {
            val total = bytes.size.toLong()
            progressFlow.emit(TransferProgress(0L, total))
            var sent = 0
            while (sent < bytes.size) {
                sent = minOf(bytes.size, sent + 2)
                progressFlow.emit(TransferProgress(sent.toLong(), total))
            }
        }

        override fun stopOffer() { stopCalls++ }
    }

    /** In-memory relay (W4-05): stores sealed bytes by code, like R2. */
    private class FakeRelay(private val code: String = "ABCD2345") : RelayClient {
        val store = mutableMapOf<String, ByteArray>()
        var downloads = 0
        val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)

        override val progress: Flow<TransferProgress> get() = progressFlow

        override suspend fun upload(sealed: ByteArray): String {
            store[code] = sealed
            return code
        }

        override suspend fun download(code: String): ByteArray {
            downloads++
            val bytes = store[code] ?: error("relay: no such code")
            val total = bytes.size.toLong()
            progressFlow.emit(TransferProgress(0L, total))
            var sent = 0
            while (sent < bytes.size) {
                sent = minOf(bytes.size, sent + 3)
                progressFlow.emit(TransferProgress(sent.toLong(), total))
            }
            return bytes
        }
    }

    private data class Setup(
        val routes: RouteRepository,
        val transport: FakeTransport,
        val service: TransferService,
        val store: FileStore,
    )

    private fun setup(): Setup {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        // Seed a route with a start-photo asset on disk.
        val source = "/camera/start.jpg".toPath()
        fileSystem.createDirectories("/camera".toPath())
        fileSystem.write(source) { write(byteArrayOf(9, 8, 7)) }
        routes.save(Fixtures.L_ROUTE, mapOf("a_start" to source))
        val transport = FakeTransport()
        val service = TransferService(
            transport = transport,
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )
        return Setup(routes, transport, service, store)
    }

    @Test
    fun offerThenReceiveSavesRouteAndAssets() = runTest {
        val (routes, transport, service) = setup()
        val offer = service.offer(Fixtures.L_ROUTE.routeId)
        assertEquals("192.168.1.20", offer.host)
        assertEquals(51234, offer.port)

        val payload = QrPayload.parse(offer.qrUri).getOrThrow()
        assertEquals(16, payload.token.size)
        assertEquals(32, payload.key.size)
        assertEquals(Fixtures.L_ROUTE.name, payload.name)

        // Receive into a *different* repository, like Phone B.
        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val serviceB = TransferService(transport, phoneB, storeB, nowMs = { 1_762_000_000_000L })
        val received = serviceB.receive(offer.qrUri)

        assertEquals(Fixtures.L_ROUTE, received.route)
        assertEquals(Fixtures.L_ROUTE, phoneB.get(Fixtures.L_ROUTE.routeId))
        assertEquals(
            listOf<Byte>(9, 8, 7),
            phoneB.readAsset(Fixtures.L_ROUTE.routeId, "a_start")!!.toList(),
        )
        assertNotNull(routes.get(Fixtures.L_ROUTE.routeId)) // sender untouched
    }

    @Test
    fun tamperedFingerprintFailsReceive() = runTest {
        val (_, _, service) = setup()
        val offer = service.offer(Fixtures.L_ROUTE.routeId)
        val payload = QrPayload.parse(offer.qrUri).getOrThrow()
        val forged = payload.copy(sha8Hex = "ffffffffffffffff").build()
        // Same transport and key; only the QR fingerprint is wrong.
        assertFailsWith<IllegalStateException> { service.receive(forged) }
    }

    @Test
    fun offerUnknownRouteFails() = runTest {
        val (_, _, service) = setup()
        assertFailsWith<IllegalStateException> { service.offer("nope") }
    }

    @Test
    fun stopOfferDelegates() = runTest {
        val (_, transport, service) = setup()
        service.offer(Fixtures.L_ROUTE.routeId)
        service.stopOffer()
        assertEquals(1, transport.stopCalls)
    }

    @Test
    fun fetchPreviewReturnsBodyWithoutSaving() = runTest {
        val (_, transport, service) = setup()
        val offer = service.offer(Fixtures.L_ROUTE.routeId)

        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val serviceB = TransferService(transport, phoneB, storeB, nowMs = { 1_762_000_000_000L })
        val body = serviceB.fetchPreview(offer.qrUri)

        assertEquals(Fixtures.L_ROUTE, body.route)
        assertNull(phoneB.get(Fixtures.L_ROUTE.routeId))
    }

    @Test
    fun saveImportedPersistsPreviewedBody() = runTest {
        val (_, transport, service) = setup()
        val offer = service.offer(Fixtures.L_ROUTE.routeId)

        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val serviceB = TransferService(transport, phoneB, storeB, nowMs = { 1_762_000_000_000L })
        val body = serviceB.fetchPreview(offer.qrUri)
        serviceB.saveImported(body)

        assertEquals(Fixtures.L_ROUTE, phoneB.get(Fixtures.L_ROUTE.routeId))
        assertEquals(
            listOf<Byte>(9, 8, 7),
            phoneB.readAsset(Fixtures.L_ROUTE.routeId, "a_start")!!.toList(),
        )
    }

    @Test
    fun relayOfferUploadsCiphertextAndAddsCodeToQr() = runTest {
        val seeded = setup()
        val relay = FakeRelay()
        val withRelay = TransferService(
            transport = seeded.transport,
            routes = seeded.routes,
            store = seeded.store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            relay = relay,
        )
        assertTrue(withRelay.relayAvailable)
        assertTrue(!seeded.service.relayAvailable)

        val offer = withRelay.offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        val payload = QrPayload.parse(offer.qrUri).getOrThrow()
        assertEquals("ABCD2345", payload.relayCode)

        // The relay only ever holds the sealed bytes (ECX1 ciphertext).
        val uploaded = relay.store.getValue("ABCD2345")
        assertTrue(uploaded.contentEquals(seeded.transport.servedPayload))
        assertEquals("ECX1", uploaded.decodeToString(0, 4))
    }

    @Test
    fun relayOfferWithoutConfiguredRelayFails() = runTest {
        val (_, _, service) = setup()
        assertFailsWith<IllegalStateException> {
            service.offer(Fixtures.L_ROUTE.routeId, useRelay = true)
        }
    }

    @Test
    fun lanFailureFallsBackToRelay() = runTest {
        val relay = FakeRelay()
        val seeded = setup()
        val senderWithRelay = TransferService(
            transport = seeded.transport,
            routes = seeded.routes,
            store = seeded.store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            relay = relay,
        )
        val offer = senderWithRelay.offer(Fixtures.L_ROUTE.routeId, useRelay = true)

        // Phone B: LAN is blocked, relay is configured with the same store.
        val deadLan = FakeTransport().apply { failFetch = true }
        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val serviceB = TransferService(
            transport = deadLan,
            routes = phoneB,
            store = storeB,
            nowMs = { 1_762_000_000_000L },
            relay = relay,
        )
        val body = serviceB.fetchPreview(offer.qrUri)
        assertEquals(Fixtures.L_ROUTE, body.route)
        assertEquals(1, relay.downloads)
    }

    @Test
    fun lanFailureWithoutRelayRethrows() = runTest {
        val (_, _, service) = setup()
        val offer = service.offer(Fixtures.L_ROUTE.routeId)
        assertNull(QrPayload.parse(offer.qrUri).getOrThrow().relayCode)

        val deadLan = FakeTransport().apply { failFetch = true }
        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val serviceB = TransferService(
            transport = deadLan,
            routes = phoneB,
            store = storeB,
            nowMs = { 1_762_000_000_000L },
            relay = FakeRelay(),
        )
        assertFailsWith<IllegalStateException> { serviceB.fetchPreview(offer.qrUri) }
    }

    @Test
    fun servedDefaultsToEmpty() = runTest {
        assertNull(FakeTransport().served.firstOrNull())
    }

    @Test
    fun progressDefaultsToEmpty() = runTest {
        val stub = object : PeerTransport {
            override suspend fun offer(payload: ByteArray, token: String): Endpoint =
                Endpoint("192.168.1.20", 51234)

            override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
                error("nothing served")

            override fun stopOffer() = Unit
        }
        assertNull(stub.progress.firstOrNull())
    }

    @Test
    fun lanFetchEmitsMonotonicProgressEndingAtTotal() = runTest {
        val (_, transport, service) = setup()
        val offer = service.offer(Fixtures.L_ROUTE.routeId)

        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val serviceB = TransferService(transport, phoneB, storeB, nowMs = { 1_762_000_000_000L })
        val seen = mutableListOf<TransferProgress>()
        val collector = backgroundScope.launch { serviceB.progress.collect { seen += it } }
        runCurrent()
        serviceB.fetchPreview(offer.qrUri)
        runCurrent()
        collector.cancel()

        val total = transport.servedPayload!!.size.toLong()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.totalBytes == total })
        assertTrue(seen.zipWithNext().all { (a, b) -> b.bytesSent >= a.bytesSent })
        val last = seen.last()
        assertEquals(total, last.bytesSent)
        assertTrue(last.isComplete)
    }

    @Test
    fun relayFetchEmitsMonotonicProgressEndingAtTotal() = runTest {
        val relay = FakeRelay()
        val seeded = setup()
        val senderWithRelay = TransferService(
            transport = seeded.transport,
            routes = seeded.routes,
            store = seeded.store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            relay = relay,
        )
        val offer = senderWithRelay.offer(Fixtures.L_ROUTE.routeId, useRelay = true)

        // Phone B: LAN is blocked, relay carries the bytes.
        val deadLan = FakeTransport().apply { failFetch = true }
        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val serviceB = TransferService(
            transport = deadLan,
            routes = phoneB,
            store = storeB,
            nowMs = { 1_762_000_000_000L },
            relay = relay,
        )
        val seen = mutableListOf<TransferProgress>()
        val collector = backgroundScope.launch { serviceB.progress.collect { seen += it } }
        runCurrent()
        val body = serviceB.fetchPreview(offer.qrUri)
        runCurrent()
        collector.cancel()

        assertEquals(Fixtures.L_ROUTE, body.route)
        val total = relay.store.getValue("ABCD2345").size.toLong()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.totalBytes == total })
        assertTrue(seen.zipWithNext().all { (a, b) -> b.bytesSent >= a.bytesSent })
        val last = seen.last()
        assertEquals(total, last.bytesSent)
        assertTrue(last.isComplete)
    }

    @Test
    fun progressFractionAndRateMath() {
        assertEquals(0f, TransferProgress().fraction)
        assertEquals(0.5f, TransferProgress(50L, 100L).fraction)
        assertEquals(1f, TransferProgress(100L, 100L).fraction)
        assertTrue(!TransferProgress(50L, 100L).isComplete)
        assertTrue(TransferProgress(100L, 100L).isComplete)

        // 50 bytes in 1000 ms.
        assertEquals(
            50.0,
            bytesPerSecond(TransferProgress(50L, 100L), 2_000L, TransferProgress(100L, 100L), 3_000L),
        )
        // Frozen light: no time, or no bytes, measures 0.
        assertEquals(
            0.0,
            bytesPerSecond(TransferProgress(50L, 100L), 2_000L, TransferProgress(100L, 100L), 2_000L),
        )
        assertEquals(
            0.0,
            bytesPerSecond(TransferProgress(50L, 100L), 2_000L, TransferProgress(50L, 100L), 3_000L),
        )
    }

    @Test
    fun lanAddressPrefersWlanThenHotspotThenAny() {
        val wlan = LanInterface("wlan0", "192.168.1.20", true, false, true)
        val hotspot = LanInterface("ap0", "192.168.43.1", true, false, true)
        val other = LanInterface("eth0", "10.0.0.5", true, false, true)
        assertEquals("192.168.1.20", selectLanIpv4(listOf(hotspot, other, wlan)))
        assertEquals("192.168.43.1", selectLanIpv4(listOf(other, hotspot)))
        assertEquals("10.0.0.5", selectLanIpv4(listOf(other)))
    }

    @Test
    fun lanAddressSkipsUnusableInterfaces() {
        val down = LanInterface("wlan0", "192.168.1.20", false, false, true)
        val loopback = LanInterface("lo", "127.0.0.1", true, true, false)
        val publicIp = LanInterface("wlan0", "8.8.8.8", true, false, false)
        assertEquals(null, selectLanIpv4(listOf(down, loopback, publicIp)))
        assertEquals(null, selectLanIpv4(emptyList()))
    }
}
