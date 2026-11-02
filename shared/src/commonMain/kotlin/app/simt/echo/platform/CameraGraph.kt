package app.simt.echo.platform

/**
 * Camera use-case graph per screen (plan W10-03).
 *
 * - **Record** = Preview + Video + Analysis ([RECORD_FULL]); ImageCapture
 *   is dropped — the start photo is the ledger's best frame at d≈0 (the
 *   ledger itself lands in W11-01; until then `takePhoto` has no still
 *   source on the full graph).
 * - **Align** = Preview + Analysis ([ALIGN]).
 * - **Navigate** = Preview + Analysis with overlay ([NAVIGATE]),
 *   Analysis-only otherwise ([ANALYSIS_ONLY]; W12-05 decides when it runs).
 *
 * The fallback ladder is by real bind attempt, not capability guess:
 * `[P,V,A] → [P,V]` with the ledger off, recorded in `capture.json` as
 * `perceptionMode` ([RECORD_FALLBACK]).
 */
enum class CameraGraph {
    RECORD_FULL,
    RECORD_FALLBACK,
    ALIGN,
    NAVIGATE,
    ANALYSIS_ONLY,
}

/** Screen selecting the graph; [cameraGraphFor] is the single mapping. */
enum class CaptureScreen {
    RECORD,
    ALIGN,
    NAVIGATE,
    OTHER,
}

/**
 * The single screen → graph mapping. Navigate drops Preview only when the
 * overlay is off; anything unrecognised analyses without previewing.
 */
fun cameraGraphFor(screen: CaptureScreen, overlayEnabled: Boolean = true): CameraGraph =
    when (screen) {
        CaptureScreen.RECORD -> CameraGraph.RECORD_FULL
        CaptureScreen.ALIGN -> CameraGraph.ALIGN
        CaptureScreen.NAVIGATE ->
            if (overlayEnabled) CameraGraph.NAVIGATE else CameraGraph.ANALYSIS_ONLY
        CaptureScreen.OTHER -> CameraGraph.ANALYSIS_ONLY
    }

/** True when the graph binds a visible preview stream. */
val CameraGraph.hasPreview: Boolean
    get() = this == CameraGraph.RECORD_FULL ||
        this == CameraGraph.RECORD_FALLBACK ||
        this == CameraGraph.ALIGN ||
        this == CameraGraph.NAVIGATE

/** True when the graph binds video recording (Record only). */
val CameraGraph.hasVideo: Boolean
    get() = this == CameraGraph.RECORD_FULL || this == CameraGraph.RECORD_FALLBACK

/** True when the graph binds the frame-analysis tap. */
val CameraGraph.hasAnalysis: Boolean
    get() = this == CameraGraph.RECORD_FULL ||
        this == CameraGraph.ALIGN ||
        this == CameraGraph.NAVIGATE ||
        this == CameraGraph.ANALYSIS_ONLY
