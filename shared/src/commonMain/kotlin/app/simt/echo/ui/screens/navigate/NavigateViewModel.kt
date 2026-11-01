package app.simt.echo.ui.screens.navigate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.nav.NavEvent
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.NavUi
import app.simt.echo.nav.Navigator
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
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
    config: EchoConfig = EchoConfig(),
    private val onArrived: (String) -> Unit,
) : ViewModel() {

    private val route = routes.get(routeId)
    private val navigator = route?.let { Navigator(it, config) }

    val ui: StateFlow<NavUi?> =
        navigator?.ui ?: MutableStateFlow<NavUi?>(null).asStateFlow()

    val legCount: Int = route?.legs?.size ?: 0

    private var arrivedFired = false

    init {
        val nav = navigator
        if (nav != null) {
            viewModelScope.launch {
                motion.updates.collect { nav.onInput(NavInput.Motion(it)) }
            }
            viewModelScope.launch {
                nav.events.collect { event ->
                    when (event) {
                        is NavEvent.Haptic -> haptics.play(event.pattern)
                        is NavEvent.Arrived ->
                            if (!arrivedFired) {
                                arrivedFired = true
                                onArrived(routeId)
                            }

                        else -> Unit
                    }
                }
            }
        }
    }

    /** Starts the motion source; called once from the screen, not init. */
    fun start() {
        motion.start()
    }

    /** Zeroes the odometer and heading here (the Align screen's confirmation). */
    fun align() {
        navigator?.onInput(NavInput.Aligned)
    }

    fun seeLandmark() {
        navigator?.onInput(NavInput.SeeLandmark)
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
