package app.simt.echo.ui.screens.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.model.BundleBody
import app.simt.echo.model.EchoSchemaVersion
import app.simt.echo.model.NodeType
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.NfcPairingController
import app.simt.echo.platform.TransferProgress
import app.simt.echo.platform.UnavailableNfcPairingController
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.transfer.NfcReadiness
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingInvitation
import app.simt.echo.transfer.PairingMethod
import app.simt.echo.transfer.PairingPhase
import app.simt.echo.transfer.PairingPolicy
import app.simt.echo.transfer.PairingRole
import app.simt.echo.transfer.PairingSource
import app.simt.echo.transfer.ProgressRateTracker
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.copy.receiveFileErrorCopy
import app.simt.echo.ui.copy.receiveSaveErrorCopy
import app.simt.echo.ui.sigil.SigilGeometry
import app.simt.echo.ui.sigil.routeSigil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Import-sheet summary for a fetched-but-unsaved bundle (plan W2-04). */
data class ImportPreview(
    val name: String,
    val lengthM: Double,
    val turns: Int,
    val landmarkCount: Int,
    val creatorLabel: String,
    val expiresAtEpochMs: Long?,
    /** The route's sigil, unfolded on the import sheet (W13-07); presentation only. */
    val sigil: SigilGeometry? = null,
)

/** Receive screen state (plan §8.3): scan → fetch → sheet → Save. */
data class ReceiveUiState(
    /**
     * Acquisition generation. Scanners key off it and every callback carries
     * it, so a late QR frame or NFC read from a cancelled attempt is
     * discarded instead of fed into the current one.
     */
    val generation: Int = 0,
    val fetching: Boolean = false,
    val preview: ImportPreview? = null,
    val savedRouteId: String? = null,
    val error: String? = null,
    /**
     * Live byte progress of the fetch in flight (W13-14); null before any
     * byte moves. Drives the particle flow — never synthesised.
     */
    val progress: TransferProgress? = null,
    /** Measured bytes/s behind [progress]; 0 means frozen light. */
    val bytesPerSecond: Double = 0.0,
    /** Method for THIS visit. QR on every new Receive; never remembered. */
    val method: PairingMethod = PairingMethod.QR,
    /** What the selector may honestly say about NFC right now. */
    val nfcReadiness: NfcReadiness = NfcReadiness.PREPARING,
    /** The reader is armed and waiting for a tap. */
    val nfcListening: Boolean = false,
    /**
     * Method switching is locked while a fetch or preview owns the screen:
     * abandoning it silently would strand a transfer.
     */
    val methodLocked: Boolean = false,
    /** Typed NFC failure for the screen to explain; never exception text. */
    val pairingFailure: PairingFailure? = null,
)

/** Plan copy for an unreachable peer (venue Wi-Fi often isolates clients). */
const val UNREACHABLE_COPY = "Can't reach the other phone. Join its hotspot and try again."

/** Honest notice for a route written by a newer ECHO than this app understands (W10-05). */
const val UPDATE_ECHO_COPY = "Update ECHO to open this route."

/**
 * Receive state machine (plan 0005 §4.5).
 *
 * One attempt spans acquisition → parse → fetch → preview, whichever source
 * produced the invitation: QR and NFC reach exactly the same bounded parser
 * and the same single-flight fetch. Save remains the only persistence
 * point, and a cancelled attempt can never resurrect a preview.
 */
class ReceiveViewModel(
    private val service: TransferService,
    private val sharing: FileSharing,
    private val nowMs: () -> Long = { currentEpochMs() },
    /**
     * NFC acquisition for this visit (plan 0005 §4.1). The default reports
     * no capabilities, so previews and tests stay honest.
     */
    private val pairing: NfcPairingController = UnavailableNfcPairingController(),
) : ViewModel() {

    private val _ui = MutableStateFlow(
        ReceiveUiState(method = PairingPolicy.defaultMethod(PairingRole.RECEIVE)),
    )
    val ui: StateFlow<ReceiveUiState> = _ui.asStateFlow()

    private var handledUri: String? = null
    private var previewBody: BundleBody? = null
    private var attemptJob: Job? = null
    private var saveJob: Job? = null
    private var closed = false

    private val rate = ProgressRateTracker(viewModelScope, nowMs)

    init {
        // Subscribed from construction — long before any scan can move
        // bytes — so no fetch emission is missed, however fast. Undispatched
        // so even a transport that emits without suspending loses nothing.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.progress.collect { emitted ->
                val measured = rate.record(emitted) { _ui.update { it.copy(bytesPerSecond = 0.0) } }
                _ui.update { it.copy(progress = emitted, bytesPerSecond = measured) }
            }
        }
        viewModelScope.launch {
            pairing.capabilities.collect { refreshReadiness() }
        }
        viewModelScope.launch {
            pairing.events.collect(::onPairingEvent)
        }
        // A new visit starts in QR: arm the camera path, not the reader.
        arm()
    }

    // ── acquisition ─────────────────────────────────────────────

    /**
     * Compatibility adapter (W2-04 callers): a QR decode belongs to the
     * attempt currently being displayed.
     */
    fun onScanned(uri: String) {
        onInvitation(uri, PairingSource.QR, _ui.value.generation.toLong())
    }

    /**
     * Accepts an invitation from either source. Both feed the same bounded
     * parser and the same single-flight attempt, so neither can bypass
     * validation or open a second fetch.
     */
    fun onInvitation(uri: String, source: PairingSource, attemptId: Long) {
        val state = _ui.value
        if (closed) return
        if (attemptId != state.generation.toLong()) return
        if (state.fetching || state.preview != null) return
        if (source == PairingSource.QR && !uri.startsWith(ECHO_QR_SCHEME)) return
        if (handledUri == uri) return
        handledUri = uri

        // An accepted invitation stops both scanners: the route moves over
        // the LAN, and a second tap or scan would only confuse the user.
        disarm()
        clearProgress()
        _ui.update {
            it.copy(
                fetching = true,
                methodLocked = true,
                error = null,
                pairingFailure = null,
                progress = null,
                bytesPerSecond = 0.0,
            )
        }
        attemptJob = viewModelScope.launch { fetch(uri, attemptId) }
    }

    private suspend fun fetch(uri: String, attemptId: Long) {
        // Bounded validation before any networking, for both sources.
        val invitation = PairingInvitation.parse(uri)
        if (invitation.isFailure) {
            if (isAttempt(attemptId)) {
                _ui.update {
                    it.copy(
                        fetching = false,
                        methodLocked = false,
                        pairingFailure = PairingFailure.InvalidInvitation,
                    )
                }
            }
            return
        }
        try {
            val body = service.fetchPreview(invitation.getOrThrow().uri)
            if (!isAttempt(attemptId)) return
            present(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!isAttempt(attemptId)) return
            _ui.update {
                it.copy(
                    fetching = false,
                    methodLocked = false,
                    error = UNREACHABLE_COPY,
                    pairingFailure = PairingFailure.Network,
                )
            }
        }
    }

    /**
     * Chooses the pairing method for this visit. The old acquisition is
     * stopped before the next starts — reader mode and the camera never run
     * together — and the camera permission is irrelevant to NFC.
     */
    fun selectMethod(method: PairingMethod) {
        if (closed) return
        val state = _ui.value
        if (state.method == method) return
        if (state.methodLocked) return
        disarm()
        _ui.update {
            it.copy(method = method, error = null, pairingFailure = null, nfcListening = false)
        }
        refreshReadiness()
        armIfIdle()
    }

    /** The destination left the foreground: stop listening, keep the visit. */
    fun onPaused() {
        disarm()
    }

    /** The destination came back: re-arm the selected method once. */
    fun onResumed() {
        armIfIdle()
    }

    private fun armIfIdle() {
        if (closed) return
        val state = _ui.value
        if (state.fetching || state.preview != null) return
        arm()
    }

    /** Starts a fresh acquisition generation for the selected method. */
    private fun arm() {
        val next = _ui.value.generation + 1
        _ui.update { it.copy(generation = next) }
        if (_ui.value.method == PairingMethod.NFC) {
            pairing.startReading(next.toLong())
            _ui.update { it.copy(nfcListening = true) }
        }
        refreshReadiness()
    }

    private fun disarm() {
        val state = _ui.value
        if (state.method == PairingMethod.NFC) {
            pairing.stopReading(state.generation.toLong())
        }
        _ui.update { it.copy(nfcListening = false) }
    }

    private fun refreshReadiness() {
        val phase = if (_ui.value.nfcListening) PairingPhase.ARMED else PairingPhase.IDLE
        _ui.update {
            it.copy(
                nfcReadiness = PairingPolicy.readiness(
                    PairingRole.RECEIVE,
                    pairing.capabilities.value,
                    phase,
                ),
            )
        }
    }

    private fun onPairingEvent(event: NfcEvent) {
        if (closed) return
        val attempt = _ui.value.generation.toLong()
        when (event) {
            is NfcEvent.Reading -> if (event.attemptId == attempt) {
                _ui.update { it.copy(nfcListening = true) }
            }

            is NfcEvent.Invitation -> onInvitation(event.uri, PairingSource.NFC, event.attemptId)

            is NfcEvent.ReadFailed -> if (event.attemptId == attempt) {
                _ui.update {
                    it.copy(nfcListening = false, pairingFailure = event.failure)
                }
            }

            else -> Unit
        }
    }

    // ── preview and save ────────────────────────────────────────

    fun save(onSaved: (String) -> Unit) {
        val body = previewBody ?: return
        // Single-flight: repeated taps cannot persist twice.
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            try {
                service.saveImported(body)
                _ui.update { it.copy(savedRouteId = body.route.routeId) }
                onSaved(body.route.routeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _ui.update { it.copy(error = receiveSaveErrorCopy()) }
            }
        }
    }

    /**
     * File fallback (W2-07): opens the document picker with acquisition
     * suspended, decodes the plain container and reuses the import sheet.
     * Nothing leaves the device, so no privacy warning is needed on import.
     */
    fun importFile() {
        val state = _ui.value
        if (closed || state.fetching || state.preview != null) return
        // Suspended while the picker is open: a tap underneath it would
        // start a transfer the user cannot see.
        disarm()
        clearProgress()
        _ui.update { it.copy(fetching = true, methodLocked = true, error = null, pairingFailure = null) }
        attemptJob = viewModelScope.launch {
            try {
                val bytes = sharing.pickDocument()
                if (bytes == null) {
                    // Cancelled picker: return to the selected method quietly.
                    _ui.update { it.copy(fetching = false, methodLocked = false) }
                    armIfIdle()
                    return@launch
                }
                present(service.importFile(bytes))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _ui.update {
                    it.copy(
                        fetching = false,
                        methodLocked = false,
                        error = receiveFileErrorCopy(),
                        pairingFailure = PairingFailure.Internal,
                    )
                }
            }
        }
    }

    /**
     * Discards the current attempt (failure, preview or in-flight fetch) and
     * re-arms the selected method. `rescan` is the method-aware retry the
     * screen offers after a failure.
     */
    fun rescan() {
        if (closed) return
        discardAttempt()
        // discardAttempt leaves the screen idle; arm() is what advances the
        // generation, so one retry advances it exactly once.
        armIfIdle()
    }

    /** Cancel/Discard: returns to the selected mode with nothing carried over. */
    fun cancel() {
        rescan()
    }

    private fun discardAttempt() {
        attemptJob?.cancel()
        attemptJob = null
        disarm()
        handledUri = null
        previewBody = null
        clearProgress()
        _ui.update {
            it.copy(
                fetching = false,
                preview = null,
                savedRouteId = null,
                error = null,
                pairingFailure = null,
                methodLocked = false,
                progress = null,
                bytesPerSecond = 0.0,
            )
        }
    }

    private fun isAttempt(attemptId: Long): Boolean = attemptId == _ui.value.generation.toLong()

    /** Drops measured progress so a new scan starts from frozen light. */
    private fun clearProgress() {
        rate.reset()
    }

    /**
     * Opens [body] on the import sheet, or says so when a newer app wrote
     * it — never a half-understood route, never a crash (W10-05).
     */
    private fun present(body: BundleBody) {
        if (EchoSchemaVersion.needsUpdate(body.route)) {
            _ui.update {
                it.copy(
                    fetching = false,
                    methodLocked = false,
                    error = UPDATE_ECHO_COPY,
                )
            }
            return
        }
        previewBody = body
        _ui.update {
            it.copy(
                fetching = false,
                // The sheet owns the screen until Save or Cancel: a silent
                // method switch would strand the fetched body.
                methodLocked = true,
                preview = summarize(body),
            )
        }
    }

    private fun summarize(body: BundleBody): ImportPreview {
        val route = body.route
        return ImportPreview(
            name = route.name,
            lengthM = route.totalLengthM,
            turns = route.nodes.count { it.type == NodeType.TURN },
            landmarkCount = route.landmarks.size,
            creatorLabel = route.creatorLabel,
            expiresAtEpochMs = route.privacy.expiresAtEpochMs,
            sigil = routeSigil(route),
        )
    }

    /** Ends this visit for good: the screen is gone and will be rebuilt. */
    fun close() {
        if (closed) return
        closed = true
        disarm()
        attemptJob?.cancel()
        saveJob?.cancel()
        pairing.close()
        viewModelScope.cancel()
    }

    override fun onCleared() {
        close()
    }

    private companion object {
        const val ECHO_QR_SCHEME = "simt-echo://"
    }
}
