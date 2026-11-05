package app.simt.echo.capture.visual

import app.simt.echo.capture.FrameTap

/** Additive visual-gate counters for the trial record and research HUD. */
data class VisualGateCounters(
    val accepted: Int = 0,
    val rejected: Int = 0,
    val unwitnessed: Int = 0,
    val suspects: Int = 0,
)

/** Optional motion-source capability; replay and iOS need not implement it. */
interface VisualMotionSource {
    val visualAssistActive: Boolean
    /** Newest usability sample; 1.0 until a frame has been evaluated. */
    val usability01: Double get() = 1.0
    /** Platform power-save state; false for replay and iOS. */
    val batterySaver: Boolean get() = false
    /** Zero for sources that do not run the visual step gate. */
    val gateCounters: VisualGateCounters get() = VisualGateCounters()
    fun attachVisual(tap: FrameTap, enabled: Boolean, intervalNs: Long = 500_000_000L): Boolean
    fun detachVisual()
    /** Changes analyzer duty after attach; 0 pauses visual analysis. */
    fun setVisualInterval(intervalNs: Long) = Unit
    /** Drain only through this step time; null when absent or stale. */
    fun drainVisual(tNs: Long): VisualMotionFrame?
}
