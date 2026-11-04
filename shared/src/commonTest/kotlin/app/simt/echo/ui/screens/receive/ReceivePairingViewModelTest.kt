package app.simt.echo.ui.screens.receive

import app.simt.echo.Fixtures
import app.simt.echo.bundle.BundleCodec
import app.simt.echo.model.BundleBody
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.EchoSchemaVersion
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FakeNfcPairingController
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.NfcCapabilities
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.transfer.NfcReadiness
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingMethod
import app.simt.echo.transfer.PairingSource
import app.simt.echo.transfer.QrPayload
import app.simt.echo.transfer.TransferService
import app.simt.echo.transfer.base64Url
import app.simt.echo.ui.copy.receiveExpiredCopy
import app.simt.echo.ui.copy.receiveUnverifiedCopy
import kotlinx.coroutines.CompletableDeferred
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

/**
 * W21-02 gate: either source, one attempt, one Save.
 *
 * QR and NFC are asserted to converge on the same parser and the same
 * single-flight fetch, so a stale scan, a stale tap or a cancelled attempt
 * can never produce a second preview — and only an explicit Save persists.
 */
class ReceivePairingViewModelTest {

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
        var fetchGate: CompletableDeferred<Unit>? = null
        val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 16)

        override val progress: Flow<TransferProgress> get() = progressFlow

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            servedPayload = payload
            servedToken = token
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
            fetches++
            fetchGate?.await()
            check(token == servedToken) { "token mismatch" }
            return servedPayload ?: error("nothing served")
        }

        override fun stopOffer() = Unit
    }

    private class FakeSharing(private val picked: ByteArray? = null) : FileSharing {
        var picks = 0
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = false
        override suspend fun pickDocument(): ByteArray? {
            picks++
            return picked
        }
    }

    private class Rig(
        val transport: FakeTransport,
        val storeA: FileStore,
        val routesA: RouteRepository,
        val receiver: TransferService,
        val phoneB: RouteRepository,
    )

    private fun rig(): Rig {
        val fileSystem = FakeFileSystem()
        val storeA = FileStore(fileSystem, "/phoneA".toPath())
        val routesA = RouteRepository(storeA)
        routesA.save(Fixtures.L_ROUTE)
        val transport = FakeTransport()
        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val sender = TransferService(
            transport = transport,
            routes = routesA,
            store = storeA,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val receiver = TransferService(
            transport = transport,
            routes = phoneB,
            store = storeB,
            nowMs = { 1_762_000_000_000L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        return Rig(transport, storeA, routesA, receiver, phoneB)
    }

    private suspend fun Rig.offer(routeId: String = Fixtures.L_ROUTE.routeId): String =
        TransferService(
            transport = transport,
            routes = routesA,
            store = storeA,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            ioDispatcher = Dispatchers.Unconfined,
        ).offer(routeId).qrUri

    private fun viewModel(
        rig: Rig,
        pairing: FakeNfcPairingController = FakeNfcPairingController(NfcCapabilities.NONE),
        sharing: FileSharing = FakeSharing(),
    ) = ReceiveViewModel(rig.receiver, sharing, pairing = pairing)

    @Test
    fun everyNewVisitStartsInQr() = runTest(dispatcher) {
        val rig = rig()
        val first = viewModel(rig)
        advanceUntilIdle()
        assertEquals(PairingMethod.QR, first.ui.value.method)

        // Leaving and returning does not remember the previous choice.
        first.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        assertEquals(PairingMethod.NFC, first.ui.value.method)

        val second = viewModel(rig)
        advanceUntilIdle()
        assertEquals(PairingMethod.QR, second.ui.value.method)
    }

    @Test
    fun choosingNfcDisarmsTheScannerAndArmsTheReader() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        assertTrue(pairing.readingAttempts.isEmpty(), "QR needs no reader")

        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()

        assertEquals(listOf(vm.ui.value.generation.toLong()), pairing.readingAttempts)
        assertTrue(vm.ui.value.nfcListening)
        assertTrue(vm.ui.value.methodLocked.not())
        // Camera permission is never part of this path: the VM has no camera
        // dependency at all, which is what makes NFC receive camera-free.
        assertEquals(NfcReadiness.ARMED, vm.ui.value.nfcReadiness, "listening for a tap")
    }

    @Test
    fun aPhoneWithoutNfcStillReceivesByQrAndSaysWhy() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(NfcCapabilities.NONE)
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()

        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        assertEquals(NfcReadiness.UNAVAILABLE, vm.ui.value.nfcReadiness)
        assertEquals(PairingMethod.NFC, vm.ui.value.method, "the choice stays explainable")
    }

    @Test
    fun aTapFetchesThroughTheSameSingleFlightAttempt() = runTest(dispatcher) {
        val rig = rig()
        val uri = rig.offer()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()

        val attempt = vm.ui.value.generation.toLong()
        pairing.emit(NfcEvent.Invitation(attempt, uri))
        advanceUntilIdle()

        assertEquals(1, rig.transport.fetches)
        assertEquals(Fixtures.L_ROUTE.name, vm.ui.value.preview?.name)
        assertNull(rig.phoneB.get(Fixtures.L_ROUTE.routeId), "a tap never auto-saves")
    }

    @Test
    fun simultaneousSourcesYieldExactlyOneFetch() = runTest(dispatcher) {
        val rig = rig()
        val uri = rig.offer()
        val vm = viewModel(rig)
        advanceUntilIdle()
        val attempt = vm.ui.value.generation.toLong()

        // A camera frame, a duplicate frame and a stale tap all at once.
        vm.onInvitation(uri, PairingSource.QR, attempt)
        vm.onInvitation(uri, PairingSource.QR, attempt)
        vm.onInvitation(uri, PairingSource.NFC, attempt - 1)
        advanceUntilIdle()

        assertEquals(1, rig.transport.fetches)
        assertNotNull(vm.ui.value.preview)
    }

    @Test
    fun aStaleSourceCannotFeedTheCurrentAttempt() = runTest(dispatcher) {
        val rig = rig()
        val uri = rig.offer()
        val vm = viewModel(rig)
        advanceUntilIdle()
        val attempt = vm.ui.value.generation.toLong()

        // Retry bumps the generation: the old attempt's callback is dead.
        vm.rescan()
        advanceUntilIdle()
        assertTrue(vm.ui.value.generation > attempt)

        // The frame that arrived late still names the attempt it started in.
        vm.onInvitation(uri, PairingSource.QR, attempt)
        advanceUntilIdle()
        assertEquals(0, rig.transport.fetches, "the QR frame still carried the old attempt")
    }

    @Test
    fun cancellingAPartialReadCannotResurrectAPreview() = runTest(dispatcher) {
        val rig = rig()
        val uri = rig.offer()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        val attempt = vm.ui.value.generation.toLong()

        vm.cancel()
        advanceUntilIdle()

        // The read completes after the cancel: it belongs to a dead attempt.
        pairing.emit(NfcEvent.Invitation(attempt, uri))
        advanceUntilIdle()
        assertNull(vm.ui.value.preview)
        assertEquals(0, rig.transport.fetches)
    }

    @Test
    fun cancellingDuringAFetchLeavesNoPreview() = runTest(dispatcher) {
        val rig = rig()
        val uri = rig.offer()
        val gate = CompletableDeferred<Unit>()
        rig.transport.fetchGate = gate
        val vm = viewModel(rig)
        advanceUntilIdle()

        vm.onScanned(uri)
        advanceUntilIdle()
        assertTrue(vm.ui.value.fetching)

        vm.cancel()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertFalse(vm.ui.value.fetching)
        assertFalse(vm.ui.value.methodLocked)
    }

    @Test
    fun retryInTheSameMethodSucceeds() = runTest(dispatcher) {
        val rig = rig()
        val offer = rig.offer()
        val payload = QrPayload.parse(offer).getOrThrow()
        val vm = viewModel(rig)
        advanceUntilIdle()

        // A wrong token is a transport failure, not a parse failure.
        vm.onScanned(payload.copy(token = ByteArray(16)).build())
        advanceUntilIdle()
        assertEquals(UNREACHABLE_COPY, vm.ui.value.error)

        vm.rescan()
        vm.onScanned(offer)
        advanceUntilIdle()
        assertNull(vm.ui.value.error)
        assertEquals(Fixtures.L_ROUTE.name, vm.ui.value.preview?.name)
    }

    @Test
    fun eachFailureKeepsItsOwnTypedReason() = runTest(dispatcher) {
        val rig = rig()
        val vm = viewModel(rig)
        advanceUntilIdle()

        // A URI that parses as ECHO but is not a valid invitation.
        vm.onScanned("simt-echo://r?v=1&h=127.0.0.1&p=1&t=AQIDBAUGBwgJCgsMDQ4PEA" +
            "&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&s=0123456789abcdef&n=x")
        advanceUntilIdle()
        assertEquals(PairingFailure.InvalidInvitation, vm.ui.value.pairingFailure)
        assertNull(vm.ui.value.error, "a rejected invitation is not network advice")
        assertEquals(0, rig.transport.fetches)

        // A reader that reports a wrong tag.
        vm.rescan()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val nfcVm = viewModel(rig, pairing)
        advanceUntilIdle()
        nfcVm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        pairing.emit(NfcEvent.ReadFailed(nfcVm.ui.value.generation.toLong(), PairingFailure.InvalidInvitation))
        advanceUntilIdle()
        assertEquals(PairingFailure.InvalidInvitation, nfcVm.ui.value.pairingFailure)
    }

    @Test
    fun aTapTimeoutSaysNoTapYetAndStopsPolling() = runTest(dispatcher) {
        // W20-05 accept: 20 s without a completed invitation stops polling and
        // leaves QR recovery behind; it never re-arms itself in a loop.
        val rig = rig()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()

        pairing.emit(NfcEvent.ReadFailed(vm.ui.value.generation.toLong(), PairingFailure.Timeout))
        advanceUntilIdle()

        assertEquals(PairingFailure.Timeout, vm.ui.value.pairingFailure)
        assertFalse(vm.ui.value.nfcListening, "a timed-out tap stops polling")
        assertNull(vm.ui.value.error, "no tap yet is a status, not an error page")
        assertEquals(0, rig.transport.fetches)
    }

    @Test
    fun anInterruptedTapKeepsListeningAndReady() = runTest(dispatcher) {
        val rig = rig()
        val uri = rig.offer()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        val attempt = vm.ui.value.generation.toLong()

        // Field loss mid-exchange: reader mode is still on, so the screen
        // must not claim it is merely preparing (R14-04).
        pairing.emit(NfcEvent.ReadFailed(attempt, PairingFailure.Interrupted))
        advanceUntilIdle()
        assertTrue(vm.ui.value.nfcListening)
        assertEquals(NfcReadiness.ARMED, vm.ui.value.nfcReadiness)
        assertEquals(PairingFailure.Interrupted, vm.ui.value.pairingFailure)

        // The retry is the same tap, on the same attempt.
        pairing.emit(NfcEvent.Invitation(attempt, uri))
        advanceUntilIdle()
        assertEquals(Fixtures.L_ROUTE.name, vm.ui.value.preview?.name)
        assertNull(vm.ui.value.pairingFailure)
    }

    @Test
    fun switchingNfcOnWhileOnReceiveArmsTheReaderOnce() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = false))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        assertEquals(NfcReadiness.DISABLED, vm.ui.value.nfcReadiness)
        assertFalse(vm.ui.value.nfcListening)
        assertTrue(pairing.readingAttempts.isEmpty(), "a disabled adapter is never armed")

        // The user turns NFC on in Settings or the quick-settings tile.
        pairing.setCapabilities(NfcCapabilities(canRead = true, enabled = true))
        advanceUntilIdle()

        assertEquals(1, pairing.readingAttempts.size, "the reader arms exactly once")
        assertEquals(vm.ui.value.generation.toLong(), pairing.readingAttempts.single())
        assertTrue(vm.ui.value.nfcListening)
        assertEquals(NfcReadiness.ARMED, vm.ui.value.nfcReadiness)
        assertNull(vm.ui.value.pairingFailure)
    }

    @Test
    fun aNewerSchemaFailsBeforeSave() = runTest(dispatcher) {
        val rig = rig()
        val future = Fixtures.L_ROUTE.copy(routeId = "future", schemaVersion = EchoSchemaVersion.CURRENT + 1)
        rig.routesA.save(future)

        val vm = viewModel(rig)
        advanceUntilIdle()
        vm.onScanned(rig.offer("future"))
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertEquals(UPDATE_ECHO_COPY, vm.ui.value.error)
        assertNull(rig.phoneB.get("future"))
    }

    @Test
    fun savePersistsExactlyOnceForRepeatedTaps() = runTest(dispatcher) {
        val rig = rig()
        val vm = viewModel(rig)
        advanceUntilIdle()
        vm.onScanned(rig.offer())
        advanceUntilIdle()

        var saves = 0
        vm.save { saves++ }
        vm.save { saves++ }
        advanceUntilIdle()

        assertEquals(1, saves, "Save is single-flight")
        assertEquals(Fixtures.L_ROUTE, rig.phoneB.get(Fixtures.L_ROUTE.routeId))
    }

    @Test
    fun aCancelledPickerReturnsToTheSelectedMethodQuietly() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val sharing = FakeSharing(picked = null)
        val vm = viewModel(rig, pairing, sharing)
        advanceUntilIdle()
        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        val listening = pairing.readingAttempts.last()

        vm.importFile()
        advanceUntilIdle()

        assertEquals(1, sharing.picks)
        assertNull(vm.ui.value.preview)
        assertNull(vm.ui.value.error)
        assertFalse(vm.ui.value.methodLocked)
        // Re-armed in the method the user chose, not in QR.
        assertEquals(PairingMethod.NFC, vm.ui.value.method)
        assertTrue(pairing.readingAttempts.last() > listening)
    }

    @Test
    fun aMethodSwitchIsLockedWhileAPreviewOwnsTheScreen() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.onScanned(rig.offer())
        advanceUntilIdle()
        assertNotNull(vm.ui.value.preview)
        assertTrue(vm.ui.value.methodLocked)

        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        assertEquals(PairingMethod.QR, vm.ui.value.method, "the user must Cancel first")
        assertEquals(0, pairing.readingAttempts.size, "the reader never armed under a preview")

        vm.cancel()
        advanceUntilIdle()
        assertFalse(vm.ui.value.methodLocked)
        assertNull(vm.ui.value.preview)
    }

    @Test
    fun anExpiredLinkSaysExpiredInsteadOfUnreachable() = runTest(dispatcher) {
        val rig = rig()
        // A bundle that was valid when it was sealed but expired by the time
        // this phone opened it: the peer answered, so this is not network.
        val expired = Fixtures.L_ROUTE.copy(
            routeId = "expired-route",
            privacy = Fixtures.L_ROUTE.privacy.copy(expiresAtEpochMs = 1_000L),
        )
        val container = BundleCodec.encode(expired, emptyList())
        val (sealed, key) = BundleCodec.seal(container)
        // The URI carries the fingerprint of the decoded body, so the failure
        // this test proves is expiry — not a fingerprint mismatch.
        val body = BundleBody(route = expired, assets = emptyList())
        val token = ByteArray(16) { 1 }
        rig.transport.servedPayload = sealed
        rig.transport.servedToken = base64Url(token)
        val uri = QrPayload(
            host = "192.168.1.20",
            port = 51234,
            token = token,
            key = key,
            sha8Hex = BundleCodec.sha8Hex(body),
            name = expired.name,
        ).build()

        val vm = viewModel(rig)
        advanceUntilIdle()
        vm.onScanned(uri)
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertEquals(receiveExpiredCopy(), vm.ui.value.error)
        assertEquals(PairingFailure.OfferExpired, vm.ui.value.pairingFailure)
    }

    @Test
    fun aBundleThatFailsItsFingerprintSaysUnverified() = runTest(dispatcher) {
        val rig = rig()
        val payload = QrPayload.parse(rig.offer()).getOrThrow()
        // The token still opens the LAN transfer; the bytes then fail `s`.
        val tampered = payload.copy(sha8Hex = "0000000000000000").build()

        val vm = viewModel(rig)
        advanceUntilIdle()
        vm.onScanned(tampered)
        advanceUntilIdle()

        assertNull(vm.ui.value.preview)
        assertEquals(receiveUnverifiedCopy(), vm.ui.value.error)
        assertEquals(PairingFailure.Verification, vm.ui.value.pairingFailure)
    }

    @Test
    fun aResumeAfterSaveDoesNotRearmAcquisition() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.onScanned(rig.offer())
        advanceUntilIdle()
        vm.save {}
        advanceUntilIdle()
        assertNotNull(vm.ui.value.savedRouteId)
        val generation = vm.ui.value.generation

        vm.onResumed()
        advanceUntilIdle()

        assertEquals(generation, vm.ui.value.generation, "a finished visit does not re-arm")
        assertTrue(pairing.readingAttempts.isEmpty(), "and it does not open the reader")
    }

    @Test
    fun aSecondSaveAfterCompletionDoesNotPersistAgain() = runTest(dispatcher) {
        val rig = rig()
        val vm = viewModel(rig)
        advanceUntilIdle()
        vm.onScanned(rig.offer())
        advanceUntilIdle()

        var saves = 0
        vm.save { saves++ }
        advanceUntilIdle()
        assertEquals(1, saves)

        vm.save { saves++ }
        advanceUntilIdle()
        assertEquals(1, saves, "a completed Save cannot write the route a second time")
    }

    @Test
    fun closingEndsAcquisitionAndIgnoresLateEvents() = runTest(dispatcher) {
        val rig = rig()
        val uri = rig.offer()
        val pairing = FakeNfcPairingController(NfcCapabilities(canRead = true, enabled = true))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        val attempt = vm.ui.value.generation.toLong()

        vm.close()
        advanceUntilIdle()
        assertEquals(1, pairing.closeCalls)

        pairing.emit(NfcEvent.Invitation(attempt, uri))
        advanceUntilIdle()
        assertNull(vm.ui.value.preview)
        assertEquals(0, rig.transport.fetches)
    }
}
