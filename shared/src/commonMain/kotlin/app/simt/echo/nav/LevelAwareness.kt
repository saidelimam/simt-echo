package app.simt.echo.nav

import app.simt.echo.capture.hPaToM
import kotlin.math.abs

/**
 * Live level awareness (plan W12-08).
 *
 * Pure: feed barometer readings plus the odometer step count, get back at
 * most one [LevelEvent] per leg. Δh is measured from the leg-start
 * baseline (relative pressure, no sea-level assumption); the event fires
 * once |Δh| crosses the [thresholdM] (W12-08: 2.2 m, shared with
 * [app.simt.echo.model.EchoConfig.levelThresholdM]).
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

    /** Leg-start pressure datum; null until the first sample of the leg. */
    private var baselineHPa: Double? = null

    /** Step count where |Δh| first left the deadband; null until armed. */
    private var armedStepCount: Int? = null

    /** Latched once the crossing fires; cleared by [resetLeg]. */
    private var fired = false

    /** Starts a new leg: the next sample becomes the pressure datum. */
    fun resetLeg() {
        baselineHPa = null
        armedStepCount = null
        fired = false
    }

    /**
     * Feeds one reading. Null pressure (no sensor) never fires. Returns
     * the [LevelEvent] exactly once, on the sample that crosses.
     */
    fun onSample(pressureHPa: Double?, stepCount: Int): LevelEvent? {
        if (fired || pressureHPa == null) return null
        val base = baselineHPa ?: pressureHPa.also { baselineHPa = it }
        val dh = hPaToM(pressureHPa, base)
        if (armedStepCount == null && abs(dh) >= ARM_M) armedStepCount = stepCount
        if (abs(dh) < thresholdM) return null
        fired = true
        val stepsDuringChange = stepCount - (armedStepCount ?: stepCount)
        return LevelEvent(dh, if (stepsDuringChange > 0) Mode.STAIRS else Mode.LIFT)
    }

    companion object {
        /** |Δh| that confirms a level change, metres (plan W12-08). */
        const val LEVEL_CROSSING_M = 2.2

        /** |Δh| where the stairs/lift step window opens, metres. */
        const val ARM_M = 0.5
    }
}
