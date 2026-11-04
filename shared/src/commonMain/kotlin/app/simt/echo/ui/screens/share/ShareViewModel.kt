package app.simt.echo.ui.screens.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.platform.ECHO_MIME
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.NfcPairingController
import app.simt.echo.platform.TransferProgress
import app.simt.echo.platform.UnavailableNfcPairingController
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.transfer.ExpiryOption
import app.simt.echo.transfer.ExportItem
import app.simt.echo.transfer.ExportSelection
import app.simt.echo.transfer.ExportSummary
import app.simt.echo.transfer.NfcReadiness
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingMethod
import app.simt.echo.transfer.PairingPhase
import app.simt.echo.transfer.PairingPolicy
import app.simt.echo.transfer.PairingRole
import app.simt.echo.transfer.ProgressRateTracker
import app.simt.echo.transfer.START_PHOTO_ITEM_ID
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.copy.shareErrorCopy
import app.simt.echo.ui.copy.shareExportErrorCopy
import app.simt.echo.ui.copy.shareUpdateErrorCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Share screen state (plan §8.3): QR offer, then "Sent ✓" on download.
 *
 * Plan 0005 adds the pairing method for this visit: Share opens in NFC,
 * both representations of one invitation are always available, and the
 * generation is what decides whether a tap, a download or a publication
 * result belongs to the offer being shown.
 */
data class ShareUiState(
    val qrUri: String? = null,
    val sent: Boolean = false,
    val error: String? = null,
    /** Internet relay configured (W4-05); the relay action hides without it. */
    val relayAvailable: Boolean = false,
    val relayOffered: Boolean = false,
    /** Privacy warning up (W2-07); no plain file exists until it is confirmed. */
    val fileWarning: Boolean = false,
    val fileShared: Boolean = false,
    /**
     * Opt-in to put perception data into the plain file. Off by default
     * and re-armed after every share: the file is unencrypted.
     */
    val filePerception: Boolean = false,
    /** Export preview rows (W3-08); empty until the route loads. */
    val exportItems: List<ExportItem> = emptyList(),
    /** Current preview-sheet choice; the offer always encodes this. */
    val selection: ExportSelection = ExportSelection(),
    /** Honest summary of everything the bundle carries. */
    val summary: ExportSummary? = null,
    /**
     * Live byte progress of the transfer in flight (W13-14); null before
     * any byte moves. Drives the particle flow — never synthesised.
     */
    val progress: TransferProgress? = null,
    /** Measured bytes/s behind [progress]; 0 means frozen light. */
    val bytesPerSecond: Double = 0.0,
    /** Method for THIS visit. NFC on every new Share; never remembered. */
    val method: PairingMethod = PairingMethod.NFC,
    /** What the selector may honestly say about NFC right now. */
    val nfcReadiness: NfcReadiness = NfcReadiness.PREPARING,
    /** Generation of the offer being served; 0 before the first one. */
    val offerGeneration: Long = 0L,
    /**
     * True only once the platform confirmed the offer is really being
     * served. A request is not readiness, so "Ready to tap" waits for this.
     */
    val readyToTap: Boolean = false,
    /**
     * A peer read the invitation: our details were supplied. This is never
     * "the route was received" — nothing acknowledges a save.
     */
    val tapRead: Boolean = false,
    /** The last NFC failure, as a typed value the screen turns into copy. */
    val tapFailure: PairingFailure? = null,
)

/** Plan copy for the W2-07 plain-file export warning. */
const val FILE_WARNING_COPY = "Not encrypted. Share only with people you trust."

/** Plan copy for the W3-08 raw-video note. */
const val RAW_VIDEO_NOTE = "Raw video is never shared — only the start photo and landmark crops you leave on."

class ShareViewModel(
    private val routeId: String,
    private val service: TransferService,
    private val haptics: Haptics,
    private val sharing: FileSharing,
    private val nowMs: () -> Long = { currentEpochMs() },
    /**
     * NFC presentation for this visit (plan 0005 §4.1). The default keeps
     * previews and tests honest: no capabilities, nothing ever ready.
     */
    private val pairing: NfcPairingController = UnavailableNfcPairingController(),
    private val role: PairingRole = PairingRole.SHARE,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        ShareUiState(method = PairingPolicy.defaultMethod(role)),
    )
    val ui: StateFlow<ShareUiState> = _ui.asStateFlow()

    private val rate = ProgressRateTracker(viewModelScope, nowMs)

    private var producerJob: Job? = null
    private var closed = false
    /** Privacy defaults apply to the first offer of the visit, never a resume. */
    private var defaultsApplied = false

    init {
        // Undispatched: subscribed before any offer can move bytes, so a
        // transport that emits without suspending loses nothing.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.progress.collect { emitted ->
                val measured = rate.record(emitted) { _ui.update { it.copy(bytesPerSecond = 0.0) } }
                _ui.update { it.copy(progress = emitted, bytesPerSecond = measured) }
            }
        }
        viewModelScope.launch {
            pairing.capabilities.collect { capabilities ->
                if (closed) return@collect
                refreshReadiness()
                val state = _ui.value
                // The adapter was switched back on (Settings, quick tile):
                // re-present the offer we already hold rather than seal a
                // new one.
                if (state.method == PairingMethod.NFC &&
                    !state.readyToTap &&
                    state.qrUri != null &&
                    PairingPolicy.availableNow(role, capabilities)
                ) {
                    publishIfSelected()
                }
            }
        }
        viewModelScope.launch {
            pairing.publication.collect { publication ->
                if (closed) return@collect
                _ui.update {
                    it.copy(
                        readyToTap = publication.ready &&
                            publication.generation == it.offerGeneration,
                    )
                }
                refreshReadiness()
            }
        }
        viewModelScope.launch {
            pairing.events.collect(::onPairingEvent)
        }
        // Completion needs transfer evidence attributed to our own offer:
        // an NFC read is not a download, and a replaced offer's late signal
        // is filtered by the service and checked again here.
        viewModelScope.launch {
            service.offerServed.collect { generation -> onServed(generation) }
        }
        start()
    }

    // ── method and presentation ─────────────────────────────────

    /**
     * Chooses the pairing method for this visit (plan 0005 §1).
     *
     * Switching only attaches or detaches a presentation of the *same*
     * invitation: it never seals again, never rotates the key or token, and
     * never restarts byte progress.
     */
    fun selectMethod(method: PairingMethod) {
        if (closed) return
        val current = _ui.value
        if (current.method == method) return
        // An unsupported choice is still selectable: the tap panel is where
        // the reason and the one-action way back to QR live (plan 0005 §2).
        // Nothing is published for it either way.
        _ui.update {
            it.copy(
                method = method,
                tapRead = false,
                tapFailure = null,
                readyToTap = false,
            )
        }
        when (method) {
            PairingMethod.NFC -> publishIfSelected()
            PairingMethod.QR -> detachNfc()
        }
        refreshReadiness()
    }

    /**
     * The destination came back to the foreground. A live offer is
     * re-presented exactly as it is: re-sealing would invalidate the
     * invitation the other phone may already hold and silently discard the
     * user's export choices (R14-05).
     */
    fun onResumed() {
        if (closed) return
        if (_ui.value.qrUri != null && service.currentOffer != null) {
            publishIfSelected()
            return
        }
        start()
    }

    /**
     * The destination left the foreground but may return. Only the NFC
     * presentation stops: the LAN offer keeps serving, because the peer may
     * already be downloading after a tap or a scan, and separating the phones
     * lowers this screen (plan 0005 §1, §4.5). [close] is what ends serving.
     */
    fun onPaused() {
        detachNfc()
    }

    private fun start() {
        if (closed) return
        if (producerJob?.isActive == true) return
        launchProducer(errorCopy = ::shareErrorCopy) {
            val items = service.describeExport(routeId)
            val selection = if (defaultsApplied) {
                _ui.value.selection
            } else {
                service.defaultSelectionFor(routeId)
            }
            defaultsApplied = true
            val summary = service.summaryFor(routeId, selection)
            // The preview describes the route, not the offer: publish it
            // before the offer completes, so a slow or failed offer can
            // never leave "What others receive" on "Loading export…".
            _ui.update {
                it.copy(
                    exportItems = items,
                    selection = selection,
                    summary = summary,
                )
            }
            val offer = service.offer(routeId, selection = selection)
            _ui.update {
                it.copy(
                    qrUri = offer.qrUri,
                    relayAvailable = service.relayAvailable,
                    offerGeneration = service.offerGeneration,
                    sent = false,
                    tapRead = false,
                    tapFailure = null,
                    error = null,
                )
            }
            publishIfSelected()
        }
    }

    /** Publishes over NFC only while that method is the selected one. */
    private fun publishIfSelected() {
        if (closed) return
        val state = _ui.value
        if (state.method != PairingMethod.NFC) return
        // Nothing is published where this phone cannot present: the screen
        // says why and offers QR instead of a tap that could never work.
        if (!PairingPolicy.availableNow(role, pairing.capabilities.value)) return
        val uri = state.qrUri ?: return
        pairing.publish(uri, state.offerGeneration)
    }

    /** Withdraws the NFC publication without touching the LAN offer. */
    private fun detachNfc() {
        val generation = _ui.value.offerGeneration
        if (generation != 0L) pairing.clearPublishedOffer(generation)
        _ui.update { it.copy(readyToTap = false) }
    }

    private fun refreshReadiness() {
        val state = _ui.value
        val phase = if (state.readyToTap) PairingPhase.ARMED else PairingPhase.IDLE
        _ui.update {
            it.copy(
                nfcReadiness = PairingPolicy.readiness(role, pairing.capabilities.value, phase),
            )
        }
    }

    private fun onPairingEvent(event: NfcEvent) {
        // A closed visit is gone for good: a late callback cannot revive it.
        if (closed) return
        val generation = _ui.value.offerGeneration
        when (event) {
            is NfcEvent.Published -> if (event.offerGeneration == generation) {
                _ui.update { it.copy(readyToTap = true, tapFailure = null) }
            }

            is NfcEvent.PublishFailed -> _ui.update {
                it.copy(readyToTap = false, tapFailure = event.failure)
            }

            // Details were supplied; the route has not been received. No
            // completion haptic and no `sent` — only a measured download is.
            is NfcEvent.InvitationRead -> if (event.offerGeneration == generation) {
                _ui.update { it.copy(tapRead = true) }
            }

            else -> Unit
        }
    }

    private fun onServed(generation: Long) {
        if (closed) return
        val state = _ui.value
        if (state.sent) return
        if (generation != state.offerGeneration) return
        _ui.update { it.copy(sent = true) }
        haptics.play(HapticPattern.CONFIRM)
    }

    // ── offer mutations ──────────────────────────────────────────

    /**
     * Preview-sheet toggle (W3-08): flips one asset, then re-offers so the
     * QR always encodes exactly the current selection. Fail-closed: both
     * representations are invalidated before the replacement is prepared.
     */
    fun toggleAsset(itemId: String, include: Boolean) {
        val current = _ui.value.selection
        val next = if (itemId == START_PHOTO_ITEM_ID) {
            current.copy(includeStartPhoto = include)
        } else {
            current.copy(
                excludedCropIds = if (include) current.excludedCropIds - itemId
                else current.excludedCropIds + itemId,
            )
        }
        if (next == current) return
        reoffer(next)
    }

    /** Expiry selector (W3-08): none / 24 h / 7 days, re-offered likewise. */
    fun setExpiry(option: ExpiryOption) {
        val current = _ui.value.selection
        if (current.expiry == option) return
        reoffer(current.copy(expiry = option))
    }

    private fun reoffer(selection: ExportSelection) {
        // Clear the stale (superset) QR + sent state before re-offering.
        clearProgress()
        // Invalidate both renderings first: nothing stale is shown or tapped.
        detachNfc()
        val summary = runCatching { service.summaryFor(routeId, selection) }.getOrNull()
        _ui.update {
            it.copy(
                selection = selection,
                summary = summary,
                qrUri = null,
                sent = false,
                tapRead = false,
                error = null,
                progress = null,
                bytesPerSecond = 0.0,
                offerGeneration = 0L,
            )
        }
        launchProducer(errorCopy = { shareUpdateErrorCopy() }) {
            val offer = service.offer(
                routeId,
                useRelay = _ui.value.relayOffered,
                selection = selection,
            )
            _ui.update {
                it.copy(
                    qrUri = offer.qrUri,
                    offerGeneration = service.offerGeneration,
                    error = null,
                )
            }
            publishIfSelected()
        }
    }

    /**
     * Re-offers through the internet relay (W4-05): the QR gains `c=` and a
     * receiver that cannot reach this phone falls back to the relay. The
     * previous QR stops working — this offer replaces it.
     */
    fun useInternetRelay() {
        if (_ui.value.relayOffered) return
        clearProgress()
        detachNfc()
        _ui.update {
            it.copy(
                qrUri = null,
                sent = false,
                tapRead = false,
                tapFailure = null,
                error = null,
                progress = null,
                bytesPerSecond = 0.0,
                offerGeneration = 0L,
            )
        }
        launchProducer(errorCopy = { shareUpdateErrorCopy() }) {
            val offer = service.offer(routeId, useRelay = true, selection = _ui.value.selection)
            _ui.update {
                it.copy(
                    qrUri = offer.qrUri,
                    relayOffered = true,
                    offerGeneration = service.offerGeneration,
                    error = null,
                )
            }
            publishIfSelected()
        }
    }

    /**
     * Retries a failed offer (W7-08): an offer failure (no route, no
     * Wi-Fi IP, transport error) is a recoverable state with Retry, never
     * a stuck "Starting offer…".
     */
    fun retryOffer() {
        if (_ui.value.qrUri != null) return
        clearProgress()
        _ui.update { it.copy(error = null, sent = false, progress = null, bytesPerSecond = 0.0) }
        launchProducer(errorCopy = ::shareErrorCopy) {
            val selection = _ui.value.selection
            val items = service.describeExport(routeId)
            val summary = service.summaryFor(routeId, selection)
            // Preview first, offer second — same ordering as [start]: the
            // route data must not wait on the offer's outcome.
            _ui.update {
                it.copy(
                    exportItems = items,
                    selection = selection,
                    summary = summary,
                )
            }
            val offer = service.offer(
                routeId,
                useRelay = _ui.value.relayOffered,
                selection = selection,
            )
            _ui.update {
                it.copy(
                    qrUri = offer.qrUri,
                    relayAvailable = service.relayAvailable,
                    offerGeneration = service.offerGeneration,
                    error = null,
                )
            }
            publishIfSelected()
        }
    }

    /**
     * Stops serving and publishing. Reusable: [onResumed] offers again, so a
     * destination that survives a pause is not permanently ended.
     */
    fun stop() {
        producerJob?.cancel()
        producerJob = null
        detachNfc()
        service.stopOffer()
        _ui.update { it.copy(readyToTap = false) }
    }

    /** Ends this visit for good: the screen is gone and will be rebuilt. */
    fun close() {
        if (closed) return
        closed = true
        stop()
        pairing.close()
        // The visit is over: no collector, timer or offer outlives it.
        viewModelScope.cancel()
    }

    // ── file fallback (unchanged) ────────────────────────────────

    /** Raises the privacy warning; nothing is written until [confirmFileShare]. */
    fun requestFileShare() {
        _ui.update { it.copy(fileWarning = true, filePerception = false, error = null) }
    }

    /**
     * Confirms the warning (W2-07): only now is the plain container
     * written, and only then does the share sheet open.
     */
    fun confirmFileShare() {
        if (!_ui.value.fileWarning) return
        viewModelScope.launch {
            try {
                // Plaintext exports live only until the next share:
                // purge any stale unencrypted copy before writing anew.
                service.purgePlainExports()
                val path = service.exportFile(
                    routeId,
                    selection = _ui.value.selection,
                    includePerception = _ui.value.filePerception,
                )
                val shared = sharing.shareFile(path, "ECHO route", ECHO_MIME)
                _ui.update {
                    it.copy(
                        fileWarning = false,
                        filePerception = false,
                        fileShared = shared,
                        error = if (shared) null else "Could not open the share sheet",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _ui.update { it.copy(fileWarning = false, filePerception = false, error = shareExportErrorCopy()) }
            }
        }
    }

    /** Opt in or out of perception data in the plain file (default out). */
    fun setFilePerception(include: Boolean) {
        _ui.update { it.copy(filePerception = include) }
    }

    /** Dismisses the warning without writing anything. */
    fun dismissFileShare() {
        _ui.update { it.copy(fileWarning = false, filePerception = false, error = null) }
    }

    /** Drops measured progress so a new offer starts from frozen light. */
    private fun clearProgress() {
        rate.reset()
    }

    private fun launchProducer(errorCopy: (Exception) -> String, block: suspend () -> Unit) {
        producerJob?.cancel()
        producerJob = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _ui.update { it.copy(error = errorCopy(e)) }
            }
        }
    }

    override fun onCleared() {
        close()
    }
}
