package app.simt.echo.transfer

import app.simt.echo.platform.NfcCapabilities
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.UnavailableNfcPairingController
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W20-01 gate: the pairing policy is pure and the seam is honest.
 *
 * Nothing here touches a radio. These cases pin the two per-visit
 * defaults, the role-specific capability mapping (receive works without
 * HCE), and the guarantee that a controller never reports ready or
 * supports anything it cannot do — including iOS, where all of it is
 * unsupported rather than merely unwired.
 */
class PairingPolicyTest {

    private val readOnly = NfcCapabilities(canRead = true, canPresent = false, enabled = true)
    private val bothOff = NfcCapabilities(canRead = true, canPresent = true, enabled = false)
    private val bothOn = NfcCapabilities(canRead = true, canPresent = true, enabled = true)

    @Test
    fun everyNewVisitRestoresItsRoleDefault() {
        // Defaults are per-visit, not remembered: both are asked fresh each time.
        // Both roles open in NFC: the receiver's reader must be armed by
        // default, because a disarmed reader is indistinguishable from a
        // dead radio (plan 0005 §1, revised 2026-11-05).
        assertEquals(PairingMethod.NFC, PairingPolicy.defaultMethod(PairingRole.SHARE))
        assertEquals(PairingMethod.NFC, PairingPolicy.defaultMethod(PairingRole.RECEIVE))
        assertEquals(PairingMethod.NFC, PairingPolicy.defaultMethod(PairingRole.SHARE))
        assertEquals(PairingMethod.NFC, PairingPolicy.defaultMethod(PairingRole.RECEIVE))
    }

    @Test
    fun receiveWorksOnAReaderWithoutHceWhileShareDoesNot() {
        // HCE absence only costs sending (plan §1 behavior matrix).
        assertTrue(PairingPolicy.supportedBy(PairingRole.RECEIVE, readOnly))
        assertFalse(PairingPolicy.supportedBy(PairingRole.SHARE, readOnly))
        assertEquals(NfcReadiness.PREPARING, PairingPolicy.readiness(PairingRole.RECEIVE, readOnly, PairingPhase.PREPARING))
        assertEquals(NfcReadiness.UNAVAILABLE, PairingPolicy.readiness(PairingRole.SHARE, readOnly, PairingPhase.PREPARING))
        // Receive can still reach armed; send cannot.
        assertEquals(NfcReadiness.ARMED, PairingPolicy.readiness(PairingRole.RECEIVE, readOnly, PairingPhase.ARMED))
        // QR stays selectable on both sides — it is the fallback, not a gap.
        assertTrue(PairingPolicy.selectable(PairingRole.SHARE, PairingMethod.QR, readOnly))
    }

    @Test
    fun disabledAdapterIsDisabledNotUnavailable() {
        // The distinction is the user's recovery: Settings vs another method.
        assertEquals(NfcReadiness.DISABLED, PairingPolicy.readiness(PairingRole.SHARE, bothOff, PairingPhase.ARMED))
        assertEquals(NfcReadiness.DISABLED, PairingPolicy.readiness(PairingRole.RECEIVE, bothOff, PairingPhase.ARMED))
        // Hardware exists, so the selector still offers NFC; it explains instead.
        assertTrue(PairingPolicy.selectable(PairingRole.SHARE, PairingMethod.NFC, bothOff))
        assertFalse(PairingPolicy.availableNow(PairingRole.SHARE, bothOff))
    }

    @Test
    fun phoneWithoutTheRadioCannotSelectNfcButKeepsQr() {
        assertTrue(NfcCapabilities.NONE.unsupported)
        assertEquals(NfcReadiness.UNAVAILABLE, PairingPolicy.readiness(PairingRole.SHARE, NfcCapabilities.NONE, PairingPhase.ARMED))
        assertEquals(NfcReadiness.UNAVAILABLE, PairingPolicy.readiness(PairingRole.RECEIVE, NfcCapabilities.NONE, PairingPhase.IDLE))
        assertFalse(PairingPolicy.selectable(PairingRole.SHARE, PairingMethod.NFC, NfcCapabilities.NONE))
        assertFalse(PairingPolicy.selectable(PairingRole.RECEIVE, PairingMethod.NFC, NfcCapabilities.NONE))
        assertTrue(PairingPolicy.selectable(PairingRole.RECEIVE, PairingMethod.QR, NfcCapabilities.NONE))
        assertFalse(PairingPolicy.availableNow(PairingRole.RECEIVE, NfcCapabilities.NONE))
    }

    @Test
    fun armedRequiresHardwareAndTheAdapter() {
        assertEquals(NfcReadiness.ARMED, PairingPolicy.readiness(PairingRole.SHARE, bothOn, PairingPhase.ARMED))
        // Idle is not armed: choosing QR keeps the phase idle and never claims ready.
        assertEquals(NfcReadiness.PREPARING, PairingPolicy.readiness(PairingRole.SHARE, bothOn, PairingPhase.IDLE))
    }

    @Test
    fun unavailableControllerNeverClaimsReadyAndNeverLeaksTheInvitation() = runTest {
        val controller = UnavailableNfcPairingController()
        assertEquals(NfcCapabilities.NONE, controller.capabilities.value)
        assertTrue(controller.capabilities.value.unsupported)

        controller.publish("simt-echo://r?v=1&t=token&k=key", offerGeneration = 7L)
        val publication = controller.publication.value
        assertFalse(publication.ready, "a request is not a ready publication")
        assertEquals(PairingFailure.Unsupported, publication.failure)
        // No events are invented and nothing is ever reading.
        assertNull(controller.events.firstOrNull())
        controller.startReading(1L)
        controller.stopReading(1L)
    }

    @Test
    fun teardownIsIdempotentAndStaleGenerationsCannotClearANewerOffer() = runTest {
        val controller = app.simt.echo.platform.FakeNfcPairingController()
        controller.publish("uri-a", offerGeneration = 1L)
        controller.publish("uri-b", offerGeneration = 2L)

        // An old clear (offer 1 replaced by offer 2) must not stop offer 2.
        controller.clearPublishedOffer(1L)
        assertEquals(2L, controller.publication.value.generation)
        assertFalse(controller.publication.value.ready)

        controller.confirmPublished(2L)
        assertTrue(controller.publication.value.ready)

        // The owning generation still clears it.
        controller.clearPublishedOffer(2L)
        assertEquals(0L, controller.publication.value.generation)
        assertEquals(listOf(1L, 2L), controller.clearedGenerations)

        // Closing twice is harmless: leaving a screen must never throw.
        controller.close()
        controller.close()
        assertEquals(2, controller.closeCalls)
        assertEquals(listOf(1L, 2L), controller.published.map { it.first })
    }

    @Test
    fun invitationEventsAreRedactedInDiagnostics() {
        // An invitation is a bearer capability: formatting must not leak it.
        val event = NfcEvent.Invitation(attemptId = 3L, uri = "simt-echo://r?v=1&t=secret-token&k=secret-key")
        val text = event.toString()
        assertFalse(text.contains("secret-token"), text)
        assertFalse(text.contains("secret-key"), text)
        assertTrue(text.contains("redacted"), text)
        // Failures carry no message at all.
        assertFalse(PairingFailure.Internal.toString().contains(":"))
        assertEquals("Unsupported", PairingFailure.Unsupported.toString())
    }

    @Test
    fun fakeControllerSeparatesRequestFromConfirmation() = runTest {
        val controller = app.simt.echo.platform.FakeNfcPairingController()
        controller.publish("uri", offerGeneration = 4L)
        assertFalse(controller.publication.value.ready)
        controller.confirmPublished(4L)
        assertTrue(controller.publication.value.ready)
        // A confirmation for some other generation changes nothing.
        controller.confirmPublished(5L)
        assertEquals(4L, controller.publication.value.generation)
    }
}
