package app.simt.echo.ui.screens.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.capture.CaptureHud
import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
import app.simt.echo.ui.copy.recordCameraErrorCopy
import app.simt.echo.ui.copy.recordResumeErrorCopy
import app.simt.echo.ui.copy.recordSaveErrorCopy
import app.simt.echo.ui.copy.recordStartErrorCopy
import app.simt.echo.ui.copy.recordStopErrorCopy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Record screen (plan §8.3). */
data class RecordUiState(
    val state: CaptureState = CaptureState.IDLE,
    val hud: CaptureHud = CaptureHud(),
    val error: String? = null,
)

class RecordViewModel(private val session: CaptureSession) : ViewModel() {

    private val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val uiState: StateFlow<RecordUiState> =
        combine(session.state, session.hud, error) { state, hud, err ->
            RecordUiState(state = state, hud = hud, error = err)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordUiState())

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
        }
    }

    fun mark() = session.mark()

    /** Long-press Finish: stop recording, show the finish form. */
    fun beginFinish() {
        viewModelScope.launch {
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
