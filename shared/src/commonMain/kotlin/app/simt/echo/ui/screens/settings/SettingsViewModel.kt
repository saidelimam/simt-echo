package app.simt.echo.ui.screens.settings

import androidx.lifecycle.ViewModel
import app.simt.echo.capture.calibrateK
import app.simt.echo.capture.calibrationWalkError
import app.simt.echo.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.round

/** Calibrated step-length state (plan §10 W4-02). */
data class SettingsUi(
    /** Current Weinberg K (tunable, persisted). */
    val stepK: Double = 0.48,
    /** Global baseline default for new trials (cards hidden). */
    val baselineMode: Boolean = false,
    /** Camera HUD on Navigate (W5-02), persisted. */
    val cameraOverlayEnabled: Boolean = false,
    /** Last calibration walk's measured distance, metres (input). */
    val measuredM: String = "",
    /** Result line after calibrating, or null before the first run. */
    val calibrationNote: String? = null,
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
class SettingsViewModel(private val configs: ConfigRepository) : ViewModel() {

    private val _ui = MutableStateFlow(
        configs.get().let {
            SettingsUi(
                stepK = it.stepK,
                baselineMode = it.baselineMode,
                cameraOverlayEnabled = it.cameraOverlayEnabled,
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
}

/** Signed percent with one decimal, multiplatform-safe (no String.format). */
private fun oneDecimalPct(fraction: Double): String {
    val tenths = round(fraction * 1000) / 10.0
    return "$tenths%"
}
