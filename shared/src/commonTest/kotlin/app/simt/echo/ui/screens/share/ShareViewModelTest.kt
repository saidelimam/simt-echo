package app.simt.echo.ui.screens.share

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.EchoRoute
import app.simt.echo.platform.ECHO_MIME
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.transfer.ExpiryOption
import app.simt.echo.transfer.QrPayload
import app.simt.echo.transfer.RelayClient
import app.simt.echo.transfer.TRANSFER_STALL_MS
import app.simt.echo.transfer.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
        var offerFails = false
        val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)

        override val progress: Flow<TransferProgress> get() = progressFlow

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            if (offerFails) error("no Wi-Fi IP")
            return Endpoint("192.168.1.20", 51234)
        }

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

    private fun serviceWithRoute(route: EchoRoute = Fixtures.L_ROUTE): Triple<TransferService, FakeTransport, FileStore> {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(route)
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
    fun toggleClearsStaleQrAndSentBeforeReoffer() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()
        transport.downloads.emit(Unit)
        advanceUntilIdle()
        assertTrue(vm.ui.value.sent)
        assertNotNull(vm.ui.value.qrUri)

        vm.toggleAsset("start", false)
        // Synchronously cleared: no stale superset QR stays live.
        assertNull(vm.ui.value.qrUri)
        assertFalse(vm.ui.value.sent)

        advanceUntilIdle()
        assertNotNull(vm.ui.value.qrUri)
        assertFalse(vm.ui.value.sent)
    }

    @Test
    fun expiredRouteSurfacesErrorAndNoQr() = runTest(dispatcher) {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(
            Fixtures.L_ROUTE.copy(
                privacy = app.simt.echo.model.PrivacyInfo(expiresAtEpochMs = 1_000L),
            ),
        )
        val service = TransferService(
            transport = FakeTransport(MutableSharedFlow()),
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 2_000L },
        )
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()
        assertNull(vm.ui.value.qrUri)
        assertNotNull(vm.ui.value.error)
        assertTrue(vm.ui.value.error!!.contains("expired", ignoreCase = true))
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

    @Test
    fun offerTransportFailureRetriesToLiveQr() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        transport.offerFails = true
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()

        // Recoverable state, not a stuck "Starting offer…": no QR, human error.
        assertNull(vm.ui.value.qrUri)
        val failure = vm.ui.value.error
        assertNotNull(failure)
        assertTrue(failure.contains("Nothing was shared"))

        transport.offerFails = false
        vm.retryOffer()
        advanceUntilIdle()

        assertNotNull(vm.ui.value.qrUri)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun retryOnPersistentFailureKeepsRecoverableError() = runTest(dispatcher) {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(
            Fixtures.L_ROUTE.copy(
                privacy = app.simt.echo.model.PrivacyInfo(expiresAtEpochMs = 1_000L),
            ),
        )
        val service = TransferService(
            transport = FakeTransport(MutableSharedFlow()),
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 2_000L },
        )
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()
        assertNull(vm.ui.value.qrUri)

        vm.retryOffer()
        advanceUntilIdle()

        assertNull(vm.ui.value.qrUri)
        assertNotNull(vm.ui.value.error)
        assertTrue(vm.ui.value.error!!.contains("expired", ignoreCase = true))
    }

    @Test
    fun retryWithLiveOfferIsNoop() = runTest(dispatcher) {
        val (service, _) = serviceWithRoute()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()
        val qr = vm.ui.value.qrUri
        assertNotNull(qr)

        vm.retryOffer()
        advanceUntilIdle()

        assertEquals(qr, vm.ui.value.qrUri)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun stopDelegatesToTransport() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()

        vm.stop()
        assertEquals(1, transport.stopCalls)
    }

    @Test
    fun progressSurfacesFractionAndMeasuredRate() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        var now = 1_000L
        val vm = ShareViewModel(
            Fixtures.L_ROUTE.routeId,
            service,
            FakeHaptics(),
            FakeFileSharing(),
            nowMs = { now },
        )
        advanceUntilIdle()
        assertNull(vm.ui.value.progress)

        // First sighting: fraction known, but no interval yet — rate 0.
        now = 2_000L
        transport.progressFlow.emit(TransferProgress(50L, 100L))
        advanceUntilIdle()
        assertEquals(0.5f, vm.ui.value.progress?.fraction)
        assertEquals(0.0, vm.ui.value.bytesPerSecond)

        // 50 bytes in 1000 ms: the particle flow runs at 50 B/s.
        now = 3_000L
        transport.progressFlow.emit(TransferProgress(100L, 100L))
        advanceUntilIdle()
        assertEquals(1.0f, vm.ui.value.progress?.fraction)
        assertEquals(50.0, vm.ui.value.bytesPerSecond)
        assertEquals(true, vm.ui.value.progress?.isComplete)
    }

    @Test
    fun reofferClearsStaleProgress() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        val vm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()
        transport.progressFlow.emit(TransferProgress(100L, 100L))
        advanceUntilIdle()
        assertNotNull(vm.ui.value.progress)

        vm.toggleAsset("start", false)
        // Synchronously cleared with the stale QR: frozen until bytes move.
        assertNull(vm.ui.value.progress)
        assertEquals(0.0, vm.ui.value.bytesPerSecond)

        advanceUntilIdle()
        assertNotNull(vm.ui.value.qrUri)
        assertNull(vm.ui.value.progress)
    }

    @Test
    fun stalledTransferZeroesTheRateButKeepsTheFraction() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        var now = 1_000L
        val vm = ShareViewModel(
            Fixtures.L_ROUTE.routeId,
            service,
            FakeHaptics(),
            FakeFileSharing(),
            nowMs = { now },
        )
        advanceUntilIdle()

        now = 2_000L
        transport.progressFlow.emit(TransferProgress(25L, 100L))
        runCurrent()
        now = 3_000L
        transport.progressFlow.emit(TransferProgress(50L, 100L))
        runCurrent()
        assertEquals(25.0, vm.ui.value.bytesPerSecond)

        // Just under a second of silence: still lit.
        advanceTimeBy(TRANSFER_STALL_MS - 1)
        runCurrent()
        assertEquals(25.0, vm.ui.value.bytesPerSecond)

        // Stalled: the last positive rate must not keep the particles lit.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(0.0, vm.ui.value.bytesPerSecond)
        assertEquals(0.5f, vm.ui.value.progress?.fraction)

        // Bytes move again: the rate is measured afresh.
        now = 5_000L
        transport.progressFlow.emit(TransferProgress(100L, 100L))
        runCurrent()
        assertEquals(25.0, vm.ui.value.bytesPerSecond)
    }

    @Test
    fun finishedTransferRestsWithoutAStallTimer() = runTest(dispatcher) {
        val (service, transport) = serviceWithRoute()
        var now = 1_000L
        val vm = ShareViewModel(
            Fixtures.L_ROUTE.routeId,
            service,
            FakeHaptics(),
            FakeFileSharing(),
            nowMs = { now },
        )
        advanceUntilIdle()
        now = 2_000L
        transport.progressFlow.emit(TransferProgress(50L, 100L))
        runCurrent()
        now = 3_000L
        transport.progressFlow.emit(TransferProgress(100L, 100L))
        runCurrent()

        advanceTimeBy(TRANSFER_STALL_MS * 5)
        runCurrent()
        assertEquals(50.0, vm.ui.value.bytesPerSecond, "a completed transfer is not a stall")
    }

    @Test
    fun sharedFileLeavesPerceptionOutUnlessOptedIn() = runTest(dispatcher) {
        val (service, _, store) = serviceWithRoute(Fixtures.V2_ROUTE)
        val sharing = FakeFileSharing()
        val vm = ShareViewModel(Fixtures.V2_ROUTE.routeId, service, FakeHaptics(), sharing)
        advanceUntilIdle()
        assertTrue(vm.ui.value.summary?.hasPerception == true, "the preview must know the route has perception")

        // Default: the warning is up, the opt-in is off, the file has none.
        vm.requestFileShare()
        assertFalse(vm.ui.value.filePerception)
        vm.confirmFileShare()
        advanceUntilIdle()
        val plain = service.importFile(store.readBytes(sharing.sharedPath.toString())!!)
        assertNull(plain.route.perception)
        assertFalse(vm.ui.value.filePerception)

        // Opt in: the file carries it; the choice does not outlive the share.
        vm.requestFileShare()
        vm.setFilePerception(true)
        assertTrue(vm.ui.value.filePerception)
        vm.confirmFileShare()
        advanceUntilIdle()
        val opted = service.importFile(store.readBytes(sharing.sharedPath.toString())!!)
        assertEquals(Fixtures.V2_ROUTE.perception, opted.route.perception)
        assertFalse(vm.ui.value.filePerception, "opt-in must re-arm to off after each share")
    }

    @Test
    fun dismissingTheFileWarningForgetsThePerceptionOptIn() = runTest(dispatcher) {
        val (service, _, _) = serviceWithRoute(Fixtures.V2_ROUTE)
        val vm = ShareViewModel(Fixtures.V2_ROUTE.routeId, service, FakeHaptics(), FakeFileSharing())
        advanceUntilIdle()
        vm.requestFileShare()
        vm.setFilePerception(true)
        vm.dismissFileShare()
        assertFalse(vm.ui.value.filePerception)
    }
}
