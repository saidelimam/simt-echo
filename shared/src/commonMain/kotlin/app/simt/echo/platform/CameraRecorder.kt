package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import okio.Path

/**
 * Camera seam (implementation plan §5.1). The Android actual records silent
 * video alongside [MotionSource] on the same monotonic clock
 * (`SystemClock.elapsedRealtimeNanos`), so sensor samples and frames share
 * one timeline without conversion.
 */
interface CameraRecorder {
    /** Live preview. Binds on first composition, unbinds on dispose. */
    @Composable
    fun Preview(modifier: Modifier)

    /** Single still into [out], e.g. the start photo. */
    suspend fun takePhoto(out: Path): Path

    /**
     * Starts silent video into [out].
     * @return `tNs` of the recording start on the [MotionUpdate] clock,
     * resolved by [resolveVideoStartNs].
     */
    suspend fun startVideo(out: Path): Long

    /** Stops recording and returns [out] once the file is final. */
    suspend fun stopVideo(): Path
}

/** Lifecycle of one capture session. See [transition] for legal moves. */
enum class RecorderState {
    IDLE,
    PREVIEW,
    RECORDING,
}

/** Events the UI and the camera backend feed the state machine. */
enum class RecorderEvent {
    PREVIEW_READY,
    PREVIEW_LOST,
    START_VIDEO,
    STOP_VIDEO,
}

/**
 * Pure transition table. Invalid moves return null instead of throwing so
 * the UI can ignore a double-tap Finish rather than crashing on it.
 */
fun transition(state: RecorderState, event: RecorderEvent): RecorderState? =
    when (state) {
        RecorderState.IDLE ->
            when (event) {
                RecorderEvent.PREVIEW_READY -> RecorderState.PREVIEW
                else -> null
            }
        RecorderState.PREVIEW ->
            when (event) {
                RecorderEvent.PREVIEW_LOST -> RecorderState.IDLE
                RecorderEvent.START_VIDEO -> RecorderState.RECORDING
                else -> null
            }
        RecorderState.RECORDING ->
            when (event) {
                RecorderEvent.STOP_VIDEO -> RecorderState.PREVIEW
                else -> null
            }
    }

/**
 * Resolves the authoritative recording-start `tNs`.
 *
 * The camera stack reports start on its own callback ([eventNs]); when that
 * timestamp is missing or non-positive (missed callback, clock jump) the
 * call-site reading ([callNs]) is the honest fallback. Both must share the
 * monotonic `elapsedRealtimeNanos` base, otherwise capture and motion drift
 * apart silently.
 */
fun resolveVideoStartNs(eventNs: Long?, callNs: Long): Long =
    if (eventNs != null && eventNs > 0L) eventNs else callNs
