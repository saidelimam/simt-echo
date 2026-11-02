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
    /**
     * Frame-ledger mode (plan W10-03): [PerceptionMode.LEDGER_ON] when the
     * Record graph bound Preview+Video+Analysis, [PerceptionMode.LEDGER_OFF]
     * on the `[P,V]` fallback. Defaults off so pre-W10-03 logs — recorded
     * with no ledger — decode honestly.
     */
    val perceptionMode: String = PerceptionMode.LEDGER_OFF,
)

/** One motion sample; every step plus a 2 Hz heartbeat. */
@Serializable
data class CaptureSample(
    val tNs: Long,
    val d: Double,
    val yaw: Double,
    val p: Double? = null,
    /**
     * Magnetic signature (W12-04): |B| and gravity-aligned vertical, µT.
     * Null for samples without a magnetometer reading; pre-W12-04 logs
     * decode with both null and segment exactly as before.
     */
    val mag: Double? = null,
    val magV: Double? = null,
)

@Serializable
data class Mark(
    val tNs: Long,
    val d: Double,
    val label: String,
)
