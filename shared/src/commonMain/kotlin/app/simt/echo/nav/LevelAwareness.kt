package app.simt.echo.nav

import app.simt.echo.capture.hPaToM
import kotlin.math.abs

/**
 * Live level awareness (plan W12-08).
 *
 * Pure: feed barometer readings plus the odometer step count and the
 * clock, get back at most one [LevelEvent] per leg. Δh is measured from
 * the leg-start baseline (relative pressure, no sea-level assumption); the
 * event fires once |Δh| crosses [thresholdM] (W12-08: 2.2 m, shared with
 * [app.simt.echo.model.EchoConfig.levelThresholdM]) *and holds there* for
 * [HOLD_NS]: a door slam or a gust is a sub-second pressure transient, and
 * it must not hard-anchor the filter at a level node.
 *
 * The baseline is the median of the first [BASELINE_SAMPLES] plausible
 * readings of the leg, not a single raw sample, so one noisy first reading
 * cannot skew every Δh that follows. Readings outside
 * [MIN_PLAUSIBLE_HPA]..[MAX_PLAUSIBLE_HPA] (a zero, a NaN, a glitch) are
 * ignored: they would otherwise divide by zero in the barometric formula.
 *
 * Stairs vs lift follows the plan: steps observed while |Δh| grows from
 * the arming deadband to the threshold read as stairs; a silent climb
 * reads as a lift. The deadband keeps flat walking before the change —
 * and barometer jitter inside it — out of the step window.
 */
class LevelAwareness(
    private val thresholdM: Double = LEVEL_CROSSING_M,
) {
    /** How the level change was travelled. */
    enum class Mode {
        STAIRS,
        LIFT,
    }

    /** One fired crossing: signed climb in metres plus the travel mode. */
    data class LevelEvent(val deltaHM: Double, val mode: Mode)

    /** Readings collected towards the baseline median. */
    private val baselineBuffer = DoubleArray(BASELINE_SAMPLES)
    private var baselineCount = 0

    /** Leg-start pressure datum; NaN until [BASELINE_SAMPLES] readings. */
    private var baselineHPa = Double.NaN

    /** Clock reading where |Δh| first stayed at/over the threshold; null while under. */
    private var crossingSinceNs: Long? = null

    /** Step count where |Δh| first left the deadband; null until armed. */
    private var armedStepCount: Int? = null

    /** Latched once the crossing fires; cleared by [resetLeg]. */
    private var fired = false

    /** Starts a new leg: the next sample becomes the pressure datum. */
    fun resetLeg() {
        baselineCount = 0
        baselineHPa = Double.NaN
        crossingSinceNs = null
        armedStepCount = null
        fired = false
    }

    /**
     * Feeds one reading at motion-clock time [tNs]. Null or implausible
     * pressure never fires. Returns the [LevelEvent] exactly once, on the
     * first sample at which the crossing has held for [HOLD_NS].
     */
    fun onSample(pressureHPa: Double?, stepCount: Int, tNs: Long): LevelEvent? {
        if (fired || pressureHPa == null || !isPlausible(pressureHPa)) return null
        if (baselineCount < BASELINE_SAMPLES) {
            baselineBuffer[baselineCount++] = pressureHPa
            if (baselineCount == BASELINE_SAMPLES) baselineHPa = median(baselineBuffer)
            return null
        }
        val dh = hPaToM(pressureHPa, baselineHPa)
        if (armedStepCount == null && abs(dh) >= ARM_M) armedStepCount = stepCount
        if (abs(dh) < thresholdM) {
            crossingSinceNs = null
            return null
        }
        val since = crossingSinceNs ?: tNs.also { crossingSinceNs = it }
        if (tNs - since < HOLD_NS) return null
        fired = true
        val stepsDuringChange = stepCount - (armedStepCount ?: stepCount)
        return LevelEvent(dh, if (stepsDuringChange > 0) Mode.STAIRS else Mode.LIFT)
    }

    private fun isPlausible(hPa: Double): Boolean =
        hPa.isFinite() && hPa in MIN_PLAUSIBLE_HPA..MAX_PLAUSIBLE_HPA

    /** Median of [values] (odd length); sorts a copy, off the hot path (once per leg). */
    private fun median(values: DoubleArray): Double = values.sortedArray()[values.size / 2]

    companion object {
        /** |Δh| that confirms a level change, metres (plan W12-08). */
        const val LEVEL_CROSSING_M = 2.2

        /** |Δh| where the stairs/lift step window opens, metres. */
        const val ARM_M = 0.5

        /** The crossing must hold this long before it fires: 1.5 s. */
        const val HOLD_NS = 1_500_000_000L

        /** Leg-start readings folded into the baseline median (odd). */
        const val BASELINE_SAMPLES = 5

        /** Plausible barometer range, hPa (Everest summit to the Dead Sea with margin). */
        const val MIN_PLAUSIBLE_HPA = 300.0
        const val MAX_PLAUSIBLE_HPA = 1100.0
    }
}
