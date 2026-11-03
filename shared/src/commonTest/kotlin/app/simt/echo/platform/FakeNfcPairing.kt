package app.simt.echo.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic [NfcPairingController] for tests (plan 0005 W20-01).
 *
 * It never invents progress: publication moves to ready only when the test
 * calls [confirmPublished], and events are emitted only when the test calls
 * [emit]. Generations are explicit, so a test can prove that a late
 * callback from a replaced offer or a cancelled read is ignored.
 */
class FakeNfcPairingController(
    initial: NfcCapabilities = NfcCapabilities(canRead = true, canPresent = true, enabled = true),
) : NfcPairingController {

    private val _capabilities = MutableStateFlow(initial)
    private val _publication = MutableStateFlow(NfcPublication())
    private val _events = MutableSharedFlow<NfcEvent>(extraBufferCapacity = 64)

    override val capabilities: StateFlow<NfcCapabilities> = _capabilities.asStateFlow()
    override val publication: StateFlow<NfcPublication> = _publication.asStateFlow()
    override val events: Flow<NfcEvent> = _events

    /** Generation → invitation, in publish order. The URI never leaves here. */
    val published = mutableListOf<Pair<Long, String>>()

    /** Generations passed to [clearPublishedOffer], including ignored ones. */
    val clearedGenerations = mutableListOf<Long>()

    val readingAttempts = mutableListOf<Long>()
    val stoppedAttempts = mutableListOf<Long>()

    var closeCalls = 0
        private set

    override fun publish(invitation: String, offerGeneration: Long) {
        published += offerGeneration to invitation
        _publication.value = NfcPublication(generation = offerGeneration, ready = false)
    }

    /** The platform confirmed serving — the only path to a ready publication. */
    fun confirmPublished(offerGeneration: Long) {
        if (_publication.value.generation == offerGeneration) {
            _publication.value = NfcPublication(generation = offerGeneration, ready = true)
        }
        _events.tryEmit(NfcEvent.Published(offerGeneration))
    }

    /** The platform refused to serve this generation. */
    fun failPublication(offerGeneration: Long, failure: app.simt.echo.transfer.PairingFailure) {
        _publication.value = NfcPublication(
            generation = offerGeneration,
            ready = false,
            failure = failure,
        )
        _events.tryEmit(NfcEvent.PublishFailed(offerGeneration, failure))
    }

    override fun startReading(attemptId: Long) {
        readingAttempts += attemptId
    }

    override fun stopReading(attemptId: Long) {
        stoppedAttempts += attemptId
    }

    override fun clearPublishedOffer(offerGeneration: Long) {
        clearedGenerations += offerGeneration
        if (_publication.value.generation == offerGeneration) {
            _publication.value = NfcPublication.IDLE
        }
    }

    override fun close() {
        closeCalls++
    }

    /** Test-controlled callback, exactly as the radio would deliver it. */
    fun emit(event: NfcEvent) {
        _events.tryEmit(event)
    }

    /** Test-controlled adapter change (user turns NFC off in Settings). */
    fun setCapabilities(next: NfcCapabilities) {
        _capabilities.value = next
    }
}
