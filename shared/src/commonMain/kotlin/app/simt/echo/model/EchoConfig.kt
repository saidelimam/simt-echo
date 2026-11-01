package app.simt.echo.model

/**
 * Tunable thresholds (plan §5: `EchoConfig` is owned by W4-02 after W2-06;
 * algorithms read config and do not hard-code past it). Defaults are the
 * verbatim plan §6.3 values; W4-02 extends this with step-length
 * calibration — the segmenter is the first reader.
 */
data class EchoConfig(
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
)
