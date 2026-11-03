package app.simt.echo.capture.visual

import kotlin.math.abs
import kotlin.math.atan

/** Reused analysis-thread result. Copy primitive fields before retaining it. */
class VisualEgo {
    var expansionPerSec = 0.0
        internal set
    var foeX = Double.NaN
        internal set
    var foeY = Double.NaN
        internal set
    var yawResidualRadPerSec = 0.0
        internal set
    var yawSigmaRadPerSec = Double.POSITIVE_INFINITY
        internal set
    /** Statistical distinction from zero, NOT a geometric/physical validity claim. */
    val yawResolved: Boolean get() = yawSigmaRadPerSec.isFinite() && abs(yawResidualRadPerSec) > 3.0 * yawSigmaRadPerSec
    var confidence01 = 0.0
        internal set
    var inliers = 0
        internal set
    /** Direction survived a depth-free consensus. Radial confidence alone does not set this. */
    var hasFoe = false
        internal set
    val travelOffsetRad: Double get() = if (hasFoe) atan(foeX) else Double.NaN

    internal fun clear() {
        expansionPerSec = 0.0
        foeX = Double.NaN
        foeY = Double.NaN
        yawResidualRadPerSec = 0.0
        yawSigmaRadPerSec = Double.POSITIVE_INFINITY
        confidence01 = 0.0
        inliers = 0
        hasFoe = false
    }
}
