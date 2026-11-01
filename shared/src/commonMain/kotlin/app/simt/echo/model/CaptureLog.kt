package app.simt.echo.model

import kotlinx.serialization.Serializable

/** Raw creator capture before segmentation (plan §5.2). */
@Serializable
data class CaptureLog(
    val draftId: String,
    val startedAtEpochMs: Long,
    val motionSource: String,
    val videoStartNs: Long,
    val samples: List<CaptureSample>,
    val marks: List<Mark>,
    val startPhoto: String,
    val routeName: String = "",
    val destinationLabel: String = "",
)

/** One motion sample; every step plus a 2 Hz heartbeat. */
@Serializable
data class CaptureSample(
    val tNs: Long,
    val d: Double,
    val yaw: Double,
    val p: Double? = null,
)

@Serializable
data class Mark(
    val tNs: Long,
    val d: Double,
    val label: String,
)
