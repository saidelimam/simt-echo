package app.simt.echo.capture

import app.simt.echo.data.DraftRepository
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.Mark
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Capture lifecycle (plan W1-03): IDLE → READY → RECORDING → FINISHING → SAVED. */
enum class CaptureState {
    IDLE,
    READY,
    RECORDING,
    FINISHING,
    SAVED,
}

/** Live heads-up state the Record screen renders. */
data class CaptureHud(
    val distanceM: Double = 0.0,
    val steps: Int = 0,
    val elapsedMs: Long = 0L,
    val marks: Int = 0,
)

/**
 * One recording session (plan W1-03). Joins [MotionSource] and
 * [CameraRecorder] on the shared monotonic clock, stores every step plus
 * a 500 ms heartbeat as [CaptureSample]s, autosaves the in-progress
 * [CaptureLog] every 10 s, and writes the final log plus the segmented
 * draft route on [save] (plan W1-04).
 *
 * Pure orchestration: clocks and ids are injected, so host tests drive
 * the whole lifecycle with fakes and a [DraftRepository] on FakeFileSystem.
 */
class CaptureSession(
    private val motion: MotionSource,
    private val camera: CameraRecorder,
    private val haptics: Haptics,
    private val drafts: DraftRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val newDraftId: () -> String,
    private val autosaveEveryMs: Long = 10_000L,
    private val config: EchoConfig = EchoConfig(),
) {
    private val _state = MutableStateFlow(CaptureState.IDLE)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _hud = MutableStateFlow(CaptureHud())
    val hud: StateFlow<CaptureHud> = _hud.asStateFlow()

    private var draftId: String? = null
    private var videoStartNs: Long = 0L
    private var startedAtEpochMs: Long = 0L
    private var startElapsedNs: Long = 0L
    private var motionSourceName: String = "echo_builtin_pdr"
    private val samples = mutableListOf<CaptureSample>()
    private val marks = mutableListOf<Mark>()
    private var lastSampleNs: Long = Long.MIN_VALUE
    private var collectJob: Job? = null
    private var autosaveJob: Job? = null

    /** Preview is live; [start] may be called. */
    fun ready() {
        if (_state.value == CaptureState.IDLE) _state.value = CaptureState.READY
    }

    /**
     * Creates the draft, takes the start photo, starts motion + video.
     * Legal only from READY; anything else is a programmer error.
     */
    suspend fun start() {
        check(_state.value == CaptureState.READY) { "start() from ${_state.value}" }
        val id = newDraftId()
        draftId = id
        startedAtEpochMs = nowMs()
        // Subscribe before motion starts so no early step is lost.
        collectJob = scope.launch { motion.updates.collect(::onUpdate) }
        camera.takePhoto(drafts.draftFilePath(id, START_PHOTO))
        motion.start()
        videoStartNs = camera.startVideo(drafts.draftFilePath(id, VIDEO_FILE))
        _state.value = CaptureState.RECORDING
        autosaveJob = scope.launch {
            while (isActive) {
                delay(autosaveEveryMs)
                if (_state.value == CaptureState.RECORDING) drafts.saveCapture(buildLog())
            }
        }
    }

    /** Drops a "Spot n" mark at the current distance; CONFIRM haptic. */
    fun mark() {
        if (_state.value != CaptureState.RECORDING) return
        val index = marks.size + 1
        marks += Mark(
            // Before the first sample there is no honest timestamp; 0 marks it.
            tNs = if (lastSampleNs == Long.MIN_VALUE) 0L else lastSampleNs,
            d = _hud.value.distanceM,
            label = "Spot $index",
        )
        _hud.update { it.copy(marks = marks.size) }
        haptics.play(HapticPattern.CONFIRM)
    }

    /** Long-press Finish: stops motion + video, enters FINISHING. */
    suspend fun beginFinish() {
        check(_state.value == CaptureState.RECORDING) { "beginFinish() from ${_state.value}" }
        _state.value = CaptureState.FINISHING
        collectJob?.cancel()
        autosaveJob?.cancel()
        motion.stop()
        camera.stopVideo()
    }

    /**
     * Finish-sheet Save: writes the final `capture.json`, then segments the
     * log into a draft `route.draft.json` (W1-04) and ends the session.
     */
    suspend fun save(routeName: String, destinationLabel: String): CaptureLog {
        check(_state.value == CaptureState.FINISHING) { "save() from ${_state.value}" }
        val log = buildLog(routeName = routeName, destinationLabel = destinationLabel)
        drafts.saveCapture(log)
        // Draft routes are keyed by draftId until Save assigns permanence.
        drafts.saveDraftRoute(segment(log, config, log.draftId, nowMs()))
        _state.value = CaptureState.SAVED
        return log
    }

    /** [beginFinish] + [save] in one call (tests, headless callers). */
    suspend fun finish(routeName: String, destinationLabel: String): CaptureLog {
        beginFinish()
        return save(routeName, destinationLabel)
    }

    private fun onUpdate(update: MotionUpdate) {
        motionSourceName = update.source
        if (startElapsedNs == 0L) startElapsedNs = update.tNs
        val isHeartbeat = update.tNs - lastSampleNs >= HEARTBEAT_NS
        if (update.isStep || isHeartbeat) {
            samples += CaptureSample(
                tNs = update.tNs,
                d = update.distanceM,
                yaw = update.yawRad,
                p = update.pressureHpa,
            )
            lastSampleNs = update.tNs
        }
        _hud.update {
            it.copy(
                distanceM = update.distanceM,
                steps = update.stepCount,
                elapsedMs = (update.tNs - startElapsedNs) / 1_000_000L,
            )
        }
    }

    private fun buildLog(
        routeName: String = "",
        destinationLabel: String = "",
    ): CaptureLog {
        val id = checkNotNull(draftId) { "buildLog before start()" }
        return CaptureLog(
            draftId = id,
            startedAtEpochMs = startedAtEpochMs,
            motionSource = motionSourceName,
            videoStartNs = videoStartNs,
            samples = samples.toList(),
            marks = marks.toList(),
            startPhoto = START_PHOTO,
            routeName = routeName,
            destinationLabel = destinationLabel,
        )
    }

    companion object {
        const val START_PHOTO = "start.jpg"
        const val VIDEO_FILE = "video.mp4"
        const val HEARTBEAT_NS = 500_000_000L
    }
}
