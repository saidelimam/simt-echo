package app.simt.echo.ui.screens.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.capture.CaptureHud
import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
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

    val uiState: StateFlow<RecordUiState> =
        combine(session.state, session.hud) { state, hud ->
            RecordUiState(state = state, hud = hud)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordUiState())

    fun onPreviewReady() = session.ready()

    fun start() {
        viewModelScope.launch { runCatching { session.start() } }
    }

    fun mark() = session.mark()

    /** Long-press Finish: stop recording, show the finish form. */
    fun beginFinish() {
        viewModelScope.launch { runCatching { session.beginFinish() } }
    }

    fun finish(routeName: String, destinationLabel: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            runCatching { session.save(routeName, destinationLabel) }
                .onSuccess { onSaved() }
        }
    }
}
