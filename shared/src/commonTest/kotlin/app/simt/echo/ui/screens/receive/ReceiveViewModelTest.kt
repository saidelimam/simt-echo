package app.simt.echo.ui.screens.receive

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import app.simt.echo.transfer.QrPayload
import app.simt.echo.transfer.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** W2-04 gate: scan → preview (unsaved) → Save persists; failures show the hotspot copy. */
class ReceiveViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeTransport : PeerTransport {
        var servedPayload: ByteArray? = null
        var servedToken: String? = null
        var fetches = 0

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

    private class Setup(
        val phoneB: RouteRepository,
        val transport: FakeTransport,
        val serviceA: TransferService,
        val serviceB: TransferService,
    )

    private fun setup(): Setup {
        val fileSystem = FakeFileSystem()
        val routesA = RouteRepository(FileStore(fileSystem, "/data".toPath()))
        val source = "/camera/start.jpg".toPath()
        fileSystem.createDirectories("/camera".toPath())
        fileSystem.write(source) { write(byteArrayOf(9, 8, 7)) }
        routesA.save(Fixtures.L_ROUTE, mapOf("a_start" to source))
        val transport = FakeTransport()
        val phoneB = RouteRepository(FileStore(FakeFileSystem(), "/phoneB".toPath()))
        val at = { 1_762_000_000_000L }
        return Setup(
            phoneB = phoneB,
            transport = transport,
            serviceA = TransferService(
                transport = transport,
                routes = routesA,
                randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
                nowMs = at,
            ),
            serviceB = TransferService(transport = transport, routes = phoneB, nowMs = at),
        )
    }

    @Test
    fun ignoresNonEchoUris() = runTest(dispatcher) {
        val setup = setup()
        val vm = ReceiveViewModel(setup.serviceB)
        vm.onScanned("https://example.com/not-a-route")
        advanceUntilIdle()

        assertEquals(0, setup.transport.fetches)
        assertNull(vm.ui.value.preview)
    }

    @Test
    fun scanFetchesPreviewWithoutSaving() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB)
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()

        val preview = vm.ui.value.preview
        assertEquals(Fixtures.L_ROUTE.name, preview?.name)
        assertEquals(Fixtures.L_ROUTE.landmarks.size, preview?.landmarkCount)
        assertNull(setup.phoneB.get(Fixtures.L_ROUTE.routeId))
        assertEquals(1, setup.transport.fetches)
    }

    @Test
    fun duplicateScanIgnored() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB)
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()

        assertEquals(1, setup.transport.fetches)
    }

    @Test
    fun savePersistsPreviewedBody() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB)
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()

        var saved: String? = null
        vm.save { saved = it }
        advanceUntilIdle()

        assertEquals(Fixtures.L_ROUTE.routeId, saved)
        assertEquals(Fixtures.L_ROUTE.routeId, vm.ui.value.savedRouteId)
        assertEquals(Fixtures.L_ROUTE, setup.phoneB.get(Fixtures.L_ROUTE.routeId))
        assertEquals(
            listOf<Byte>(9, 8, 7),
            setup.phoneB.readAsset(Fixtures.L_ROUTE.routeId, "a_start")!!.toList(),
        )
    }

    @Test
    fun unreachablePeerShowsErrorCopy() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val payload = QrPayload.parse(offer.qrUri).getOrThrow()
        val badToken = payload.copy(token = ByteArray(16)).build()
        val vm = ReceiveViewModel(setup.serviceB)
        vm.onScanned(badToken)
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertEquals(UNREACHABLE_COPY, vm.ui.value.error)
    }

    @Test
    fun rescanRearmsAfterError() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val payload = QrPayload.parse(offer.qrUri).getOrThrow()
        val vm = ReceiveViewModel(setup.serviceB)
        vm.onScanned(payload.copy(token = ByteArray(16)).build())
        advanceUntilIdle()

        vm.rescan()
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()

        assertEquals(null, vm.ui.value.error)
        assertEquals(Fixtures.L_ROUTE.name, vm.ui.value.preview?.name)
    }
}
