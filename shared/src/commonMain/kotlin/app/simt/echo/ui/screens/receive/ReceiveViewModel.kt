package app.simt.echo.ui.screens.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.model.BundleBody
import app.simt.echo.model.NodeType
import app.simt.echo.platform.FileSharing
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.copy.receiveFileErrorCopy
import app.simt.echo.ui.copy.receiveSaveErrorCopy
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
)

/** Receive screen state (plan §8.3): scan → fetch → sheet → Save. */
data class ReceiveUiState(
    val generation: Int = 0,
    val fetching: Boolean = false,
    val preview: ImportPreview? = null,
    val savedRouteId: String? = null,
    val error: String? = null,
)

/** Plan copy for an unreachable peer (venue Wi-Fi often isolates clients). */
const val UNREACHABLE_COPY = "Can't reach the other phone. Join its hotspot and try again."

class ReceiveViewModel(
    private val service: TransferService,
    private val sharing: FileSharing,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReceiveUiState())
    val ui: StateFlow<ReceiveUiState> = _ui.asStateFlow()

    private var handledUri: String? = null
    private var previewBody: BundleBody? = null

    fun onScanned(uri: String) {
        if (handledUri != null || _ui.value.fetching || _ui.value.preview != null) return
        if (!uri.startsWith(ECHO_QR_SCHEME)) return
        handledUri = uri
        _ui.update { it.copy(fetching = true, error = null) }
        viewModelScope.launch {
            try {
                val body = service.fetchPreview(uri)
                previewBody = body
                _ui.update { it.copy(fetching = false, preview = summarize(body)) }
            } catch (e: Exception) {
                _ui.update { it.copy(fetching = false, error = UNREACHABLE_COPY) }
            }
        }
    }

    fun save(onSaved: (String) -> Unit) {
        val body = previewBody ?: return
        viewModelScope.launch {
            try {
                service.saveImported(body)
                _ui.update { it.copy(savedRouteId = body.route.routeId) }
                onSaved(body.route.routeId)
            } catch (e: Exception) {
                _ui.update { it.copy(error = receiveSaveErrorCopy()) }
            }
        }
    }

    /**
     * File fallback (W2-07): opens the document picker, decodes the plain
     * container and reuses the import sheet. Nothing leaves the device, so
     * no privacy warning is needed on import.
     */
    fun importFile() {
        if (handledUri != null || _ui.value.fetching || _ui.value.preview != null) return
        _ui.update { it.copy(fetching = true, error = null) }
        viewModelScope.launch {
            try {
                val bytes = sharing.pickDocument()
                if (bytes == null) {
                    _ui.update { it.copy(fetching = false) }
                    return@launch
                }
                val body = service.importFile(bytes)
                previewBody = body
                _ui.update { it.copy(fetching = false, preview = summarize(body)) }
            } catch (e: Exception) {
                _ui.update { it.copy(fetching = false, error = receiveFileErrorCopy()) }
            }
        }
    }

    /**
     * Clears a failure — or a finished sheet — and re-arms the scanner
     * for another attempt (W7-08): preview, save state and error are all
     * dropped, the scanner generation bumps so the view restarts cleanly.
     */
    fun rescan() {
        handledUri = null
        previewBody = null
        _ui.update {
            it.copy(
                generation = it.generation + 1,
                fetching = false,
                preview = null,
                savedRouteId = null,
                error = null,
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
        )
    }

    private companion object {
        const val ECHO_QR_SCHEME = "simt-echo://"
    }
}
