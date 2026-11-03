package app.simt.echo.ui.screens.share

import app.simt.echo.platform.NfcCapabilities
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.NfcPairingController
import app.simt.echo.platform.FakeNfcPairingController
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.FileSharing
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.ECHO_MIME
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.TransferProgress
import app.simt.echo.transfer.NfcReadiness
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingMethod
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

/**
 * W21-01 gate: Share behaves the same whichever method is selected.
 *
 * The generator is asserted to run exactly once per real offer: switching
 * methods is a presentation change, so the token, the key and the export
 * selection survive it. Readiness is asserted to require a confirmed
 * publication, and completion to require a download of our own offer.
 */
class SharePairingViewModelTest {

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

    private class FakeSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }

    private class CountingTransport : PeerTransport {
        var offers = 0
        var stopCalls = 0
        val servedGenerations = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        val progressFlow = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 8)

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            offers++
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
            error("Share never fetches")

        override fun stopOffer() {
            stopCalls++
        }

        override val servedOffers: Flow<Long> get() = servedGenerations

        override val progress: Flow<TransferProgress> get() = progressFlow
    }

    private class FakeRelay(private val code: String = "ABCD2345") : RelayClient {
        var uploads = 0
        override suspend fun upload(sealed: ByteArray): String {
            uploads++
            return code
        }

        override suspend fun download(code: String): ByteArray = error("unused")
    }

    private data class Rig(
        val service: TransferService,
        val transport: CountingTransport,
        val relay: FakeRelay? = null,
    )

    private fun rig(qualifiedRelay: Boolean = false): Rig {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val transport = CountingTransport()
        val relay = if (qualifiedRelay) FakeRelay() else null
        val service = TransferService(
            transport = transport,
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
            relay = relay,
            peerRelayQualified = qualifiedRelay,
            ioDispatcher = Dispatchers.Unconfined,
        )
        return Rig(service, transport, relay)
    }

    private fun viewModel(
        rig: Rig,
        pairing: NfcPairingController,
        haptics: Haptics = FakeHaptics(),
    ) = ShareViewModel(
        routeId = Fixtures.L_ROUTE.routeId,
        service = rig.service,
        haptics = haptics,
        sharing = FakeSharing(),
        pairing = pairing,
    )

    private fun capabilities(
        canRead: Boolean = true,
        canPresent: Boolean = true,
        enabled: Boolean = true,
    ) = NfcCapabilities(canRead = canRead, canPresent = canPresent, enabled = enabled)

    @Test
    fun aNewShareOpensInNfcAndPublishesOnlyAfterSealing() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities())
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()

        assertEquals(PairingMethod.NFC, vm.ui.value.method)
        val state = vm.ui.value
        assertNotNull(state.qrUri)
        assertEquals(1, rig.transport.offers, "one invitation for this visit")
        // Published, but not yet ready: a request is not a confirmed offer.
        assertEquals(1, pairing.published.size)
        assertEquals(state.qrUri, pairing.published.single().second)
        assertFalse(state.readyToTap)

        pairing.confirmPublished(state.offerGeneration)
        advanceUntilIdle()
        assertTrue(vm.ui.value.readyToTap)
        assertEquals(NfcReadiness.ARMED, vm.ui.value.nfcReadiness)
    }

    @Test
    fun switchingMethodsNeverSealsAgainOrChangesTheInvitation() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities())
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        val first = vm.ui.value
        val originalPayload = QrPayload.parse(first.qrUri!!).getOrThrow()

        vm.selectMethod(PairingMethod.QR)
        advanceUntilIdle()
        // QR detaches the presentation without touching the offer.
        assertEquals(first.qrUri, vm.ui.value.qrUri)
        assertEquals(1, rig.transport.offers)
        assertEquals(listOf(first.offerGeneration), pairing.clearedGenerations)

        vm.selectMethod(PairingMethod.NFC)
        advanceUntilIdle()
        val again = vm.ui.value
        assertEquals(first.qrUri, again.qrUri, "the same invitation, two representations")
        assertEquals(1, rig.transport.offers, "switching is not a re-offer")
        assertEquals(originalPayload, QrPayload.parse(again.qrUri!!).getOrThrow())
        // Re-published for the same generation, so a tap still works.
        assertEquals(2, pairing.published.size)
        assertEquals(first.offerGeneration, pairing.published.last().first)
    }

    @Test
    fun anUnsupportedPhoneKeepsQrAsOneActionAndSaysWhy() = runTest(dispatcher) {
        val rig = rig()
        // A reader-only phone: NFC receive works, sending does not.
        val pairing = FakeNfcPairingController(capabilities(canPresent = false))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()

        assertEquals(NfcReadiness.UNAVAILABLE, vm.ui.value.nfcReadiness)
        assertTrue(pairing.published.isEmpty(), "nothing is published where HCE is absent")
        assertNotNull(vm.ui.value.qrUri, "QR stays usable without NFC")

        val state = vm.ui.value
        pairing.publish(state.qrUri!!, state.offerGeneration)
        pairing.failPublication(state.offerGeneration, PairingFailure.Unsupported)
        advanceUntilIdle()
        assertFalse(vm.ui.value.readyToTap, "a refused publication is never ready")
        assertEquals(PairingFailure.Unsupported, vm.ui.value.tapFailure)

        vm.selectMethod(PairingMethod.QR)
        advanceUntilIdle()
        assertEquals(PairingMethod.QR, vm.ui.value.method)
        assertNull(vm.ui.value.tapFailure, "switching method clears the tap failure")
    }

    @Test
    fun aDisabledAdapterIsExplainableAndRecovers() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities(enabled = false))
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()

        assertEquals(NfcReadiness.DISABLED, vm.ui.value.nfcReadiness)
        assertTrue(pairing.published.isEmpty())

        // The user turns NFC on and comes back: the offer is published.
        pairing.setCapabilities(capabilities(enabled = true))
        advanceUntilIdle()
        assertEquals(NfcReadiness.PREPARING, vm.ui.value.nfcReadiness)
        assertEquals(1, pairing.published.size)
    }

    @Test
    fun aPrivacyChangeInvalidatesBothRenderingsBeforeReplacingThem() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities())
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        val first = vm.ui.value
        pairing.confirmPublished(first.offerGeneration)
        advanceUntilIdle()

        vm.toggleAsset("start", false)
        // Synchronously: no stale QR, no stale tap, no ready state.
        assertNull(vm.ui.value.qrUri)
        assertFalse(vm.ui.value.readyToTap)
        assertEquals(listOf(first.offerGeneration), pairing.clearedGenerations)

        advanceUntilIdle()
        val second = vm.ui.value
        assertNotNull(second.qrUri)
        assertEquals(2, rig.transport.offers, "the replacement is a real new offer")
        assertTrue(second.offerGeneration > first.offerGeneration, "a new generation owns it")
        assertEquals(2, pairing.published.size)
        assertEquals(second.offerGeneration, pairing.published.last().first)

        // The replaced generation can no longer claim readiness.
        pairing.confirmPublished(first.offerGeneration)
        advanceUntilIdle()
        assertFalse(vm.ui.value.readyToTap, "a late confirmation for the old offer is ignored")
    }

    @Test
    fun aTapSaysDetailsWereSharedAndIsNeverCompletion() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities())
        val haptics = FakeHaptics()
        val vm = viewModel(rig, pairing, haptics)
        advanceUntilIdle()
        val generation = vm.ui.value.offerGeneration
        pairing.confirmPublished(generation)
        advanceUntilIdle()

        pairing.emit(NfcEvent.InvitationRead(generation))
        advanceUntilIdle()

        assertTrue(vm.ui.value.tapRead)
        assertFalse(vm.ui.value.sent, "an invitation read is not a received route")
        assertTrue(haptics.played.isEmpty(), "no completion haptic on a tap")

        // A read for a replaced offer must not mark the current one.
        vm.toggleAsset("start", false)
        advanceUntilIdle()
        pairing.emit(NfcEvent.InvitationRead(generation))
        advanceUntilIdle()
        assertFalse(vm.ui.value.tapRead, "a replaced offer's read is not ours")
    }

    @Test
    fun completionNeedsOurOwnDownloadsEvidenceAndConfirmsOnce() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities())
        val haptics = FakeHaptics()
        val vm = viewModel(rig, pairing, haptics)
        advanceUntilIdle()
        val generation = vm.ui.value.offerGeneration

        // A download attributed to some other offer is not completion.
        rig.transport.servedGenerations.emit(generation + 99)
        advanceUntilIdle()
        assertFalse(vm.ui.value.sent)
        assertTrue(haptics.played.isEmpty())

        // Duplicate signals confirm once: the screen animates once.
        rig.transport.servedGenerations.emit(generation)
        rig.transport.servedGenerations.emit(generation)
        advanceUntilIdle()
        assertTrue(vm.ui.value.sent)
        assertEquals(listOf(HapticPattern.CONFIRM), haptics.played)
    }

    @Test
    fun aQualifiedRelayReplacementPublishesExactlyOneNewOffer() = runTest(dispatcher) {
        val rig = rig(qualifiedRelay = true)
        val pairing = FakeNfcPairingController(capabilities())
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        assertTrue(vm.ui.value.relayAvailable)
        val before = vm.ui.value

        vm.useInternetRelay()
        advanceUntilIdle()

        val after = vm.ui.value
        assertTrue(after.relayOffered)
        assertEquals(2, rig.transport.offers)
        assertEquals(1, rig.relay?.uploads)
        assertEquals("ABCD2345", QrPayload.parse(after.qrUri!!).getOrThrow().relayCode)
        assertEquals(2, pairing.published.size)
        assertEquals(after.offerGeneration, pairing.published.last().first)
        assertTrue(after.offerGeneration > before.offerGeneration)
        assertEquals(listOf(before.offerGeneration), pairing.clearedGenerations)
    }

    @Test
    fun anUnqualifiedRelayIsNeverOfferedSoNothingUploads() = runTest(dispatcher) {
        // Configured but unusable on this backend: the action must hide.
        val rig = rig(qualifiedRelay = false)
        val pairing = FakeNfcPairingController(capabilities())
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        assertFalse(vm.ui.value.relayAvailable)

        vm.useInternetRelay()
        advanceUntilIdle()
        assertFalse(vm.ui.value.relayOffered)
    }

    @Test
    fun stoppingPublishesNothingAndStaysReusable() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities())
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        val first = vm.ui.value
        pairing.confirmPublished(first.offerGeneration)
        advanceUntilIdle()

        vm.onPaused()
        advanceUntilIdle()
        assertFalse(vm.ui.value.readyToTap, "a paused screen is never ready to tap")
        assertEquals(listOf(first.offerGeneration), pairing.clearedGenerations)
        assertEquals(1, rig.transport.stopCalls)

        // The destination survived: coming back offers afresh.
        vm.onResumed()
        advanceUntilIdle()
        val resumed = vm.ui.value
        assertNotNull(resumed.qrUri)
        assertEquals(2, rig.transport.offers)
        assertTrue(resumed.offerGeneration > first.offerGeneration)
        assertFalse(resumed.sent)
    }

    @Test
    fun closingEndsTheVisitAndIgnoresLateEvents() = runTest(dispatcher) {
        val rig = rig()
        val pairing = FakeNfcPairingController(capabilities())
        val vm = viewModel(rig, pairing)
        advanceUntilIdle()
        val generation = vm.ui.value.offerGeneration

        vm.close()
        advanceUntilIdle()
        assertEquals(1, pairing.closeCalls)
        assertFalse(vm.ui.value.readyToTap)

        val snapshot = vm.ui.value
        pairing.confirmPublished(generation)
        pairing.emit(NfcEvent.InvitationRead(generation))
        rig.transport.servedGenerations.emit(generation)
        advanceUntilIdle()

        assertEquals(snapshot, vm.ui.value, "a closed visit cannot be republished or completed")
        // Closing twice is safe.
        vm.close()
        assertEquals(1, pairing.closeCalls)
    }
}
