package app.simt.echo.capture.visual

import app.simt.echo.model.ViewStrip

/** Reused ring-drain output, owned exclusively by the navigation thread. */
class VisualMotionFrame {
    var tNs = 0L
        internal set
    val ego = VisualEgo()
    var usability01 = 0.0
        internal set
    var reason = VisualReason.UNKNOWN
        internal set
    /** Latest live view strip for W17-03, valid only when [hasViewStrip]. */
    val viewStrip = ByteArray(ViewStrip.BYTES)
    var hasViewStrip = false
        internal set
}
