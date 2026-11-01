package app.simt.echo.ui.screens.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.transfer.TransferService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Share screen state (plan §8.3): QR offer, then "Sent ✓" on download. */
data class ShareUiState(
    val qrUri: String? = null,
    val sent: Boolean = false,
    val error: String? = null,
)

class ShareViewModel(
    private val routeId: String,
    private val service: TransferService,
    private val haptics: Haptics,
) : ViewModel() {

    private val _ui = MutableStateFlow(ShareUiState())
    val ui: StateFlow<ShareUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val offer = service.offer(routeId)
                _ui.update { it.copy(qrUri = offer.qrUri) }
                service.served.collect {
                    _ui.update { state -> state.copy(sent = true) }
                    haptics.play(HapticPattern.CONFIRM)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Could not start sharing") }
            }
        }
    }

    override fun onCleared() {
        service.stopOffer()
    }
}
