package app.simt.echo.model

import kotlinx.serialization.Serializable

/**
 * Navigation mode a trial ran under (plan §10 W4-01).
 *
 * `ECHO` shows landmark cards and allows `SeeLandmark` snaps; `SENSOR_ONLY`
 * is the honest baseline — turns and distance only. The mode is recorded
 * per trial so the §11 field protocol (5 + 5 runs, alternating) exports to
 * one CSV with no hand-editing.
 */
enum class TrialMode { ECHO, SENSOR_ONLY }

/**
 * One drift observation at a taped marker (plan §10 W4-01 debug button
 * **At marker**): the sensor estimate when the walker stood on a marker
 * whose route distance is known.
 */
@Serializable
data class DriftMarker(
    /** Known route distance of the taped marker, metres. */
    val expectedNodeDistanceM: Double,
    /** Navigator's estimated distance at the tap, metres. */
    val estimatedDistanceM: Double,
    /** Wall time of the tap, epoch millis. */
    val atEpochMs: Long,
) {
    /** Signed error: positive means the estimate ran ahead of the tape. */
    val errorM: Double get() = estimatedDistanceM - expectedNodeDistanceM
}

/**
 * One field-protocol run (plan §11), written to `trials/<id>.json`.
 *
 * Counts mirror the Navigator's own vocabulary (anchors, confirmations,
 * uncertain/off-route episodes) so the CSV is a direct transcription of
 * what the state machine reported — never a retelling.
 */
@Serializable
data class Trial(
    val trialId: String,
    val routeId: String,
    val mode: TrialMode,
    val device: String,
    val startedAtEpochMs: Long,
    val durationS: Double,
    val distanceM: Double,
    val arrived: Boolean,
    /** Turn + landmark anchors the run passed. */
    val anchors: Int = 0,
    /** Explicit landmark confirmations (`SeeLandmark` / "I see it"). */
    val confirmations: Int = 0,
    /** Confirmations the field partner judged mistaken (§11 report field). */
    val mistakenConfirmations: Int = 0,
    val uncertainEpisodes: Int = 0,
    val offRouteEpisodes: Int = 0,
    /** Visual step-gate outcomes, additive and zero when assist was off. */
    val visualGateAccepted: Int = 0,
    val visualGateRejected: Int = 0,
    val visualGateUnwitnessed: Int = 0,
    val visualGateSuspects: Int = 0,
    /** Lighting noted for the run (§11: note lighting and holding style). */
    val lighting: String = "",
    /** How the phone was held (§11). */
    val holdingStyle: String = "",
    /** Free-form field notes for the run (§11). */
    val notes: String = "",
    val markers: List<DriftMarker> = emptyList(),
)

/**
 * Baseline mode hides landmark cards and disables `SeeLandmark` (plan §10
 * W4-01): turns and distance only. One pure predicate, shared by the
 * Navigate screen and the trial writer, so the two can never disagree.
 */
fun shouldShowLandmarkCards(baselineMode: Boolean): Boolean = !baselineMode
