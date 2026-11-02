package app.simt.echo.ui.screens.settings

import androidx.lifecycle.ViewModel
import app.simt.echo.capture.calibrateK
import app.simt.echo.capture.calibrationWalkError
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.platform.CSV_MIME
import app.simt.echo.platform.FileSharing
import app.simt.echo.ui.copy.settingsExportErrorCopy
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round

/** Calibrated step-length state (plan §10 W4-02). */
data class SettingsUi(
    /** Current Weinberg K (tunable, persisted). */
    val stepK: Double = 0.48,
    /** Global baseline default for new trials (cards hidden). */
    val baselineMode: Boolean = false,
    /** Camera HUD on Navigate (W5-02), persisted. */
    val cameraOverlayEnabled: Boolean = false,
    /** LAN local-model base URL (W5-01), persisted. Empty = none. */
    val localModelBaseUrl: String = "",
    /** LAN local-model id (W5-01), persisted. Empty = none. */
    val localModelName: String = "",
    /** Prefer the local model over the cloud worker (W5-01). */
    val preferLocalModel: Boolean = false,
    /** On-phone model file name when one was pushed, else null (W5-01). */
    val onPhoneModel: String? = null,
    /** Cloud AI configured on this build (plan W7-07: AppGraph status). */
    val cloudAiConfigured: Boolean = true,
    /** Last calibration walk's measured distance, metres (input). */
    val measuredM: String = "",
    /** Result line after calibrating, or null before the first run. */
    val calibrationNote: String? = null,
    /** Result line after exporting the trials CSV, or null before any. */
    val exportNote: String? = null,
)

private const val CALIBRATION_WALK_M = 10.0

/**
 * Settings state over [ConfigRepository] (plan §10 W4-02).
 *
 * One seam for field tuning: the step-K calibration walk plus the
 * exposed thresholds' persistence. Pure calibration math lives in
 * `capture/Calibration.kt`; this ViewModel only transcribes input and
 * stores the result.
 */
class SettingsViewModel(
    private val configs: ConfigRepository,
    private val trials: TrialRepository,
    private val sharing: FileSharing,
    /** On-phone model file name when the platform found one (W5-01). */
    private val onPhoneModelName: String? = null,
    /** Cloud AI configured on this build (plan W7-07: AppGraph status). */
    private val cloudAiConfigured: Boolean = true,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        configs.get().let {
            SettingsUi(
                stepK = it.stepK,
                baselineMode = it.baselineMode,
                cameraOverlayEnabled = it.cameraOverlayEnabled,
                localModelBaseUrl = it.localModelBaseUrl,
                localModelName = it.localModelName,
                preferLocalModel = it.preferLocalModel,
                onPhoneModel = onPhoneModelName,
                cloudAiConfigured = cloudAiConfigured,
            )
        },
    )
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    fun onMeasuredChange(value: String) {
        _ui.update { it.copy(measuredM = value, calibrationNote = null) }
    }

    /**
     * Walk exactly [CALIBRATION_WALK_M] m (tape), enter what ECHO measured:
     * `K_new = K · 10 / measured`, stored in `EchoConfig`.
     */
    fun calibrate() {
        val measured = _ui.value.measuredM.toDoubleOrNull()
        val current = configs.get()
        val next = measured?.let { calibrateK(current.stepK, CALIBRATION_WALK_M, it) }
        if (next == null) {
            val note = if (measured == null) {
                "Enter the measured metres first."
            } else {
                "That walk would put K outside the plausible range — check the measured metres."
            }
            _ui.update { it.copy(calibrationNote = note) }
            return
        }
        configs.update { it.copy(stepK = next) }
        val error = calibrationWalkError(CALIBRATION_WALK_M, measured ?: 0.0)
        val pct = error?.let { " (walk read ${oneDecimalPct(it)} ${if (it >= 0) "long" else "short"})" } ?: ""
        _ui.update { it.copy(stepK = next, measuredM = "", calibrationNote = "K = ${round(next * 1000) / 1000.0}$pct") }
    }

    fun resetK() {
        configs.update { it.copy(stepK = 0.48) }
        _ui.update { it.copy(stepK = 0.48, calibrationNote = "K reset to 0.48.") }
    }

    fun setBaselineMode(enabled: Boolean) {
        configs.update { it.copy(baselineMode = enabled) }
        _ui.update { it.copy(baselineMode = enabled) }
    }

    fun setCameraOverlay(enabled: Boolean) {
        configs.update { it.copy(cameraOverlayEnabled = enabled) }
        _ui.update { it.copy(cameraOverlayEnabled = enabled) }
    }

    /**
     * Exports the whole trial protocol as one CSV through the system
     * share sheet (W6-01, closing W4-01's residual). The file is
     * plaintext like the W2-07 `.echo` fallback: it lives in
     * `exports/` until the next export replaces it. An empty trial set
     * is an honest no-op — no share sheet with nothing in it.
     */
    fun exportTrials() {
        viewModelScope.launch {
            try {
                val path = trials.exportCsvFile()
                if (path == null) {
                    _ui.update { it.copy(exportNote = "No trials to export yet.") }
                    return@launch
                }
                val shared = sharing.shareFile(path, "ECHO trials", CSV_MIME)
                _ui.update {
                    it.copy(
                        exportNote = if (shared) "Trials exported ✓"
                        else "Could not open the share sheet",
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(exportNote = settingsExportErrorCopy()) }
            }
        }
    }

    fun onLocalModelBaseUrlChange(value: String) {
        _ui.update { it.copy(localModelBaseUrl = value) }
        configs.update { it.copy(localModelBaseUrl = value.trim()) }
    }

    fun onLocalModelNameChange(value: String) {
        _ui.update { it.copy(localModelName = value) }
        configs.update { it.copy(localModelName = value.trim()) }
    }

    fun setPreferLocalModel(enabled: Boolean) {
        configs.update { it.copy(preferLocalModel = enabled) }
        _ui.update { it.copy(preferLocalModel = enabled) }
    }
}

/** Signed percent with one decimal, multiplatform-safe (no String.format). */
private fun oneDecimalPct(fraction: Double): String {
    val tenths = round(fraction * 1000) / 10.0
    return "$tenths%"
}
