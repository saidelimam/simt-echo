package app.simt.echo.capture.visual

/** Device-to-world unit quaternion, sampled at the requested frame time. */
class VisualFramePose {
    var qw = 1.0
    var qx = 0.0
    var qy = 0.0
    var qz = 0.0
    var mode = PoseMode.UNKNOWN
    var angularRateRadPerSec = 0.0
}

/** One analysis-thread reader; implementations never retain target objects. */
interface VisualMotionInput {
    fun isAvailable(tNs: Long): Boolean
    fun readAt(tNs: Long, meanLuma: Double, target: VisualFramePose): Boolean
}
