package app.simt.echo.ui.screens.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.capture.CaptureHud
import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
import app.simt.echo.transfer.WalkStagerFactory
import app.simt.echo.ui.copy.recordCameraErrorCopy
import app.simt.echo.ui.copy.recordResumeErrorCopy
import app.simt.echo.ui.copy.recordSaveErrorCopy
import app.simt.echo.ui.copy.recordStartErrorCopy
import app.simt.echo.ui.copy.recordStopErrorCopy
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Record screen (plan §8.3). */
data class RecordUiState(
    val state: CaptureState = CaptureState.IDLE,
    val hud: CaptureHud = CaptureHud(),
    val error: String? = null,
)

/**
 * Ready-on-arrival opt-in state (plan W11-09). Default off: [optIn] is
 * false until the pre-start overlay toggle, and [consented] is false
 * until the consent sheet is accepted. Start is blocked while
 * [consentPending] — the sheet appears *before* Start, never after.
 */
data class ReadyOptInUiState(
    val optIn: Boolean = false,
    val consentPending: Boolean = false,
    val consented: Boolean = false,
    /** The Settings "mobile data" switch, read when the screen opens. */
    val mobileData: Boolean = false,
) {
    /** Pre-start checkbox label; network details live in the consent sheet. */
    val optInLabel: String
        get() = "Find landmarks while I walk"

    /** Consent sheet body; the network sentence follows the Settings switch. */
    val consentBody: String
        get() = "As each checkpoint window closes, face-filtered strips upload " +
            (if (mobileData) "over Wi-Fi, or mobile data when there is no Wi-Fi (never while roaming) " else "over Wi-Fi only ") +
            "to Cloudflare R2 so landmarks are ready when you finish. " +
            "Staged strips are deleted within 1 hour; the processing job's media within 24 hours. " +
            "Landmarks are proposed by Meta Llama 4 Scout (Google Gemma 4 fallback) on Workers AI. " +
            "Nothing sends before you allow it. " +
            "Cancel or discard deletes staged uploads."
}

class RecordViewModel(
    private val session: CaptureSession,
    /** Settings "mobile data" switch (W11-09): shapes the opt-in copy only; the uploader's policy comes from [app.simt.echo.AppGraph.readyUploadConsent]. */
    mobileDataAllowed: Boolean = false,
    /**
     * Builds the walk-time stager (W11-09) once recording has started with the
     * user's consent; null from it, or this default, means no upload while walking.
     */
    private val walkStagers: WalkStagerFactory = WalkStagerFactory { _, _, _ -> null },
) : ViewModel() {

    private val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private var walkJob: Job? = null

    val uiState: StateFlow<RecordUiState> =
        combine(session.state, session.hud, error) { state, hud, err ->
            RecordUiState(state = state, hud = hud, error = err)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordUiState())

    private val _readyOptIn = kotlinx.coroutines.flow.MutableStateFlow(ReadyOptInUiState(mobileData = mobileDataAllowed))
    val readyOptIn: StateFlow<ReadyOptInUiState> = _readyOptIn

    fun onPreviewReady() = session.ready()

    /**
     * Enters the screen: reopens [resumeDraftId]'s autosaved log when
     * resuming an interrupted recording (W4-03), then arms the preview.
     * A draft that cannot be reopened surfaces an error, never a crash.
     */
    fun prepare(resumeDraftId: String?) {
        if (resumeDraftId != null && !session.resume(resumeDraftId)) {
            error.value = recordResumeErrorCopy()
        }
        session.ready()
    }

    /** Camera bind/preview failure, surfaced instead of crashing (W4-03). */
    fun onCameraError(message: String) {
        error.value = recordCameraErrorCopy()
    }

    fun start() {
        viewModelScope.launch {
            runCatching { session.start() }
                .onFailure { error.value = recordStartErrorCopy() }
                .onSuccess { startWalkStaging() }
        }
    }

    /**
     * Upload while walking (W11-09): only when the user opted in and accepted
     * the consent sheet, and only once the draft exists. The stager is best
     * effort and swallows its own failures, so it never reaches [error].
     */
    private fun startWalkStaging() {
        val state = _readyOptIn.value
        if (!state.optIn || !state.consented) return
        val draftId = session.activeDraftId ?: return
        val stager = walkStagers.create(draftId, session::liveLog, session::liveLedger) ?: return
        walkJob = viewModelScope.launch {
            stager.run(session.hud.map { it.distanceM }.distinctUntilChanged())
        }
    }

    /**
     * Pre-start Start (plan W11-09): with the opt-in toggle on and no
     * consent recorded, the consent sheet claims the phase instead —
     * recording starts only after [grantReadyConsent]. Anything else
     * starts immediately; consent can never trail Start.
     */
    fun requestStart() {
        if (_readyOptIn.value.optIn && !_readyOptIn.value.consented) {
            _readyOptIn.value = _readyOptIn.value.copy(consentPending = true)
            return
        }
        start()
    }

    /** Pre-start overlay toggle (plan W11-09); default off. */
    fun setReadyOptIn(optIn: Boolean) {
        _readyOptIn.value = _readyOptIn.value.copy(optIn = optIn)
    }

    /** Consent sheet Allow: records consent, then starts recording. */
    fun grantReadyConsent() {
        _readyOptIn.value = _readyOptIn.value.copy(consentPending = false, consented = true)
        start()
    }

    /** Consent sheet dismiss: back to the overlay, recording never starts. */
    fun dismissReadyConsent() {
        _readyOptIn.value = _readyOptIn.value.copy(consentPending = false)
    }

    /** Consent sheet Not now: opts out and starts recording without upload. */
    fun declineReadyConsent() {
        _readyOptIn.value = _readyOptIn.value.copy(optIn = false, consentPending = false)
        start()
    }

    fun mark() = session.mark()

    /** Long-press Finish: stop recording, show the finish form. */
    fun beginFinish() {
        viewModelScope.launch {
            // An in-flight batch is abandoned, not awaited: its session is
            // recorded, and Finish sends whatever it had not claimed.
            walkJob?.cancelAndJoin()
            runCatching { session.beginFinish() }
                .onFailure { error.value = recordStopErrorCopy() }
        }
    }

    fun finish(routeName: String, destinationLabel: String, onSaved: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { session.save(routeName, destinationLabel) }
                .onSuccess { onSaved(it.draftId) }
                .onFailure { error.value = recordSaveErrorCopy() }
        }
    }
}
