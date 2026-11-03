package app.simt.echo.capture.visual

/** Reused ring-drain output, owned exclusively by the navigation thread. */
class VisualMotionFrame {
    var tNs = 0L
        internal set
    val ego = VisualEgo()
    var usability01 = 0.0
        internal set
    var reason = VisualReason.UNKNOWN
        internal set
}
