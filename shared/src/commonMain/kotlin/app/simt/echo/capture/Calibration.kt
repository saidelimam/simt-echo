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

/** Plausible Weinberg K band. Outside it a "calibration" is a typo, a
 * unit error, or a corrupt file — never a gait. (Defaults sit at 0.48;
 * real gaits calibrate within a few tens of percent of it.) */
const val STEP_K_MIN = 0.15
const val STEP_K_MAX = 1.5
const val STEP_K_DEFAULT = 0.48

/** True when [k] is a finite, plausible step constant. */
fun isPlausibleStepK(k: Double): Boolean = k.isFinite() && k in STEP_K_MIN..STEP_K_MAX

/**
 * Calibrated constant from a taped walk: `K_new = K · true / measured`.
 * Returns null when the walk measured nothing (no division by zero, no
 * silent zeroing of the constant), when any input is non-finite, or when
 * the result leaves the plausible band — a rejected calibration keeps
 * the old K instead of poisoning every future walk (review 0002 R2).
 */
fun calibrateK(currentK: Double, trueDistanceM: Double, measuredDistanceM: Double): Double? {
    if (!currentK.isFinite() || !trueDistanceM.isFinite() || !measuredDistanceM.isFinite()) return null
    if (trueDistanceM <= 0 || measuredDistanceM <= 0) return null
    val next = currentK * trueDistanceM / measuredDistanceM
    return next.takeIf(::isPlausibleStepK)
}

/** Signed relative error of a calibration walk (`+` = over-reading). */
fun calibrationWalkError(trueDistanceM: Double, measuredDistanceM: Double): Double? {
    if (trueDistanceM <= 0 || measuredDistanceM <= 0) return null
    return (measuredDistanceM - trueDistanceM) / trueDistanceM
}
