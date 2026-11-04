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
    /** Zero for sources that do not run the visual step gate. */
    val gateCounters: VisualGateCounters get() = VisualGateCounters()
    fun attachVisual(tap: FrameTap, enabled: Boolean, intervalNs: Long = 500_000_000L): Boolean
    fun detachVisual()
    /** Drain only through this step time; null when absent or stale. */
    fun drainVisual(tNs: Long): VisualMotionFrame?
}
