package app.simt.echo.capture

import app.simt.echo.data.DraftRepository
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.Mark
import app.simt.echo.model.PerceptionMode
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /**
     * Ledger mode stamped into every `capture.json` (plan W10-03). A
     * supplier, not a snapshot: the recorder learns its bind outcome
     * lazily, so the mode is read when the log is built. Defaults to
     * [PerceptionMode.LEDGER_OFF] — recording behaviour is otherwise
     * unchanged, and fakes that report nothing stay honest.
     */
    private val perceptionMode: () -> String = { PerceptionMode.LEDGER_OFF },
) {
    private val _state = MutableStateFlow(CaptureState.IDLE)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _hud = MutableStateFlow(CaptureHud())
    val hud: StateFlow<CaptureHud> = _hud.asStateFlow()

    // Hot and lossy on purpose: the analysis thread emits with tryEmit, and a
    // slow or absent collector costs the oldest offer, never a stalled frame.
    private val _ledgerOffers = MutableSharedFlow<LedgerOffer>(
        extraBufferCapacity = LEDGER_OFFER_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every glimpse-ledger outcome while recording, for presentation readers only. */
    val ledgerOffers: SharedFlow<LedgerOffer> = _ledgerOffers.asSharedFlow()

    private var draftId: String? = null
    private var videoStartNs: Long = 0L
    private var startedAtEpochMs: Long = 0L
    private var startElapsedNs: Long = 0L
    private var motionSourceName: String = "echo_builtin_pdr"
    // `samples` and `marks` are written by the motion collector and the
    // `mark()` caller, and read by the autosave and the live checkpoint
    // reader on other threads: every touch goes through [logLock].
    private val logLock = PoolLock()
    private val samples = mutableListOf<CaptureSample>()
    private val marks = mutableListOf<Mark>()
    private var lastSampleNs: Long = Long.MIN_VALUE
    private var collectJob: Job? = null
    private var autosaveJob: Job? = null
    private var resumedDraftId: String? = null
    private var ledger: RecordingLedger? = null

    /** Preview is live; [start] may be called. */
    fun ready() {
        if (_state.value == CaptureState.IDLE) _state.value = CaptureState.READY
    }

    /**
     * Reopens an interrupted draft (W4-03 process-death recovery): the
     * autosaved capture log is reloaded into this session, and [start]
     * continues recording into the SAME draft — samples and marks append,
     * the draft id and original start time survive. Video restarts (the
     * pre-kill take is replaced); the sensor timeline is what segments.
     * Returns false when the draft has no readable capture log.
     */
    fun resume(draftId: String): Boolean {
        if (_state.value != CaptureState.IDLE) return false
        val log = runCatching { drafts.loadCapture(draftId) }.getOrNull() ?: return false
        resumedDraftId = draftId
        logLock.withLock {
            samples += log.samples
            marks += log.marks
        }
        startedAtEpochMs = log.startedAtEpochMs
        lastSampleNs = log.samples.lastOrNull()?.tNs ?: Long.MIN_VALUE
        _hud.update {
            it.copy(
                distanceM = log.samples.lastOrNull()?.d ?: 0.0,
                marks = marks.size,
            )
        }
        _state.value = CaptureState.READY
        return true
    }

    /**
     * Creates the draft, starts motion + video and arms the glimpse ledger
     * (the start photo is produced later from the ledger). Legal only from
     * READY; anything else is a programmer error.
     */
    suspend fun start() {
        check(_state.value == CaptureState.READY) { "start() from ${_state.value}" }
        val id = resumedDraftId ?: newDraftId()
        draftId = id
        if (resumedDraftId == null) startedAtEpochMs = nowMs()
        // Calibrated geometry in, raw sensors out: the configured step K
        // reaches any calibratable source (W4-02); others ignore it.
        (motion as? StepCalibratable)?.stepK = config.stepK
        val recordingLedger = RecordingLedger(drafts, id, camera.frameTap, onOffer = { _ledgerOffers.tryEmit(it) })
        ledger = recordingLedger
        // Subscribe before motion starts so no early step is lost.
        collectJob = scope.launch { motion.updates.collect(::onUpdate) }
        motion.start()
        recordingLedger.attach()
        try {
            videoStartNs = camera.startVideo(drafts.draftFilePath(id, VIDEO_FILE))
        } catch (e: Throwable) {
            // A failed start leaves nothing running: the session stays READY
            // and a retry must not stack a second collector or consumer.
            recordingLedger.detach()
            collectJob?.cancel()
            motion.stop()
            throw e
        }
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
        val count = logLock.withLock {
            marks += Mark(
                // Before the first sample there is no honest timestamp; 0 marks it.
                tNs = if (lastSampleNs == Long.MIN_VALUE) 0L else lastSampleNs,
                d = _hud.value.distanceM,
                label = "Spot ${marks.size + 1}",
            )
            marks.size
        }
        _hud.update { it.copy(marks = count) }
        haptics.play(HapticPattern.CONFIRM)
    }

    /**
     * The draft this session records into, or null before [start]. Read by
     * the walk-time uploader, which stages against the same draft.
     */
    val activeDraftId: String? get() = draftId

    /**
     * A consistent copy of the log so far, for readers that segment while the
     * walk is still going (plan W11-09). Null before [start]; cheap enough to
     * call every few metres, safe from any thread.
     */
    fun liveLog(): CaptureLog? = if (draftId == null) null else buildLog()

    /**
     * The glimpse-ledger entries kept so far (frames are already in the
     * draft); empty before [start] or when the recorder kept none.
     */
    fun liveLedger(): List<GlimpseEntry> = ledger?.snapshot().orEmpty()

    /** Long-press Finish: stops motion + video, enters FINISHING. */
    suspend fun beginFinish() {
        check(_state.value == CaptureState.RECORDING) { "beginFinish() from ${_state.value}" }
        _state.value = CaptureState.FINISHING
        ledger?.detach()
        collectJob?.cancel()
        autosaveJob?.cancel()
        motion.stop()
        try {
            camera.stopVideo()
        } finally {
            // Frames stopped with the consumer; keep what the ledger kept.
            ledger?.persist()
        }
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
        ledger?.onMotion(update)
        motionSourceName = update.source
        if (startElapsedNs == 0L) startElapsedNs = update.tNs
        val isHeartbeat = update.tNs - lastSampleNs >= HEARTBEAT_NS
        if (update.isStep || isHeartbeat) {
            val sample = CaptureSample(
                tNs = update.tNs,
                d = update.distanceM,
                yaw = update.yawRad,
                p = update.pressureHpa,
                mag = update.magMag,
                magV = update.magVert,
            )
            logLock.withLock { samples += sample }
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
            samples = logLock.withLock { samples.toList() },
            marks = logLock.withLock { marks.toList() },
            startPhoto = START_PHOTO,
            routeName = routeName,
            destinationLabel = destinationLabel,
            perceptionMode = perceptionMode(),
        )
    }

    companion object {
        const val START_PHOTO = "start.jpg"
        const val VIDEO_FILE = "video.mp4"
        const val HEARTBEAT_NS = 500_000_000L

        /** Ledger offers buffered for a slow reader (8 Hz frames ≈ 2 s of slack). */
        const val LEDGER_OFFER_BUFFER = 16
    }
}
