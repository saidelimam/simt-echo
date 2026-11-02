package app.simt.echo.platform

import kotlinx.coroutines.flow.Flow

/**
 * One inertial motion sample or step event.
 *
 * Geometry comes from sensors only (implementation plan §0): AI code never
 * creates or mutates these values. Field semantics are frozen with the domain
 * model in slice W0-04; this seam shape follows plan §5.
 */
data class MotionUpdate(
    val tNs: Long,
    val distanceM: Double,
    val yawRad: Double,
    val stepCount: Int,
    val isStep: Boolean,
    val pressureHpa: Double?,
    val source: String,
    /**
     * Heading quality q ∈ [0,1] from W12-01 stillness/drift scoring
     * (1.0 = fully trusted). Replay and older producers leave the default.
     */
    val headingQuality: Double = 1.0,
)

/**
 * Stream of device motion while walking a route.
 *
 * Implementations: `BuiltInPdrMotionSource` (slice W1-01, engine in
 * `capture/`), and replay for navigation tests (W2-05) — so capture and
 * navigation never diverge. ECHO is fully enclosed: no implementation
 * depends on any external app or engine.
 */
interface MotionSource {
    /** Motion at ~20 Hz plus discrete step events ([MotionUpdate.isStep]). */
    val updates: Flow<MotionUpdate>

    fun start()

    fun stop()
}
