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
    /**
     * Magnetic signature at emit time (W12-04): total-field magnitude |B|
     * and gravity-aligned vertical component, µT. Null without a
     * magnetometer (or before the first game-rotation sample aligns it).
     * Replay forwards what the log carries; older logs carry nothing.
     */
    val magMag: Double? = null,
    val magVert: Double? = null,
    /**
     * Slowly-varying camera-to-travel heading offset (rad, W16-03) estimated
     * from the visual Focus of Expansion (`travelYaw - cameraYaw`). Zero when
     * visual assist is off or no FOE has been observed, so existing callers
     * and baseline behaviour are bit-identical.
     */
    val travelOffsetRad: Double = 0.0,
    /**
     * True when visual expansion indicates forward motion while the
     * accelerometer missed a step (slow shuffle, W16-04). Raises navigation σ
     * without inventing distance or step count.
     */
    val missedStepSuspect: Boolean = false,
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
