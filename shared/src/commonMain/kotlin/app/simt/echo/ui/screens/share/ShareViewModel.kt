package app.simt.echo.ui.screens.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.platform.ECHO_MIME
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.transfer.ExpiryOption
import app.simt.echo.transfer.ExportItem
import app.simt.echo.transfer.ExportSelection
import app.simt.echo.transfer.ExportSummary
import app.simt.echo.transfer.START_PHOTO_ITEM_ID
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
    /** Internet relay configured (W4-05); the relay action hides without it. */
    val relayAvailable: Boolean = false,
    val relayOffered: Boolean = false,
    /** Privacy warning up (W2-07); no plain file exists until it is confirmed. */
    val fileWarning: Boolean = false,
    val fileShared: Boolean = false,
    /** Export preview rows (W3-08); empty until the route loads. */
    val exportItems: List<ExportItem> = emptyList(),
    /** Current preview-sheet choice; the offer always encodes this. */
    val selection: ExportSelection = ExportSelection(),
    /** Honest summary of everything the bundle carries. */
    val summary: ExportSummary? = null,
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
) : ViewModel() {

    private val _ui = MutableStateFlow(ShareUiState())
    val ui: StateFlow<ShareUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val items = service.describeExport(routeId)
                val selection = service.defaultSelectionFor(routeId)
                val summary = service.summaryFor(routeId, selection)
                val offer = service.offer(routeId, selection = selection)
                _ui.update {
                    it.copy(
                        qrUri = offer.qrUri,
                        relayAvailable = service.relayAvailable,
                        exportItems = items,
                        selection = selection,
                        summary = summary,
                    )
                }
                service.served.collect {
                    _ui.update { state -> state.copy(sent = true) }
                    haptics.play(HapticPattern.CONFIRM)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Could not start sharing") }
            }
        }
    }

    /**
     * Preview-sheet toggle (W3-08): flips one asset, then re-offers so the
     * QR always encodes exactly the current selection. Fail-closed: the
     * previous QR (a superset) is replaced — [TransferService.offer] stops
     * the old offer first.
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
        val summary = runCatching { service.summaryFor(routeId, selection) }.getOrNull()
        _ui.update {
            it.copy(selection = selection, summary = summary, qrUri = null, sent = false, error = null)
        }
        viewModelScope.launch {
            try {
                val relayed = _ui.value.relayOffered
                val offer = service.offer(routeId, useRelay = relayed, selection = selection)
                _ui.update { it.copy(qrUri = offer.qrUri, error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Could not update sharing") }
            }
        }
    }

    /**
     * Re-offers through the internet relay (W4-05): the QR gains `c=` and a
     * receiver that cannot reach this phone falls back to the relay. The
     * previous QR stops working — this offer replaces it.
     */
    fun useInternetRelay() {
        if (_ui.value.relayOffered) return
        _ui.update { it.copy(qrUri = null, sent = false, error = null) }
        viewModelScope.launch {
            try {
                val offer = service.offer(routeId, useRelay = true, selection = _ui.value.selection)
                _ui.update { it.copy(qrUri = offer.qrUri, relayOffered = true, error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Could not start the relay") }
            }
        }
    }

    /** Raises the privacy warning; nothing is written until [confirmFileShare]. */
    fun requestFileShare() {
        _ui.update { it.copy(fileWarning = true, error = null) }
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
                val path = service.exportFile(routeId, selection = _ui.value.selection)
                val shared = sharing.shareFile(path, "ECHO route", ECHO_MIME)
                _ui.update {
                    it.copy(
                        fileWarning = false,
                        fileShared = shared,
                        error = if (shared) null else "Could not open the share sheet",
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(fileWarning = false, error = e.message ?: "Could not export the file") }
            }
        }
    }

    /** Dismisses the warning without writing anything. */
    fun dismissFileShare() {
        _ui.update { it.copy(fileWarning = false, error = null) }
    }

    override fun onCleared() {
        service.stopOffer()
    }
}
