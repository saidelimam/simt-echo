package app.simt.echo.platform

import androidx.compose.runtime.Composable
import app.simt.echo.transfer.PairingFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What this phone's NFC stack reports (plan 0005 §4.1). Presence of the
 * radio and presence of the card-emulation component are separate: a phone
 * that can read has [canRead] without [canPresent], and only sending needs
 * HCE. [enabled] is the adapter state, which the user can change at any
 * time — rechecked on resume and on adapter broadcasts, never cached.
 */
data class NfcCapabilities(
    val canRead: Boolean = false,
    val canPresent: Boolean = false,
    val enabled: Boolean = false,
) {
    /** Both directions of the shipping feature are unsupported here. */
    val unsupported: Boolean get() = !canRead && !canPresent

    companion object {
        /** No NFC stack at all (iOS, emulators, phones without the radio). */
        val NONE = NfcCapabilities()
    }
}

/**
 * Publication state of one offer generation on the presenting side.
 *
 * [ready] is true only after the platform confirmed the offer is actually
 * being served; "we asked" is a different value on purpose, so the UI can
 * never render "Ready to tap" for a request that failed.
 */
data class NfcPublication(
    val generation: Long = 0L,
    val ready: Boolean = false,
    val failure: PairingFailure? = null,
) {
    companion object {
        val IDLE = NfcPublication()
    }
}

/**
 * Typed NFC events, each tagged with the generation that owns it.
 *
 * A late callback from a replaced offer or a cancelled read must be
 * discardable by comparing generations — never applied to whatever is
 * current at collection time.
 *
 * [Invitation] carries a bearer capability (the v1 pairing URI). Its
 * formatting is redacted so a log line or a crash report cannot leak the
 * token or the AES key.
 */
sealed class NfcEvent {

    /** A reader connected; the invitation is arriving. */
    data class Reading(val attemptId: Long) : NfcEvent()

    /** A complete, validated invitation was read. */
    data class Invitation(val attemptId: Long, val uri: String) : NfcEvent() {
        override fun toString(): String = "Invitation(attemptId=$attemptId, uri=<redacted>)"
    }

    /** The read ended without an invitation. */
    data class ReadFailed(val attemptId: Long, val failure: PairingFailure) : NfcEvent()

    /** Our published invitation was read by a peer. Not proof of a saved route. */
    data class InvitationRead(val offerGeneration: Long) : NfcEvent()

    /** Publication confirmed: the offer is being served now. */
    data class Published(val offerGeneration: Long) : NfcEvent()

    /** Publication could not be established; nothing is being served. */
    data class PublishFailed(val offerGeneration: Long, val failure: PairingFailure) : NfcEvent()
}

/**
 * Foreground NFC pairing, as the screens use it (plan 0005 §4.1).
 *
 * The controller is a device service, like the camera or the motion source:
 * it is created in the composition where the Activity and lifecycle exist,
 * never in `AppGraph`. Commands are idempotent and generation-scoped, so a
 * stale caller cannot stop a newer offer or clear a newer publication.
 *
 * Only one reader job and one publication can exist at a time; arming both
 * roles on one phone is never valid because Android reader mode disables
 * local card emulation.
 */
interface NfcPairingController {

    /** Live hardware/adapter state; updated on resume and adapter changes. */
    val capabilities: StateFlow<NfcCapabilities>

    /** Publication state of the current offer generation. */
    val publication: StateFlow<NfcPublication>

    /** Typed acquisition events, tagged with their generation. */
    val events: Flow<NfcEvent>

    /**
     * Offers [invitation] to a foreground reader under [offerGeneration].
     * Replaces any previous publication. Idempotent per generation.
     */
    fun publish(invitation: String, offerGeneration: Long)

    /**
     * Arms the reader for [attemptId], disconnecting any previous attempt.
     * The caller stops the QR scanner first: reader mode and card emulation
     * cannot both be armed on one phone.
     */
    fun startReading(attemptId: Long)

    /** Disarms the reader if [attemptId] is still the active attempt. */
    fun stopReading(attemptId: Long)

    /** Stops serving, only if [offerGeneration] is still the live publication. */
    fun clearPublishedOffer(offerGeneration: Long)

    /** Release everything: readers, publications and platform listeners. */
    fun close()
}

/**
 * A controller for a phone with no usable NFC stack (iOS in v1, Android
 * while its HCE presenter is not installed, emulators).
 *
 * Every command is a no-op and nothing is ever reported ready: this is the
 * honest implementation of "we cannot do this here", not a stub that
 * pretends. An attempt to publish fails with [PairingFailure.Unsupported]
 * so the UI can say why instead of waiting forever.
 */
class UnavailableNfcPairingController(
    private val failure: PairingFailure = PairingFailure.Unsupported,
) : NfcPairingController {

    private val _publication = MutableStateFlow(NfcPublication())

    override val capabilities: StateFlow<NfcCapabilities> =
        MutableStateFlow(NfcCapabilities.NONE).asStateFlow()

    override val publication: StateFlow<NfcPublication> = _publication.asStateFlow()

    override val events: Flow<NfcEvent> = emptyFlow()

    override fun publish(invitation: String, offerGeneration: Long) {
        _publication.value = NfcPublication(
            generation = offerGeneration,
            ready = false,
            failure = failure,
        )
    }

    override fun startReading(attemptId: Long) = Unit

    override fun stopReading(attemptId: Long) = Unit

    override fun clearPublishedOffer(offerGeneration: Long) {
        if (_publication.value.generation == offerGeneration) {
            _publication.value = NfcPublication.IDLE
        }
    }

    override fun close() = Unit
}

/**
 * The NFC pairing service for the current composition (plan 0005 §4.1).
 *
 * A composable factory, matching [rememberCameraRecorder] and friends: the
 * Android implementation needs the resumed Activity and its lifecycle, so
 * it cannot be built in the graph. Tests inject a fake controller instead.
 */
@Composable
expect fun rememberNfcPairingController(): NfcPairingController

/**
 * The user's own route to the NFC toggle (plan 0005 §2).
 *
 * The app never flips the adapter itself: this opens the system NFC
 * settings screen, falling back to the wireless settings in it where a
 * build does not expose the narrower screen. Platforms without NFC return
 * a no-op, which pairs with a selector that never offers the action.
 */
@Composable
expect fun rememberNfcSettingsOpener(): () -> Unit
