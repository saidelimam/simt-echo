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
)

/**
 * Stream of device motion while walking a route.
 *
 * Implementations: `TrackPlusMotionSource` (reusing simt-app Track) with a
 * built-in PDR fallback, slice W1-01. Replay for navigation tests implements
 * the same seam (W2-05), so capture and navigation never diverge.
 */
interface MotionSource {
    /** Motion at ~20 Hz plus discrete step events ([MotionUpdate.isStep]). */
    val updates: Flow<MotionUpdate>

    fun start()

    fun stop()
}
