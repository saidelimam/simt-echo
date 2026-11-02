package app.simt.echo.platform

/**
 * Camera use-case graph per screen (plan W10-03).
 *
 * - **Record** = Preview + Video + Analysis ([RECORD_FULL]); there is no
 *   ImageCapture — the start photo is the ledger's best frame at d≈0
 *   (`startPhotoEntry`), fed by the Frame Tap consumer the recording
 *   registers.
 * - **Align** = Preview + Analysis ([ALIGN]).
 * - **Navigate** = Preview + Analysis with overlay ([NAVIGATE]),
 *   Analysis-only otherwise ([ANALYSIS_ONLY]; W12-05 decides when it runs).
 *   Analysis with no consumer is wasted work, so [ANALYSIS_ONLY] binds
 *   only when a consumer is registered at bind time ([bindsAnalysis]).
 *
 * The fallback ladder is by real bind attempt, not capability guess:
 * `[P,V,A] → [P,V]` with the ledger off, recorded in `capture.json` as
 * `perceptionMode`.
 */
enum class CameraGraph {
    RECORD_FULL,
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
        this == CameraGraph.ALIGN ||
        this == CameraGraph.NAVIGATE

/** True when the graph binds video recording (Record only). */
val CameraGraph.hasVideo: Boolean
    get() = this == CameraGraph.RECORD_FULL

/** True when the graph binds the frame-analysis tap. */
val CameraGraph.hasAnalysis: Boolean
    get() = this == CameraGraph.RECORD_FULL ||
        this == CameraGraph.ALIGN ||
        this == CameraGraph.NAVIGATE ||
        this == CameraGraph.ANALYSIS_ONLY

/**
 * True when the graph should bind its analysis use case: graphs with a
 * preview always do (a consumer may register once recording starts), but
 * [CameraGraph.ANALYSIS_ONLY] exists only to feed a consumer, so without
 * one it would run the camera (privacy indicator, battery) for nothing.
 */
fun CameraGraph.bindsAnalysis(hasConsumer: Boolean): Boolean =
    hasAnalysis && (hasPreview || hasConsumer)
