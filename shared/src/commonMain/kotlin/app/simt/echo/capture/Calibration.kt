package app.simt.echo.capture

/**
 * Step-length calibration (plan §10 W4-02).
 *
 * The Weinberg step length `L = K · (amax − amin)^0.25` scales linearly
 * with `K`, so a taped walk of known length corrects every future walk by
 * the same ratio — no per-user gait model, one scalar. Pure functions
 * below; the [StepCalibratable] seam pushes the result into whichever
 * [app.simt.echo.platform.MotionSource] is live, so capture, replay, and
 * tests share the correction without knowing each other's types.
 */

/** A motion source whose step length scales with one constant. */
interface StepCalibratable {
    var stepK: Double
}

/**
 * Calibrated constant from a taped walk: `K_new = K · true / measured`.
 * Returns null when the walk measured nothing (no division by zero, no
 * silent zeroing of the constant).
 */
fun calibrateK(currentK: Double, trueDistanceM: Double, measuredDistanceM: Double): Double? {
    if (trueDistanceM <= 0 || measuredDistanceM <= 0) return null
    return currentK * trueDistanceM / measuredDistanceM
}

/** Signed relative error of a calibration walk (`+` = over-reading). */
fun calibrationWalkError(trueDistanceM: Double, measuredDistanceM: Double): Double? {
    if (trueDistanceM <= 0 || measuredDistanceM <= 0) return null
    return (measuredDistanceM - trueDistanceM) / trueDistanceM
}
