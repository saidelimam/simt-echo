package app.simt.echo.ui.screens.share

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.PeerTransport
import app.simt.echo.transfer.QrPayload
import app.simt.echo.transfer.RelayClient
import app.simt.echo.transfer.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2-04 gate: offer shows the QR; a peer download flips to "Sent ✓" + CONFIRM. */
class ShareViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeHaptics : Haptics {
        val played = mutableListOf<HapticPattern>()
        override fun play(pattern: HapticPattern) {
            played += pattern
        }
    }

    private class FakeTransport(val downloads: MutableSharedFlow<Unit>) : PeerTransport {
        var stopCalls = 0

        override suspend fun offer(payload: ByteArray, token: String): Endpoint =
            Endpoint("192.168.1.20", 51234)

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
            error("Share never fetches")

        override fun stopOffer() {
            stopCalls++
        }

        override val served: Flow<Unit> get() = downloads
    }

    private fun serviceWithRoute(): Pair<TransferService, FakeTransport> {
        val fileSystem = FakeFileSystem()
        val routes = RouteRepository(FileStore(fileSystem, "/data".toPath()))
        routes.save(Fixtures.L_ROUTE)
        val transport = FakeTransport(MutableSharedFlow())
        val service = TransferService(
            transport = transport,
            routes = routes,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )
        return service to transport
    }

    @Test
    fun offerShowsQrThenSentOnDownload() = runTest(dispatcher) {
        val (service, _) = serviceWithRoute()
        val haptics = FakeHaptics()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, haptics)
        advanceUntilIdle()

        val offered = vm.ui.value
        assertNotNull(offered.qrUri)
        assertTrue(offered.qrUri.startsWith("simt-echo://"))
    }

    @Test
    fun downloadMarksSentAndConfirms() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        val haptics = FakeHaptics()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, haptics)
        advanceUntilIdle()

        transport.downloads.emit(Unit)
        advanceUntilIdle()

        assertTrue(vm.ui.value.sent)
        assertEquals(listOf(HapticPattern.CONFIRM), haptics.played)
    }

    @Test
    fun relayActionHiddenWithoutRelayAndAddsCodeWhenConfigured() = runTest(dispatcher) {
        // No relay configured: no relay state surfaces.
        val (plainService, _) = serviceWithRoute()
        val plainVm = ShareViewModel(Fixtures.L_ROUTE.routeId, plainService, FakeHaptics())
        advanceUntilIdle()
        assertTrue(!plainVm.ui.value.relayAvailable)

        // Relay configured: action appears, then the QR gains the code.
        val fileSystem = FakeFileSystem()
        val routes = RouteRepository(FileStore(fileSystem, "/relay".toPath()))
        routes.save(Fixtures.L_ROUTE)
        val relay = object : RelayClient {
            override suspend fun upload(sealed: ByteArray): String = "XYZ789"
            override suspend fun download(code: String): ByteArray = error("unused")
        }
        val service = TransferService(
            transport = FakeTransport(MutableSharedFlow()),
            routes = routes,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            relay = relay,
        )
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics())
        advanceUntilIdle()
        assertTrue(vm.ui.value.relayAvailable)
        assertNull(QrPayload.parse(vm.ui.value.qrUri ?: error("no QR yet")).getOrThrow().relayCode)

        vm.useInternetRelay()
        advanceUntilIdle()
        assertTrue(vm.ui.value.relayOffered)
        assertEquals(
            "XYZ789",
            QrPayload.parse(vm.ui.value.qrUri ?: error("no QR yet")).getOrThrow().relayCode,
        )
    }

    @Test
    fun offerFailureShowsError() = runTest(dispatcher) {
        val routes = RouteRepository(FileStore(FakeFileSystem(), "/empty".toPath()))
        val service = TransferService(FakeTransport(MutableSharedFlow()), routes)
        val vm = ShareViewModel("no-such-route", service, FakeHaptics())
        advanceUntilIdle()

        val ui = vm.ui.value
        assertNull(ui.qrUri)
        assertNotNull(ui.error)
    }
}
