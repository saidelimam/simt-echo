package app.simt.echo.ui.screens.navigate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.DriftMarker
import app.simt.echo.model.Trial
import app.simt.echo.model.TrialMode
import app.simt.echo.nav.NavEvent
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import app.simt.echo.nav.Navigator
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.currentEpochMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val trials: TrialRepository? = null,
    private val device: String = "unknown",
    /** Baseline (SENSOR_ONLY) run: cards hidden, confirmations disabled. */
    val baselineMode: Boolean = false,
    private val nowMs: () -> Long = ::currentEpochMs,
    private val onArrived: (String) -> Unit,
) : ViewModel() {

    private val route = routes.get(routeId)
    private val navigator = route?.let(::Navigator)

    val ui: StateFlow<NavUi?> =
        navigator?.ui ?: MutableStateFlow<NavUi?>(null).asStateFlow()

    val legCount: Int = route?.legs?.size ?: 0

    private var arrivedFired = false

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
                    nav.onInput(NavInput.Motion(it))
                }
            }
            viewModelScope.launch {
                nav.events.collect { event ->
                    when (event) {
                        is NavEvent.Haptic -> haptics.play(event.pattern)
                        is NavEvent.Anchor -> anchors++
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
        }
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

    override fun onCleared() {
        motion.stop()
    }
}
