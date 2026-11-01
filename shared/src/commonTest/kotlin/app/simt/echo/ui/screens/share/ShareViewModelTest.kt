package app.simt.echo.ui.screens.share

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.ECHO_MIME
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.PeerTransport
import app.simt.echo.transfer.ExpiryOption
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
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private class FakeFileSharing : FileSharing {
        var sharedPath: Path? = null
        var sharedName: String? = null
        var sharedMime: String? = null
        var shareFails = false

        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean {
            sharedPath = path
            sharedName = name
            sharedMime = mime
            return !shareFails
        }

        override suspend fun pickDocument(): ByteArray? = null
    }

    private fun serviceWithRoute(): Triple<TransferService, FakeTransport, FileStore> {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val transport = FakeTransport(MutableSharedFlow())
        val service = TransferService(
            transport = transport,
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )
        return Triple(service, transport, store)
    }

    @Test
    fun offerShowsQrThenSentOnDownload() = runTest(dispatcher) {
        val (service, _) = serviceWithRoute()
        val haptics = FakeHaptics()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, haptics, FakeFileSharing())
        advanceUntilIdle()

        val offered = vm.ui.value
        assertNotNull(offered.qrUri)
        assertTrue(offered.qrUri.startsWith("simt-echo://"))
    }

    @Test
    fun downloadMarksSentAndConfirms() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        val haptics = FakeHaptics()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, haptics, FakeFileSharing())
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
        val plainVm = ShareViewModel(Fixtures.L_ROUTE.routeId, plainService, FakeHaptics(), FakeFileSharing())
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
            store = FileStore(fileSystem, "/relay".toPath()),
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            relay = relay,
        )
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
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
        val emptyStore = FileStore(FakeFileSystem(), "/empty".toPath())
        val routes = RouteRepository(emptyStore)
        val service = TransferService(
            FakeTransport(MutableSharedFlow()),
            routes,
            store = emptyStore,
        )
        val vm = ShareViewModel("no-such-route", service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()

        val ui = vm.ui.value
        assertNull(ui.qrUri)
        assertNotNull(ui.error)
    }

    @Test
    fun fileExportWarnsBeforeWritingAndSharesAfterConfirm() = runTest(dispatcher) {
        val (service, _, store) = serviceWithRoute()
        val sharing = FakeFileSharing()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), sharing)
        advanceUntilIdle()

        // Requesting the share only raises the warning; no file exists yet.
        vm.requestFileShare()
        assertTrue(vm.ui.value.fileWarning)
        assertNull(sharing.sharedPath)
        assertNull(store.readBytes("exports/${service.echoFileName(Fixtures.L_ROUTE.name).removeSuffix(".echo")}-${Fixtures.L_ROUTE.routeId}.echo"))

        // Confirming writes the plain container and opens the sheet with it.
        vm.confirmFileShare()
        advanceUntilIdle()

        val expected = store.resolvePath("exports/${service.echoFileName(Fixtures.L_ROUTE.name).removeSuffix(".echo")}-${Fixtures.L_ROUTE.routeId}.echo")
        assertNotNull(store.readBytes("exports/${service.echoFileName(Fixtures.L_ROUTE.name).removeSuffix(".echo")}-${Fixtures.L_ROUTE.routeId}.echo"))
        assertEquals(expected, sharing.sharedPath)
        assertEquals(ECHO_MIME, sharing.sharedMime)
        val ui = vm.ui.value
        assertFalse(ui.fileWarning)
        assertTrue(ui.fileShared)
    }

    @Test
    fun fileWarningDismissWritesNothing() = runTest(dispatcher) {
        val (service, _, store) = serviceWithRoute()
        val sharing = FakeFileSharing()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), sharing)
        advanceUntilIdle()

        vm.requestFileShare()
        vm.dismissFileShare()

        val ui = vm.ui.value
        assertFalse(ui.fileWarning)
        assertFalse(ui.fileShared)
        assertNull(sharing.sharedPath)
        assertNull(store.readBytes("exports/${service.echoFileName(Fixtures.L_ROUTE.name).removeSuffix(".echo")}-${Fixtures.L_ROUTE.routeId}.echo"))
    }

    @Test
    fun previewDefaultsStartPhotoOnAndToggleReoffers() = runTest(dispatcher) {
        val (service, _) = serviceWithRoute()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()

        // L fixture: start photo only, default ON.
        assertEquals(1, vm.ui.value.exportItems.size)
        assertTrue(vm.ui.value.selection.includeStartPhoto)
        assertNotNull(vm.ui.value.qrUri)

        vm.toggleAsset("start", false)
        advanceUntilIdle()

        assertFalse(vm.ui.value.selection.includeStartPhoto)
        // Re-offered: still a live QR, now encoding the exclusion.
        assertNotNull(vm.ui.value.qrUri)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun expirySelectorUpdatesSelectionAndReoffers() = runTest(dispatcher) {
        val (service, _) = serviceWithRoute()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()
        assertEquals(ExpiryOption.NONE, vm.ui.value.selection.expiry)

        vm.setExpiry(ExpiryOption.HOURS_24)
        advanceUntilIdle()

        assertEquals(ExpiryOption.HOURS_24, vm.ui.value.selection.expiry)
        assertNotNull(vm.ui.value.qrUri)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun fileShareSheetFailureShowsError() = runTest(dispatcher) {
        val (service, _, store) = serviceWithRoute()
        val sharing = FakeFileSharing().apply { shareFails = true }
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), sharing)
        advanceUntilIdle()

        vm.requestFileShare()
        vm.confirmFileShare()
        advanceUntilIdle()

        val ui = vm.ui.value
        assertFalse(ui.fileWarning)
        assertFalse(ui.fileShared)
        assertNotNull(ui.error)
        // The file was written before the sheet failed; the error is surfaced.
        assertNotNull(store.readBytes("exports/${service.echoFileName(Fixtures.L_ROUTE.name).removeSuffix(".echo")}-${Fixtures.L_ROUTE.routeId}.echo"))
    }
}
