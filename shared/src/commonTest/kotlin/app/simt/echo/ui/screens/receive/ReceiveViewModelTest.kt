package app.simt.echo.ui.screens.receive

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.EchoSchemaVersion
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.transfer.QrPayload
import app.simt.echo.transfer.TRANSFER_STALL_MS
import app.simt.echo.transfer.TransferService
import kotlinx.coroutines.Dispatchers
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
        val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)

        override val progress: kotlinx.coroutines.flow.Flow<TransferProgress> get() = progressFlow

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            servedPayload = payload
            servedToken = token
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
            fetches++
            check(token == servedToken) { "token mismatch" }
            val bytes = servedPayload ?: error("nothing served")
            val total = bytes.size.toLong()
            progressFlow.emit(TransferProgress(0L, total))
            progressFlow.emit(TransferProgress(total, total))
            return bytes
        }

        override fun stopOffer() = Unit
    }

    private class FakeFileSharing : FileSharing {
        var pickResult: ByteArray? = null
        var picks = 0

        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean =
            error("Receive never shares")

        override suspend fun pickDocument(): ByteArray? {
            picks++
            return pickResult
        }
    }

    private class Setup(
        val storeA: FileStore,
        val phoneB: RouteRepository,
        val storeB: FileStore,
        val transport: FakeTransport,
        val serviceA: TransferService,
        val serviceB: TransferService,
    )

    private fun setup(): Setup {
        val fileSystem = FakeFileSystem()
        val storeA = FileStore(fileSystem, "/data".toPath())
        val routesA = RouteRepository(storeA)
        val source = "/camera/start.jpg".toPath()
        fileSystem.createDirectories("/camera".toPath())
        fileSystem.write(source) { write(byteArrayOf(9, 8, 7)) }
        routesA.save(Fixtures.L_ROUTE, mapOf("a_start" to source))
        val transport = FakeTransport()
        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val at = { 1_762_000_000_000L }
        return Setup(
            storeA = storeA,
            phoneB = phoneB,
            storeB = storeB,
            transport = transport,
            serviceA = TransferService(
                transport = transport,
                routes = routesA,
                store = storeA,
                randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
                nowMs = at,
            ),
            serviceB = TransferService(transport = transport, routes = phoneB, store = storeB, nowMs = at),
        )
    }

    @Test
    fun ignoresNonEchoUris() = runTest(dispatcher) {
        val setup = setup()
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
        vm.onScanned("https://example.com/not-a-route")
        advanceUntilIdle()

        assertEquals(0, setup.transport.fetches)
        assertNull(vm.ui.value.preview)
    }

    @Test
    fun scanFetchesPreviewWithoutSaving() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
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
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
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
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
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
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
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
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
        vm.onScanned(payload.copy(token = ByteArray(16)).build())
        advanceUntilIdle()

        vm.rescan()
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()

        assertEquals(null, vm.ui.value.error)
        assertEquals(Fixtures.L_ROUTE.name, vm.ui.value.preview?.name)
    }

    @Test
    fun importFileDecodesPreviewWithoutSaving() = runTest(dispatcher) {
        val setup = setup()
        val exportPath = setup.serviceA.exportFile(Fixtures.L_ROUTE.routeId)
        val bytes = setup.storeA.readBytes(exportPath.toString())!!
        val sharing = FakeFileSharing().apply { pickResult = bytes }
        val vm = ReceiveViewModel(setup.serviceB, sharing)

        vm.importFile()
        advanceUntilIdle()

        assertEquals(Fixtures.L_ROUTE.name, vm.ui.value.preview?.name)
        assertNull(vm.ui.value.error)
        assertNull(setup.phoneB.get(Fixtures.L_ROUTE.routeId))
    }

    @Test
    fun importFileCancelledRestartsQuietly() = runTest(dispatcher) {
        val setup = setup()
        val sharing = FakeFileSharing() // pickDocument() → null (cancel)
        val vm = ReceiveViewModel(setup.serviceB, sharing)

        vm.importFile()
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertNull(vm.ui.value.error)

        // The scanner re-arms and a subsequent scan still works.
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()
        assertEquals(Fixtures.L_ROUTE.name, vm.ui.value.preview?.name)
    }

    @Test
    fun importFileBadBytesShowsError() = runTest(dispatcher) {
        val setup = setup()
        val sharing = FakeFileSharing().apply {
            pickResult = "not a bundle".encodeToByteArray()
        }
        val vm = ReceiveViewModel(setup.serviceB, sharing)

        vm.importFile()
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertNotNull(vm.ui.value.error)
    }

    @Test
    fun previewCarriesModelCountsAndExpiry() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()

        val preview = vm.ui.value.preview
        assertNotNull(preview)
        assertEquals(Fixtures.L_ROUTE.name, preview.name)
        assertEquals(Fixtures.L_ROUTE.totalLengthM, preview.lengthM)
        assertEquals(2, preview.turns)
        assertEquals(Fixtures.L_ROUTE.landmarks.size, preview.landmarkCount)
        assertEquals(Fixtures.L_ROUTE.creatorLabel, preview.creatorLabel)
        assertEquals(Fixtures.L_ROUTE.privacy.expiresAtEpochMs, preview.expiresAtEpochMs)
    }

    @Test
    fun rescanFromPreviewResetsCleanly() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()
        assertNotNull(vm.ui.value.preview)
        val generation = vm.ui.value.generation

        vm.rescan()

        assertNull(vm.ui.value.preview)
        assertNull(vm.ui.value.error)
        assertEquals(generation + 1, vm.ui.value.generation)

        // The scanner re-arms: the same QR scans again into a fresh preview.
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()
        assertNotNull(vm.ui.value.preview)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun rescanAfterSaveClearsSavedState() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()
        var saved: String? = null
        vm.save { saved = it }
        advanceUntilIdle()
        assertEquals(Fixtures.L_ROUTE.routeId, saved)
        assertNotNull(vm.ui.value.savedRouteId)

        vm.rescan()

        assertNull(vm.ui.value.savedRouteId)
        assertNull(vm.ui.value.preview)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun fetchSurfacesProgressEndingAtTotal() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing(), nowMs = { 9_000L })
        advanceUntilIdle()
        assertNull(vm.ui.value.progress)

        vm.onScanned(offer.qrUri)
        advanceUntilIdle()

        // The preview landed and the measured flow ended exactly at total.
        assertNotNull(vm.ui.value.preview)
        val progress = vm.ui.value.progress
        assertNotNull(progress)
        assertTrue(progress.totalBytes > 0L)
        assertEquals(progress.totalBytes, progress.bytesSent)
        assertTrue(progress.isComplete)
        assertTrue(vm.ui.value.bytesPerSecond >= 0.0)
    }

    @Test
    fun rescanClearsProgress() = runTest(dispatcher) {
        val setup = setup()
        val offer = setup.serviceA.offer(Fixtures.L_ROUTE.routeId)
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing(), nowMs = { 9_000L })
        advanceUntilIdle()
        vm.onScanned(offer.qrUri)
        advanceUntilIdle()
        assertNotNull(vm.ui.value.progress)

        vm.rescan()

        assertNull(vm.ui.value.progress)
        assertEquals(0.0, vm.ui.value.bytesPerSecond)
    }

    @Test
    fun stalledFetchZeroesTheRateAndRescanCancelsTheTimer() = runTest(dispatcher) {
        val setup = setup()
        var now = 1_000L
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing(), nowMs = { now })
        advanceUntilIdle()

        now = 2_000L
        setup.transport.progressFlow.emit(TransferProgress(10L, 100L))
        runCurrent()
        now = 3_000L
        setup.transport.progressFlow.emit(TransferProgress(60L, 100L))
        runCurrent()
        assertEquals(50.0, vm.ui.value.bytesPerSecond)

        advanceTimeBy(TRANSFER_STALL_MS)
        runCurrent()
        assertEquals(0.0, vm.ui.value.bytesPerSecond, "no bytes for a second: the light must go dim")
        assertEquals(0.6f, vm.ui.value.progress?.fraction)
    }

    @Test
    fun routeFromANewerEchoSaysUpdateInsteadOfOpening() = runTest(dispatcher) {
        val setup = setup()
        val future = Fixtures.L_ROUTE.copy(routeId = "future-route", schemaVersion = EchoSchemaVersion.CURRENT + 1)
        RouteRepository(setup.storeA).save(future)

        // Scanned over LAN: no preview, no half-understood route, honest copy.
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
        vm.onScanned(setup.serviceA.offer(future.routeId).qrUri)
        advanceUntilIdle()
        assertNull(vm.ui.value.preview)
        assertFalse(vm.ui.value.fetching)
        assertEquals(UPDATE_ECHO_COPY, vm.ui.value.error)
        assertNull(setup.phoneB.get(future.routeId))

        // Picked as a file: same notice.
        val bytes = setup.storeA.readBytes(setup.serviceA.exportFile(future.routeId).toString())!!
        val fileVm = ReceiveViewModel(setup.serviceB, FakeFileSharing().apply { pickResult = bytes })
        fileVm.importFile()
        advanceUntilIdle()
        assertNull(fileVm.ui.value.preview)
        assertEquals(UPDATE_ECHO_COPY, fileVm.ui.value.error)
    }

    @Test
    fun currentSchemaRouteStillOpens() = runTest(dispatcher) {
        val setup = setup()
        val vm = ReceiveViewModel(setup.serviceB, FakeFileSharing())
        vm.onScanned(setup.serviceA.offer(Fixtures.L_ROUTE.routeId).qrUri)
        advanceUntilIdle()
        assertNotNull(vm.ui.value.preview)
        assertNull(vm.ui.value.error)
    }
}
