package app.simt.echo.model

import kotlinx.serialization.Serializable

/**
 * Tunable thresholds (plan §5: `EchoConfig` is owned by W4-02 after W2-06;
 * algorithms read config and do not hard-code past it). Defaults are the
 * verbatim plan §6.3 values; W4-02 extends this with step-length
 * calibration — the segmenter is the first reader.
 */
@Serializable
data class EchoConfig(
    /**
     * Weinberg step-length constant (§6.2 step 3, slice W4-02).
     * Calibrated by a taped 10 m walk (`calibrateK`); every reader —
     * [app.simt.echo.capture.PdrEngine] via the [app.simt.echo.capture.StepCalibratable]
     * seam, the segmenter, the navigator — scales from this one scalar.
     */
    val stepK: Double = 0.48,
    /**
     * Baseline navigation mode (§10 W4-01): landmark cards hidden,
     * `SeeLandmark` disabled — turns and distance only. Recorded per
     * trial by the trial writer; this is the global Settings default.
     */
    val baselineMode: Boolean = false,
    /**
     * Camera HUD on Navigate (W5-02): live preview behind the navigation
     * HUD with the landmark crop (and its SAM mask, when persisted) over
     * it. Opt-in, default off; never a live-frame projection — no camera
     * pose or tracking exists in this app (review 0004 R4).
     */
    val cameraOverlayEnabled: Boolean = false,

    // Local model (plan §7.5 / W5-01), Settings → Advanced. All defaults
    // are "nothing configured": with these unset the app behaves exactly
    // as before (cloud or manual landmarks only).
    /** Base URL of an OpenAI-compatible LAN vision server, or empty. */
    val localModelBaseUrl: String = "",
    /** Model id on that server (also its display name), or empty. */
    val localModelName: String = "",
    /** Prefer the local model over the cloud worker when both could run. */
    val preferLocalModel: Boolean = false,
    /** Resample distance per path point, metres (§6.3 step 1). */
    val resampleStepM: Double = 0.5,
    /** Circular yaw-smoothing window radius, metres (§6.3 step 2). */
    val yawSmoothWindowM: Double = 1.0,
    /** Window for the rolling leg heading, metres (§6.3 step 3). */
    val legHeadingWindowM: Double = 3.0,
    /** Deviation from the leg heading that opens a turn, degrees. */
    val turnOpenDeviationDeg: Double = 35.0,
    /** Sustained deviation length that confirms a turn, metres. */
    val turnOpenDistanceM: Double = 1.5,
    /** Yaw change over [turnOpenDistanceM] below which a turn closes, degrees. */
    val turnCloseChangeDeg: Double = 10.0,
    /** Minimum turn magnitude kept as a node, degrees. */
    val turnMinAngleDeg: Double = 45.0,
    /** Turn magnitude classified as a U-turn, degrees. */
    val uTurnAngleDeg: Double = 150.0,
    /** Leg |Δh| that triggers a level change, metres. */
    val levelThresholdM: Double = 2.2,
    /** Metres of climb per level in `levelDelta = round(Δh / this)`. */
    val levelPerFloorM: Double = 3.0,
    /** A user mark within this distance of a turn merges into it, metres. */
    val markMergeDistanceM: Double = 3.0,
    /** CHECKPOINT/LEVEL nodes closer than this to the previous node drop, metres. */
    val minNodeSpacingM: Double = 4.0,

    // Navigator (§6.4). W4-02 tunes these alongside step-length calibration.
    /** Fraction of leg length at which a turn enters APPROACH. */
    val navApproachFraction: Double = 0.15,
    /** Minimum remaining distance for APPROACH, metres. */
    val navApproachMinM: Double = 4.0,
    /** Default landmark-card visibility when a landmark has none, metres. */
    val navLandmarkVisibleDefaultM: Double = 8.0,
    /** Floor for landmark-card visibility, metres. */
    val navLandmarkVisibleMinM: Double = 6.0,
    /** Remaining distance that arms the "I'm here" arrival prompt, metres. */
    val navArrivePromptM: Double = 2.0,
    /** Walking this far past the destination arrives without a prompt, metres. */
    val navArriveAutoPastM: Double = 3.0,
    /** Minimum overshoot past a leg that raises UNCERTAIN, metres. */
    val navUncertainOvershootMinM: Double = 5.0,
    /** Overshoot as a fraction of leg length that raises UNCERTAIN. */
    val navUncertainOvershootFraction: Double = 0.30,
    /** Odometer uncertainty at an anchor, metres (σ model). */
    val navUncertaintyBaseM: Double = 0.5,
    /** Odometer uncertainty growth per metre since the last anchor. */
    val navUncertaintyPerMetre: Double = 0.08,
    /** Uncertainty above which navigation is UNCERTAIN, metres. */
    val navUncertaintyMaxM: Double = 6.0,
    /** Extra metres after a CONFIRM prompt at which the leg auto-advances. */
    val navConfirmAutoAdvanceM: Double = 8.0,
    /** A matching turn only advances at or past this leg fraction. */
    val navTurnMinProgressFraction: Double = 0.5,
    /** Distance on the first leg after which a wrong start can be called, metres. */
    val navWrongStartDistanceM: Double = 6.0,
    /** Heading error that marks a wrong start, degrees. */
    val navWrongStartHeadingErrorDeg: Double = 50.0,
    /** Trailing distance window for corridor snapping, metres. */
    val navCorridorWindowM: Double = 4.0,
    /** Maximum yaw spread inside the corridor window, degrees. */
    val navCorridorYawStdDeg: Double = 8.0,
    /** Maximum arrow error corridor snapping will correct, degrees. */
    val navCorridorArrowMaxDeg: Double = 25.0,
    /** Fraction of the arrow error absorbed per motion update while snapping. */
    val navCorridorCorrection: Double = 0.05,

    // Online turn detector (§6.5).
    /** Yaw history retained by the detector, milliseconds. */
    val turnHistoryMs: Long = 6_000L,
    /** Reference window start, milliseconds before now. */
    val turnRefStartMs: Long = 4_000L,
    /** Reference window end, milliseconds before now. */
    val turnRefEndMs: Long = 2_500L,
    /** Recent window judged for stability, milliseconds. */
    val turnRecentMs: Long = 1_000L,
    /** Maximum yaw spread in the recent window for a turn to fire, degrees. */
    val turnRecentMaxStdDeg: Double = 12.0,
    /** Quiet period after a detected turn, milliseconds. */
    val turnCooldownMs: Long = 2_000L,
)
