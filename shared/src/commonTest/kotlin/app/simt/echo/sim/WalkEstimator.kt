package app.simt.echo.sim

import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.Navigator

/** Emitted pose used by the Monte-Carlo scorer. The default is the real navigator. */
interface WalkEstimator {
    val estimatedDistanceM: Double
    val uncertaintyM: Double
    val headingRad: Double
    val headingMetric: String

    fun onSample(sample: SimWalkSample)
}

/** Real navigator. Heading stays the legacy raw sensor yaw, not the handset witness. */
class NavigatorWalkEstimator(
    private val route: EchoRoute,
    engine: NavigatorEngine,
    seed: Long,
) : WalkEstimator {
    private val nav = Navigator(
        route,
        EchoConfig(navigatorEngine = engine),
        ghostSeed = seed xor MonteCarloGate.GHOST_SEED_SALT,
    )
    private var primed = false
    override var estimatedDistanceM: Double = 0.0
        private set
    override var uncertaintyM: Double = 0.0
        private set
    override var headingRad: Double = 0.0
        private set
    override val headingMetric: String = MonteCarloGate.HEADING_METRIC_RAW_SENSOR

    override fun onSample(sample: SimWalkSample) {
        nav.onInput(NavInput.Motion(sample.motion))
        if (!primed) {
            nav.onInput(NavInput.Aligned)
            primed = true
        }
        estimatedDistanceM = route.totalLengthM - nav.ui.value.distanceToNextM
        uncertaintyM = nav.ui.value.uncertaintyM
        headingRad = sample.motion.yawRad
    }
}

/** Ignores the walk and emits one fixed state. Used to prove the gate reads estimator output. */
class FixedWalkEstimator(
    override val estimatedDistanceM: Double,
    override val uncertaintyM: Double,
    override val headingRad: Double,
    override val headingMetric: String = MonteCarloGate.HEADING_METRIC_RAW_SENSOR,
) : WalkEstimator {
    override fun onSample(sample: SimWalkSample) = Unit
}
