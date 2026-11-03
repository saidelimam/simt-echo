package app.simt.echo.ui.screens.navigate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.visual.VisualMotionFrame
import app.simt.echo.capture.visual.VisualMotionSource
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.DriftMarker
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.model.Trial
import app.simt.echo.model.TrialMode
import app.simt.echo.nav.NavEvent
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import app.simt.echo.nav.NavWearState
import app.simt.echo.nav.Navigator
import app.simt.echo.nav.endedWearState
import app.simt.echo.nav.toWearState
import app.simt.echo.platform.CueOutput
import app.simt.echo.platform.DistanceHeartbeat
import app.simt.echo.platform.EyesFreeFilter
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.LandmarkMark
import app.simt.echo.platform.LiveUpdateDirector
import app.simt.echo.platform.NAV_LIVE_UPDATE_ID
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.NoCueOutput
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.currentEpochMs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch

/**
 * Navigate screen state (plan W2-06): the [NavUi] flow straight from the
 * [Navigator], so UI states map 1:1 to [NavPhase] by construction —
 * especially UNCERTAIN (the S-reviewed criterion). Null while the route
 * id does not resolve to a saved route.
 */
class NavigateViewModel(
    private val routeId: String,
    routes: RouteRepository,
    private val motion: MotionSource,
    private val haptics: Haptics,
    config: EchoConfig = EchoConfig(),
    private val trials: TrialRepository? = null,
    private val device: String = "unknown",
    /** Baseline (SENSOR_ONLY) run: cards hidden, confirmations disabled.
     * Defaults to the persisted config's mode (W4-02 owns the toggle). */
    val baselineMode: Boolean = config.baselineMode,
    private val nowMs: () -> Long = ::currentEpochMs,
    /**
     * Persisted stride posterior (W12-03, per device). Optional so older
     * call sites keep compiling: when absent the Navigator still learns
     * within the walk, but nothing is stored.
     */
    private val configs: ConfigRepository? = null,
    /**
     * Watch nav mirror sink (plan W13-12). Defaults to a no-op so every
     * existing call site and platform keeps compiling; the Android app
     * passes `GmsWearNavSender::send`, whose own gate holds the ≤ 1 Hz
     * rule — this collector forwards every snapshot and never filters.
     * Declared before [onArrived] (not after) so existing trailing-lambda
     * call sites keep binding their lambda to `onArrived`.
     */
    private val wearNav: (NavWearState) -> Unit = {},
    /**
     * Earcon + announcement output (plan W13-09). Defaults to silence so
     * every existing call site keeps compiling; the screen passes the
     * platform output ([rememberCueOutput]).
     */
    private val cues: CueOutput = NoCueOutput,
    /**
     * Live Update notification director (W13-10), or null when the
     * platform posts none. Driven from the same [ui] flow as everything
     * else; dismissed on arrival and when the screen goes away.
     */
    private val liveUpdates: LiveUpdateDirector? = null,
    /**
     * Ephemeral route (W13-13 practice tour): used directly, never read
     * from nor written to the route store. The tour replays a bundled
     * fixture walk through this same instrument with a null trial sink,
     * so it writes neither `routes/` nor `trials/`.
     */
    ephemeralRoute: SavedRoute? = null,
    private val onArrived: (String) -> Unit,
) : ViewModel() {

    private val route = ephemeralRoute ?: routes.get(routeId)
    private val navigator = route?.let { Navigator(it, config) }
    private val visualEnabled = config.visualAssist && !baselineMode && navigator != null
    val visualAssistRequested: Boolean get() = visualEnabled && motion is VisualMotionSource
    /** Owned by the navigation collector, not an image or a UI snapshot. */
    var visualFrame: VisualMotionFrame? = null
        private set

    fun attachVisualTap(tap: FrameTap): Boolean =
        (motion as? VisualMotionSource)?.attachVisual(
            tap,
            visualAssistRequested,
            // The step gate's ±0.4 s window needs frames faster than the 2 Hz
            // navigation rate, or a candidate is judged with an empty window.
            app.simt.echo.capture.FrameRateGovernor.VISUAL_GATE_INTERVAL_NS,
        ) == true

    fun detachVisualTap() {
        (motion as? VisualMotionSource)?.detachVisual()
    }

    val ui: StateFlow<NavUi?> =
        navigator?.ui ?: MutableStateFlow<NavUi?>(null).asStateFlow()

    val legCount: Int = route?.legs?.size ?: 0

    private val legLengthsM: List<Double> = route?.legs?.map { it.lengthM }.orEmpty()
    private val landmarkMarks: List<LandmarkMark> = route?.let { r ->
        r.landmarks.mapNotNull { landmark ->
            r.nodes.firstOrNull { it.id == landmark.nodeId }
                ?.let { LandmarkMark(it.atDistanceM, landmark.title) }
        }
    }.orEmpty()

    private var arrivedFired = false

    // ── Distance heartbeat (W13-09) ─────────────────────────────────────
    private val heartbeatEnabled = config.turnHeartbeat
    private var heartbeat: Job? = null
    private var heartbeatRemainingM = 0.0

    // ── Trial session stats (plan §11 / W4-01 writer) ──────────────────
    // Counted from the Navigator's own events, never retold: anchors are
    // NavEvent.Anchor, episodes are phase entries, confirmations are the
    // walker's explicit "I see it" taps.
    private var startedAtMs: Long = 0L
    private var anchors = 0
    private var confirmations = 0
    private var uncertainEpisodes = 0
    private var offRouteEpisodes = 0
    private var walkedM = 0.0
    private val markers = mutableListOf<DriftMarker>()

    init {
        val nav = navigator
        if (nav != null) {
            viewModelScope.launch {
                motion.updates.collect {
                    walkedM = it.distanceM
                    if (it.isStep) {
                        val drained = (motion as? VisualMotionSource)?.drainVisual(it.tNs)
                        visualFrame = drained
                        if (drained != null) {
                            nav.onVisualEgo(
                                ego = drained.ego,
                                usability01 = drained.usability01,
                                reason = drained.reason,
                                tNs = drained.tNs,
                            )
                        }
                    }
                    nav.onInput(NavInput.Motion(it))
                }
            }
            viewModelScope.launch {
                nav.events.collect { event ->
                    when (event) {
                        is NavEvent.Haptic -> onCue(event.pattern)
                        is NavEvent.Anchor -> {
                            anchors++
                            configs?.setLearnedStride(nav.learnedStrideK, nav.learnedStrideLegs)
                        }
                        is NavEvent.Phase -> when (event.to) {
                            NavPhase.UNCERTAIN -> uncertainEpisodes++
                            NavPhase.OFF_ROUTE -> offRouteEpisodes++
                            else -> Unit
                        }
                        is NavEvent.Arrived ->
                            if (!arrivedFired) {
                                arrivedFired = true
                                recordTrial()
                                onArrived(routeId)
                            }
                    }
                }
            }
            viewModelScope.launch {
                nav.ui.collect { ui ->
                    if (ui != null) {
                        updateHeartbeat(ui)
                        runCatching { wearNav(ui.toWearState()) }
                        runCatching {
                            liveUpdates?.updateNav(
                                ui = ui,
                                legLengthsM = legLengthsM,
                                landmarks = landmarkMarks,
                                routeName = route?.name.orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * One navigation cue: felt (haptics, mirrored to the watch), heard (the
     * opt-in earcon, gated by the platform output) and, when the walker may
     * be eyes-free, announced — decision points only ([EyesFreeFilter]):
     * confirmations and heartbeat ticks are felt, never spoken.
     */
    private fun onCue(pattern: HapticPattern) {
        haptics.play(pattern)
        val nav = ui.value ?: return
        cues.earcon(pattern, nav.arrowRad)
        if (cues.eyesFree && EyesFreeFilter.isDecisionPoint(pattern)) cues.announce(nav.instruction)
    }

    /**
     * Replays TICK on the [DistanceHeartbeat] schedule while a turn is
     * within 8 m (APPROACH on a turn node), quickening as the remaining
     * distance shrinks. Runs from the nav state alone, so any phase change
     * (TURNING, UNCERTAIN, OFF_ROUTE, ARRIVED…) cancels it.
     */
    private fun updateHeartbeat(ui: NavUi) {
        if (!heartbeatEnabled || heartbeatIntervalMs(ui) == null) {
            stopHeartbeat()
            return
        }
        heartbeatRemainingM = ui.distanceToNextM
        if (heartbeat != null) return
        heartbeat = viewModelScope.launch {
            while (isActive) {
                delay(DistanceHeartbeat.intervalMs(heartbeatRemainingM).milliseconds)
                haptics.play(HapticPattern.TICK)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    /** Arrival writes the run (W4-01): Arrived's form completes §11 fields. */
    private fun recordTrial() {
        val store = trials ?: return
        val started = if (startedAtMs > 0L) startedAtMs else nowMs()
        store.save(
            Trial(
                trialId = "trial-$routeId-$started",
                routeId = routeId,
                mode = if (baselineMode) TrialMode.SENSOR_ONLY else TrialMode.ECHO,
                device = device,
                startedAtEpochMs = started,
                durationS = (nowMs() - started).coerceAtLeast(0L) / 1000.0,
                distanceM = walkedM,
                arrived = true,
                anchors = anchors,
                confirmations = confirmations,
                uncertainEpisodes = uncertainEpisodes,
                offRouteEpisodes = offRouteEpisodes,
                markers = markers.toList(),
            ),
        )
    }

    /** Starts the motion source; called once from the screen, not init. */
    fun start() {
        if (startedAtMs == 0L) startedAtMs = nowMs()
        motion.start()
    }

    /** Zeroes the odometer and heading here (the Align screen's confirmation). */
    fun align() {
        navigator?.onInput(NavInput.Aligned)
    }

    fun seeLandmark() {
        // Baseline honesty: in SENSOR_ONLY runs the confirmation does not
        // exist — the screen hides the button, and the count stays clean
        // even if a caller slips through.
        if (baselineMode) return
        confirmations++
        navigator?.onInput(NavInput.SeeLandmark)
    }

    /**
     * "At marker" debug tap (§10 W4-01): the walker stands on a taped
     * marker whose route distance is the current target node's; log the
     * estimate beside it for drift reporting.
     */
    fun atMarker() {
        val nav = ui.value ?: return
        val target = route?.nodes?.getOrNull(nav.legIndex + 1) ?: return
        markers += DriftMarker(
            expectedNodeDistanceM = target.atDistanceM,
            estimatedDistanceM = walkedM,
            atEpochMs = nowMs(),
        )
    }

    fun notYet() {
        navigator?.onInput(NavInput.NotYet)
    }

    fun lost() {
        navigator?.onInput(NavInput.Lost)
    }

    fun restartFromLast() {
        navigator?.onInput(NavInput.RestartFromLast)
    }

    fun restartFromStart() {
        navigator?.onInput(NavInput.RestartFromStart)
    }

    fun imHere() {
        navigator?.onInput(NavInput.ImHere)
    }

    /** Last-leg check for the contextual "I'm here" action. */
    fun isLastLeg(nav: NavUi): Boolean = nav.legIndex >= legCount - 1

    /**
     * Fingerprint chip confirm (W12-05 → W13-08 instrument): the user
     * tapped "That's the X?" — resolves into a soft anchor. No-op
     * without a pending prompt (Navigator owns the rule).
     */
    fun confirmFingerprint() {
        navigator?.confirmFingerprint()
    }

    /** Fingerprint chip dismiss: proposal dropped, no anchor. */
    fun dismissFingerprint() {
        navigator?.dismissFingerprint()
    }

    override fun onCleared() {
        stopHeartbeat()
        // Tell the watch the walk is over, so it does not keep an arrow up.
        runCatching { wearNav(endedWearState()) }
        runCatching { liveUpdates?.dismiss(NAV_LIVE_UPDATE_ID) }
        detachVisualTap()
        motion.stop()
    }
}

/**
 * Heartbeat interval for [ui], or null when no heartbeat is due: only
 * while approaching a turn that is within [DistanceHeartbeat.RANGE_M].
 */
internal fun heartbeatIntervalMs(ui: NavUi): Long? =
    if (ui.phase == NavPhase.APPROACH && ui.nextTurn != null &&
        ui.distanceToNextM <= DistanceHeartbeat.RANGE_M
    ) {
        DistanceHeartbeat.intervalMs(ui.distanceToNextM)
    } else {
        null
    }
