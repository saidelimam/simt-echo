package app.simt.echo.platform.nfc

import app.simt.echo.transfer.NfcApduPresenter
import app.simt.echo.transfer.NfcApduProtocol
import app.simt.echo.transfer.NfcInvitationAssembler
import app.simt.echo.transfer.NfcOfferSnapshot
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W20-04 gate: a presentation policy, not a radio.
 *
 * Every case here is "should this APDU be answered at all": cold process,
 * QR mode, background, lock, expiry and a replaced selection. RF routing
 * and real wallets remain W21-05 device evidence — this proves the app
 * never claims more than the registry state allows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class NfcPresenterTest {

    private val shortInvitation =
        "simt-echo://r?v=1&n=Tap".encodeToByteArray()

    private val longInvitation =
        ("simt-echo://r?v=1&n=" + "🚪".repeat(120)).encodeToByteArray()

    private var now = 1_000L
    private var interactive = true
    private val reads = mutableListOf<Long>()
    private lateinit var presenter: NfcApduPresenter

    @Before
    fun setUp() {
        ActiveNfcOfferRegistry.clearAll()
        reads.clear()
        now = 1_000L
        interactive = true
        presenter = EchoTapPresentation.presenter(
            nowMs = { now },
            interactive = { interactive },
            onInvitationRead = { reads += it },
        )
    }

    private fun offer(
        generation: Long = 1L,
        bytes: ByteArray = shortInvitation,
        expiresAt: Long = 10_000L,
    ) = NfcOfferSnapshot(
        generation = generation,
        offerId = ByteArray(NfcApduProtocol.OFFER_ID_BYTES) { (it + 1).toByte() },
        invitation = bytes,
        expiresAtMonotonicMs = expiresAt,
    )

    private fun arm(generation: Long = 1L, bytes: ByteArray = shortInvitation) {
        ActiveNfcOfferRegistry.publish(offer(generation, bytes))
        ActiveNfcOfferRegistry.arm()
    }

    private fun statusOf(reply: ByteArray): Int = NfcApduProtocol.statusOf(reply)

    private fun selectStatus(): Int = statusOf(presenter.respond(NfcApduProtocol.selectCommand()))

    @Test
    fun aColdProcessServesNothing() {
        // No snapshot is ever loaded from disk: a restarted process has no
        // offer at all, which is the honest 6985 rather than a stale invite.
        assertEquals(NfcApduProtocol.SW_NO_OFFER, selectStatus())
        assertEquals(
            NfcApduProtocol.SW_NO_OFFER,
            statusOf(presenter.respond(NfcApduProtocol.readCommand(0, 16))),
        )
    }

    @Test
    fun onlyAnArmedOfferIsReadable() {
        arm()
        // Paused, locked method switch, or QR selected: the controller
        // disarms, and the offer becomes unreadable even though it exists.
        ActiveNfcOfferRegistry.disarm()
        assertEquals(NfcApduProtocol.SW_NO_OFFER, selectStatus())

        ActiveNfcOfferRegistry.arm()
        assertEquals(NfcApduProtocol.SW_OK, selectStatus())
    }

    @Test
    fun aLockedOrSleepingPhoneServesNothing() {
        arm()
        interactive = false
        assertEquals(NfcApduProtocol.SW_NO_OFFER, selectStatus())

        interactive = true
        assertEquals(NfcApduProtocol.SW_OK, selectStatus())
    }

    @Test
    fun anExpiredOfferServesNothing() {
        arm()
        now = 10_000L
        assertEquals(NfcApduProtocol.SW_NO_OFFER, selectStatus())
    }

    @Test
    fun aChangedSelectionCannotBeStitchedOntoTheOldOne() {
        arm(generation = 1L, bytes = longInvitation)
        assertEquals(NfcApduProtocol.SW_OK, selectStatus())
        presenter.respond(NfcApduProtocol.readCommand(0, 224))

        // The user toggled a crop: a new offer replaces the old one.
        val replacement = ByteArray(300) { 'z'.code.toByte() }
        ActiveNfcOfferRegistry.publish(offer(generation = 2L, bytes = replacement))
        ActiveNfcOfferRegistry.arm()

        assertEquals(
            NfcApduProtocol.SW_NO_OFFER,
            statusOf(presenter.respond(NfcApduProtocol.readCommand(224, 76))),
            "a transaction cannot continue into a different invitation",
        )
        // A fresh SELECT serves the new selection from byte zero.
        assertEquals(NfcApduProtocol.SW_OK, selectStatus())
    }

    @Test
    fun aStaleClearLeavesTheNewerOfferArmed() {
        arm(generation = 2L)
        ActiveNfcOfferRegistry.clear(generation = 1L)
        assertNotNull(ActiveNfcOfferRegistry.current())
        assertEquals(NfcApduProtocol.SW_OK, selectStatus())
    }

    @Test
    fun theWholeInvitationCrossesAndIsObservedOnce() {
        arm(bytes = longInvitation)
        val declared = NfcApduProtocol.parseSelectResponse(
            presenter.respond(NfcApduProtocol.selectCommand()),
        ).length
        val assembler = NfcInvitationAssembler(declared)

        var offset = 0
        while (offset < declared) {
            val count = minOf(NfcApduProtocol.MAX_READ_BYTES, declared - offset)
            val chunk = NfcApduProtocol.parseReadResponse(
                presenter.respond(NfcApduProtocol.readCommand(offset, count)),
                count,
            )
            assembler.accept(offset, chunk)
            offset += count
        }

        assertTrue(assembler.complete)
        assertContentEquals(longInvitation, assembler.bytes())
        assertEquals(listOf(1L), reads, "one tap is one observation")
    }

    @Test
    fun theHostedServiceAnswersFromTheRegistryAndForgetsOnDestroy() {
        arm()
        val service = Robolectric.buildService(EchoHostApduService::class.java).create().get()
        assertEquals(
            NfcApduProtocol.SW_OK,
            statusOf(service.processCommandApdu(NfcApduProtocol.selectCommand(), null)),
        )

        // Service recreation with no publication (cold start) serves nothing.
        service.onDestroy()
        val recreated = Robolectric.buildService(EchoHostApduService::class.java).create().get()
        assertEquals(
            NfcApduProtocol.SW_NO_OFFER,
            statusOf(recreated.processCommandApdu(NfcApduProtocol.selectCommand(), null)),
        )
    }

    @Test
    fun theServiceRefusesAPeerWhenNothingIsArmedEvenWithASnapshot() {
        // Published but never armed: the service is bound, an offer exists,
        // and the answer is still 6985.
        ActiveNfcOfferRegistry.publish(offer())
        val service = Robolectric.buildService(EchoHostApduService::class.java).create().get()
        assertEquals(
            NfcApduProtocol.SW_NO_OFFER,
            statusOf(service.processCommandApdu(NfcApduProtocol.selectCommand(), null)),
        )
    }
}
