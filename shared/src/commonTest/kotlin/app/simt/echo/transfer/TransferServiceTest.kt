package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** W2-03 gate: offer → QR → receive saves the route; tamper fails. */
class TransferServiceTest {

    private class FakeTransport : PeerTransport {
        var servedPayload: ByteArray? = null
        var servedToken: String? = null
        var stopCalls = 0

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            servedPayload = payload
            servedToken = token
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
            check(token == servedToken) { "token mismatch" }
            return servedPayload ?: error("nothing served")
        }

        override fun stopOffer() { stopCalls++ }
    }

    private fun setup(): Triple<RouteRepository, FakeTransport, TransferService> {
        val fileSystem = FakeFileSystem()
        val routes = RouteRepository(FileStore(fileSystem, "/data".toPath()))
        // Seed a route with a start-photo asset on disk.
        val source = "/camera/start.jpg".toPath()
        fileSystem.createDirectories("/camera".toPath())
        fileSystem.write(source) { write(byteArrayOf(9, 8, 7)) }
        routes.save(Fixtures.L_ROUTE, mapOf("a_start" to source))
        val transport = FakeTransport()
        val service = TransferService(
            transport = transport,
            routes = routes,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )
        return Triple(routes, transport, service)
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
        val phoneB = RouteRepository(FileStore(FakeFileSystem(), "/phoneB".toPath()))
        val serviceB = TransferService(transport, phoneB, nowMs = { 1_762_000_000_000L })
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
