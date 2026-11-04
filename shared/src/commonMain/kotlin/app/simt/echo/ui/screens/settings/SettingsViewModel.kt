package app.simt.echo.ui.screens.settings

import androidx.lifecycle.ViewModel
import app.simt.echo.capture.calibrateK
import app.simt.echo.capture.calibrationWalkError
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.platform.CSV_MIME
import app.simt.echo.platform.FileSharing
import app.simt.echo.transfer.DeviceCredentials
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.ui.copy.settingsExportErrorCopy
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
    /** Visual assist (plan W19-01), persisted. */
    val visualAssist: Boolean = false,
    /** Distance heartbeat while closing on a turn (W13-09), persisted. */
    val turnHeartbeat: Boolean = true,
    /** Opt-in earcons at turns (W13-09), persisted. */
    val earcons: Boolean = false,
    /** Walk-time upload may use mobile data, not just Wi-Fi (W11-09), persisted. */
    val readyUploadMobileData: Boolean = true,
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
    /** Result line after Delete all, or null before the first run. */
    val deleteNote: String? = null,
    /** Anchored legs folded into the learned stride (W12-03). */
    val strideLearnedLegs: Int = 0,
    /** Files Delete all would erase right now; 0 disables the row (W8-11). */
    val deletableItems: Int = 0,
) {
    /**
     * Destructive rows stay inert when there is nothing to erase: an
     * enabled "Delete all" over an empty store promises an action it
     * cannot perform.
     */
    val canDeleteAll: Boolean get() = deletableItems > 0
}

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
    /**
     * Saved routes and drafts for Delete all (W8-11). Optional so older
     * call sites keep compiling: when absent, trials and exports are
     * still cleared and the note says exactly what was removed.
     */
    private val routes: RouteRepository? = null,
    private val drafts: DraftRepository? = null,
    /**
     * Cloud seam and this install's credential (review 0012, R12-09). Both
     * optional so older call sites keep compiling: without them Delete all
     * erases only what is on the phone and says exactly that.
     */
    private val api: V11CloudApi? = null,
    private val credentials: DeviceCredentials? = null,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        configs.get().let {
            SettingsUi(
                stepK = it.stepK,
                baselineMode = it.baselineMode,
                cameraOverlayEnabled = it.cameraOverlayEnabled,
                visualAssist = it.visualAssist,
                turnHeartbeat = it.turnHeartbeat,
                earcons = it.earcons,
                readyUploadMobileData = it.readyUploadMobileData,
                localModelBaseUrl = it.localModelBaseUrl,
                localModelName = it.localModelName,
                preferLocalModel = it.preferLocalModel,
                onPhoneModel = onPhoneModelName,
                cloudAiConfigured = cloudAiConfigured,
                strideLearnedLegs = it.learnedStrideLegs,
                deletableItems = deletableCount(),
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
        configs.update { it.copy(stepK = next).withoutLearnedStride() }
        val error = calibrationWalkError(CALIBRATION_WALK_M, measured ?: 0.0)
        val pct = error?.let { " (walk read ${oneDecimalPct(it)} ${if (it >= 0) "long" else "short"})" } ?: ""
        _ui.update { it.copy(stepK = next, strideLearnedLegs = 0, measuredM = "", calibrationNote = "K = ${round(next * 1000) / 1000.0}$pct") }
    }

    fun resetK() {
        configs.update { it.copy(stepK = 0.48).withoutLearnedStride() }
        _ui.update { it.copy(stepK = 0.48, strideLearnedLegs = 0, calibrationNote = "K reset to 0.48.") }
    }

    fun setBaselineMode(enabled: Boolean) {
        configs.update { it.copy(baselineMode = enabled) }
        _ui.update { it.copy(baselineMode = enabled) }
    }

    fun setTurnHeartbeat(enabled: Boolean) {
        configs.update { it.copy(turnHeartbeat = enabled) }
        _ui.update { it.copy(turnHeartbeat = enabled) }
    }

    fun setEarcons(enabled: Boolean) {
        configs.update { it.copy(earcons = enabled) }
        _ui.update { it.copy(earcons = enabled) }
    }

    fun setReadyUploadMobileData(enabled: Boolean) {
        configs.update { it.copy(readyUploadMobileData = enabled) }
        _ui.update { it.copy(readyUploadMobileData = enabled) }
    }

    fun setCameraOverlay(enabled: Boolean) {
        configs.update { it.copy(cameraOverlayEnabled = enabled) }
        _ui.update { it.copy(cameraOverlayEnabled = enabled) }
    }

    fun setVisualAssist(enabled: Boolean) {
        configs.update { it.copy(visualAssist = enabled) }
        _ui.update { it.copy(visualAssist = enabled) }
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

    /**
     * Deletes every route, draft, trial and exported file (W8-11 Privacy &
     * data) — and, since R12-09 (review 0012, F9), the cloud copies the drafts
     * still name: a finished or running job, a resumable upload session, the
     * walk-staged sessions, and finally this install's device record and key.
     *
     * The server pass runs first and best-effort: wiping `drafts/` first would
     * destroy the jobId/uploadId handles the privacy policy promises an
     * immediate delete on, leaving indoor photos in R2 until the retention
     * window expires. The note reports what actually happened, never more.
     */
    fun deleteAll() {
        viewModelScope.launch {
            val cloud = deleteCloudCopies()
            val routesRemoved = routes?.deleteAll() ?: 0
            val draftsRemoved = drafts?.deleteAll() ?: 0
            val trialsRemoved = trials.deleteAll()
            val exportsRemoved = trials.clearExports()
            val localRemoved = routesRemoved + draftsRemoved + trialsRemoved + exportsRemoved
            val note = when {
                localRemoved == 0 && cloud == null -> "Nothing to delete."
                else -> buildString {
                    if (localRemoved > 0) {
                        append(
                            "Removed ${plural(routesRemoved, "route")}, " +
                                "${plural(draftsRemoved, "draft")}, " +
                                "${plural(trialsRemoved, "trial")} and " +
                                "${plural(exportsRemoved, "exported file")}.",
                        )
                    }
                    when (cloud) {
                        true -> append(
                            if (localRemoved > 0) " Cloud copies and this install's device record are deleted."
                            else "Deleted this install's cloud copies and device record.",
                        )
                        false -> append(
                            " Some cloud copies could not be deleted; they expire within 24 hours.",
                        )
                        null -> Unit
                    }
                }
            }
            _ui.update { it.copy(deleteNote = note, deletableItems = deletableCount()) }
        }
    }

    /**
     * Erases the server side of Delete all. Returns true when every named
     * object is gone (204 and 404 both mean gone), false when any call failed,
     * and null when this ViewModel has no cloud seam to speak of.
     */
    private suspend fun deleteCloudCopies(): Boolean? {
        val cloud = api ?: return null
        var complete = true
        for (draftId in drafts?.listDraftIds().orEmpty()) {
            drafts?.loadJobRecord(draftId)?.let { record ->
                complete = deleteQuietly { cloud.delete(record.jobId) } && complete
            }
            drafts?.loadUploadRecord(draftId)?.let { record ->
                complete = deleteQuietly { cloud.deleteUpload(record.uploadId) } && complete
            }
            drafts?.loadReadyStagedRecord(draftId)?.uploadIds?.forEach { uploadId ->
                complete = deleteQuietly { cloud.deleteUpload(uploadId) } && complete
            }
        }
        complete = deleteQuietly { cloud.deleteDevice() } && complete
        // The key goes even when the record delete failed: a retained key
        // would keep the orphan usable, while the record itself expires on its
        // own. A failure here must not abort the local erase.
        credentials?.let { creds ->
            try {
                creds.forget()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort; the next mint overwrites the stored key.
            }
        }
        return complete
    }

    /** Runs one erasure call, reporting failure instead of throwing it. */
    private suspend fun deleteQuietly(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Saved routes, drafts and trial/export files Delete all would erase. */
    private fun deletableCount(): Int =
        (routes?.list()?.size ?: 0) + (drafts?.listDraftIds()?.size ?: 0) + trials.deletableFileCount()
}

/** Settings line for the learned stride (W12-03): "1 leg", "3 legs". */
internal fun strideLearnedLabel(legs: Int): String = "Stride learned from ${plural(legs, "leg")}"

/**
 * A manual K (calibrate / reset) supersedes whatever the walks learned:
 * the learned posterior is relative to the old K, so keeping it would
 * seed the next walk from a stride the user just overrode.
 */
private fun EchoConfig.withoutLearnedStride(): EchoConfig =
    copy(learnedStepK = null, learnedStrideLegs = 0)

/** Count with a human singular/plural noun (W7-06 copy discipline). */
private fun plural(count: Int, singular: String): String =
    if (count == 1) "1 $singular" else "$count ${singular}s"

/** Signed percent with one decimal, multiplatform-safe (no String.format). */
private fun oneDecimalPct(fraction: Double): String {
    val tenths = round(fraction * 1000) / 10.0
    return "$tenths%"
}
