package app.simt.echo.capture

import app.simt.echo.capture.visual.VisualEgo
import kotlin.math.abs

/** Step-gate verdict for a candidate or visual motion sample (plan W16-04). */
enum class StepGateVerdict {
    ACCEPTED,
    REJECTED_PHANTOM,
    MISSED_STEP_SUSPECT,
}

/**
 * Visual step gate (plan W16-04).
 *
 * Uses visual expansion rate as an independent *is-the-body-moving* witness:
 * - An accelerometer step candidate at `tNs` is accepted when visual confidence
 *   is low (< [MIN_VISUAL_CONFIDENCE]) OR visual expansion clears
 *   [MOTION_EXPANSION_FLOOR_PER_SEC] within ±[WINDOW_NS] (±0.4 s).
 * - When visual confidence is high across a quiet-step window ([SHUFFLE_QUIET_NS])
 *   and expansion clears the motion floor while the accelerometer has not fired
 *   a step, emits a [StepGateVerdict.MISSED_STEP_SUSPECT] that raises navigation
 *   σ without inventing distance or step count.
 *
 * Pure and allocation-free on the hot path (fixed-capacity primitive ring).
 */
class VisualStepGate {

    private val timesNs = LongArray(CAPACITY) { Long.MIN_VALUE }
    private val expansions = DoubleArray(CAPACITY)
    private val confidences = DoubleArray(CAPACITY)
    private var cursor = 0
    private var count = 0

    private var lastAcceptedStepNs: Long = Long.MIN_VALUE
    private var lastSuspectNs: Long = Long.MIN_VALUE
    private var consecutiveMovingFrames: Int = 0

    /** Total accelerometer step candidates rejected as phantom steps. */
    var rejectedPhantomCount: Int = 0
        private set

    /** Total accelerometer step candidates accepted. */
    var acceptedStepCount: Int = 0
        private set

    /** Total slow-shuffle missed-step suspects flagged. */
    var missedStepSuspectCount: Int = 0
        private set

    /** Timestamps (ns) of flagged `MISSED_STEP_SUSPECT` events (bounded diagnostic log). */
    private val suspectLog = LongArray(MAX_SUSPECT_LOG)
    private var suspectLogCount = 0

    fun suspectTimestampsNs(): LongArray = suspectLog.copyOf(suspectLogCount)

    /** Records one visual ego-motion observation at [tNs]. */
    fun onVisualEgo(ego: VisualEgo, tNs: Long): StepGateVerdict =
        onVisual(ego.expansionPerSec, ego.confidence01, tNs)

    /**
     * Records one primitive visual expansion observation at [tNs].
     * Returns [StepGateVerdict.MISSED_STEP_SUSPECT] when sustained visual forward
     * expansion occurs during an accelerometer step drought (slow shuffle).
     */
    fun onVisual(expansionPerSec: Double, confidence01: Double, tNs: Long): StepGateVerdict {
        if (!expansionPerSec.isFinite() || !confidence01.isFinite()) return StepGateVerdict.ACCEPTED
        timesNs[cursor] = tNs
        expansions[cursor] = expansionPerSec
        confidences[cursor] = confidence01.coerceIn(0.0, 1.0)
        cursor = (cursor + 1) % CAPACITY
        if (count < CAPACITY) count++

        val confidentMoving = confidence01 >= MIN_VISUAL_CONFIDENCE &&
            expansionPerSec >= MOTION_EXPANSION_FLOOR_PER_SEC
        if (confidentMoving) {
            consecutiveMovingFrames++
        } else {
            consecutiveMovingFrames = 0
        }

        val stepDrought = lastAcceptedStepNs == Long.MIN_VALUE ||
            (tNs >= lastAcceptedStepNs && tNs - lastAcceptedStepNs >= SHUFFLE_QUIET_NS)
        val cooldownReady = lastSuspectNs == Long.MIN_VALUE ||
            (tNs >= lastSuspectNs && tNs - lastSuspectNs >= SUSPECT_COOLDOWN_NS)

        if (confidentMoving && consecutiveMovingFrames >= MIN_SHUFFLE_FRAMES && stepDrought && cooldownReady) {
            lastSuspectNs = tNs
            missedStepSuspectCount++
            if (suspectLogCount < MAX_SUSPECT_LOG) {
                suspectLog[suspectLogCount++] = tNs
            }
            return StepGateVerdict.MISSED_STEP_SUSPECT
        }
        return StepGateVerdict.ACCEPTED
    }

    /**
     * Evaluates an accelerometer step candidate at [stepTNs].
     * Accepted when visual confidence is low in `[stepTNs - 0.4 s, stepTNs + 0.4 s]`
     * OR any confident visual observation in that ±0.4 s window has
     * `|expansionPerSec| >= MOTION_EXPANSION_FLOOR_PER_SEC`.
     */
    fun evaluateStepCandidate(stepTNs: Long): StepGateVerdict {
        var sawConfidentVisual = false
        var sawMotionAboveFloor = false
        for (i in 0 until count) {
            val t = timesNs[i]
            if (t == Long.MIN_VALUE) continue
            if (abs(stepTNs - t) <= WINDOW_NS) {
                if (confidences[i] >= MIN_VISUAL_CONFIDENCE) {
                    sawConfidentVisual = true
                    if (abs(expansions[i]) >= MOTION_EXPANSION_FLOOR_PER_SEC) {
                        sawMotionAboveFloor = true
                        break
                    }
                }
            }
        }
        return if (!sawConfidentVisual || sawMotionAboveFloor) {
            lastAcceptedStepNs = stepTNs
            consecutiveMovingFrames = 0
            acceptedStepCount++
            StepGateVerdict.ACCEPTED
        } else {
            rejectedPhantomCount++
            StepGateVerdict.REJECTED_PHANTOM
        }
    }

    fun reset() {
        timesNs.fill(Long.MIN_VALUE)
        expansions.fill(0.0)
        confidences.fill(0.0)
        cursor = 0
        count = 0
        lastAcceptedStepNs = Long.MIN_VALUE
        lastSuspectNs = Long.MIN_VALUE
        consecutiveMovingFrames = 0
        rejectedPhantomCount = 0
        acceptedStepCount = 0
        missedStepSuspectCount = 0
        suspectLogCount = 0
    }

    companion object {
        private const val CAPACITY = 16
        private const val MAX_SUSPECT_LOG = 128

        /** ±0.4 s coincidence window between accelerometer step and visual expansion (W16-04). */
        const val WINDOW_NS = 400_000_000L

        /** Minimum visual confidence to gate step candidates or flag missed steps. */
        const val MIN_VISUAL_CONFIDENCE = 0.5

        /** Minimum visual expansion rate (1/s) indicating real forward body motion. */
        const val MOTION_EXPANSION_FLOOR_PER_SEC = 0.015

        /** Accelerometer step drought before visual motion flags a missed-step suspect. */
        const val SHUFFLE_QUIET_NS = 800_000_000L

        /** Minimum cooldown between consecutive missed-step suspect flags. */
        const val SUSPECT_COOLDOWN_NS = 600_000_000L

        /** Consecutive confident moving visual frames required to flag a slow shuffle. */
        const val MIN_SHUFFLE_FRAMES = 2

        /** Uncertainty inflation (m) applied per flagged `MISSED_STEP_SUSPECT` (W16-04). */
        const val MISSED_STEP_SIGMA_INFLATION_M = 0.45
    }
}
