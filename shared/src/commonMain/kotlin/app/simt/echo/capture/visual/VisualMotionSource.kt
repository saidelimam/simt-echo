package app.simt.echo.capture.visual

import app.simt.echo.capture.FrameTap

/** Optional motion-source capability; replay and iOS need not implement it. */
interface VisualMotionSource {
    val visualAssistActive: Boolean
    fun attachVisual(tap: FrameTap, enabled: Boolean, intervalNs: Long = 500_000_000L): Boolean
    fun detachVisual()
    /** Drain only through this step time; null when absent or stale. */
    fun drainVisual(tNs: Long): VisualMotionFrame?
}
