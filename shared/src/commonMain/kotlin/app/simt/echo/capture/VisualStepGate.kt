package app.simt.echo.capture

import app.simt.echo.capture.visual.VisualEgo
import app.simt.echo.capture.visual.VisualReason
import kotlin.math.abs
import kotlin.math.sqrt

/** Step-gate verdict for a candidate or visual motion sample (plan W16-04). */
enum class StepGateVerdict {
    ACCEPTED,
    REJECTED_PHANTOM,
    MISSED_STEP_SUSPECT,
}

/** What the visual evidence in the candidate window actually says. */
enum class VisualMotionEvidence {
    MOVING,
    STILL,
    UNKNOWN,
}

/**
 * Visual step gate (plan W16-04).
 *
 * A candidate is judged on the visual evidence in `[t - 0.4 s, t + 0.4 s]`,
 * never on one sample and never on an unsigned magnitude. The window estimate
 * is the confidence-weighted mean signed expansion; its standard error comes
 * from [VisualEgo.expansionSigmaPerSec]. One extreme confident sample is
 * trimmed when its deviation from the window median is implausible, so a
 * single noisy frame cannot turn a still window into motion.
 *
 * States:
 * - `MOVING` when `mean >= max(MOVING_FLOOR, 3 * sigmaMean)` and at least two
 *   samples support it;
 * - `STILL` when every window sample is confident, `|mean| < STILL_CEILING`
 *   and `sigmaMean <= STILL_SIGMA_MAX`;
 * - `UNKNOWN` otherwise.
 *
 * Only `STILL` rejects a candidate. `UNKNOWN` is fail-open here; R15-04 adds
 * the written policy for late or missing evidence. Contraction (negative
 * expansion) is never `MOVING`: backward walking is out of scope.
 *
 * Pure and allocation-free on the hot path (fixed primitive ring and scratch).
 */
class VisualStepGate {

    private val timesNs = LongArray(CAPACITY) { Long.MIN_VALUE }
    private val expansions = DoubleArray(CAPACITY)
    private val sigmas = DoubleArray(CAPACITY)
    private val confidences = DoubleArray(CAPACITY)
    private val reasons = IntArray(CAPACITY) { VisualReason.UNKNOWN.ordinal }
    private var cursor = 0
    private var count = 0

    private val windowExpansions = DoubleArray(CAPACITY)
    private val windowSigmas = DoubleArray(CAPACITY)
    private val windowWeights = DoubleArray(CAPACITY)
    private val orderedExpansions = DoubleArray(CAPACITY)

    private var lastAcceptedStepNs: Long = Long.MIN_VALUE
    private var lastSuspectNs: Long = Long.MIN_VALUE
    private var consecutiveMovingFrames: Int = 0
    private var lastConfidentNs: Long = Long.MIN_VALUE
    private var lastCameraUnavailableNs: Long = Long.MIN_VALUE
    private var lastSampleNs: Long = Long.MIN_VALUE

    /** Total accelerometer step candidates rejected as phantom steps. */
    var rejectedPhantomCount: Int = 0
        private set

    /** Total accelerometer step candidates accepted. */
    var acceptedStepCount: Int = 0
        private set

    /** Accepted candidates that had no usable visual evidence while armed. */
    var unwitnessedCount: Int = 0
        private set

    /** Candidates whose window was explicitly MOVING. */
    var movingWitnessCount: Int = 0
        private set

    /** Total slow-shuffle missed-step suspects flagged. */
    var missedStepSuspectCount: Int = 0
        private set

    /** Most recent candidate/window classification, for tests and telemetry. */
    var lastEvidence: VisualMotionEvidence = VisualMotionEvidence.UNKNOWN
        private set

    /** Timestamps (ns) of flagged `MISSED_STEP_SUSPECT` events (bounded diagnostic log). */
    private val suspectLog = LongArray(MAX_SUSPECT_LOG)
    private var suspectLogCount = 0

    fun suspectTimestampsNs(): LongArray = suspectLog.copyOf(suspectLogCount)

    /** Records one visual ego-motion observation at [tNs]. */
    fun onVisualEgo(ego: VisualEgo, tNs: Long, reason: VisualReason = VisualReason.NONE): StepGateVerdict =
        onVisual(ego.expansionPerSec, ego.confidence01, tNs, ego.expansionSigmaPerSec, reason)

    /**
     * Records one primitive visual expansion observation at [tNs].
     * Returns [StepGateVerdict.MISSED_STEP_SUSPECT] when sustained visual
     * forward motion occurs during an accelerometer step drought.
     */
    fun onVisual(
        expansionPerSec: Double,
        confidence01: Double,
        tNs: Long,
        expansionSigmaPerSec: Double = 0.0,
        reason: VisualReason = VisualReason.NONE,
    ): StepGateVerdict {
        if (!expansionPerSec.isFinite() || !confidence01.isFinite() || !expansionSigmaPerSec.isFinite() && expansionSigmaPerSec != Double.POSITIVE_INFINITY) {
            return StepGateVerdict.ACCEPTED
        }
        timesNs[cursor] = tNs
        expansions[cursor] = expansionPerSec
        sigmas[cursor] = expansionSigmaPerSec
        confidences[cursor] = confidence01.coerceIn(0.0, 1.0)
        reasons[cursor] = reason.ordinal
        cursor = (cursor + 1) % CAPACITY
        if (count < CAPACITY) count++
        lastSampleNs = tNs
        if (confidence01 >= MIN_VISUAL_CONFIDENCE && reason == VisualReason.NONE) lastConfidentNs = tNs
        if (cameraCannotSee(reason)) lastCameraUnavailableNs = tNs

        val evidence = evidenceAt(tNs)
        lastEvidence = evidence
        if (evidence == VisualMotionEvidence.MOVING) {
            consecutiveMovingFrames++
        } else {
            consecutiveMovingFrames = 0
        }

        val stepDrought = lastAcceptedStepNs == Long.MIN_VALUE ||
            (tNs >= lastAcceptedStepNs && tNs - lastAcceptedStepNs >= SHUFFLE_QUIET_NS)
        val cooldownReady = lastSuspectNs == Long.MIN_VALUE ||
            (tNs >= lastSuspectNs && tNs - lastSuspectNs >= SUSPECT_COOLDOWN_NS)

        if (consecutiveMovingFrames >= MIN_SHUFFLE_FRAMES && stepDrought && cooldownReady) {
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
     * Estimates the signed expansion evidence in `[stepTNs - 0.4 s, stepTNs + 0.4 s]`.
     * The mean is confidence weighted; one outlier is trimmed only when it is
     * far outside the window median, so a single noisy frame cannot create motion.
     */
    fun evidenceAt(stepTNs: Long): VisualMotionEvidence {
        var total = 0
        var confident = 0
        for (i in 0 until count) {
            val t = timesNs[i]
            if (t == Long.MIN_VALUE || abs(stepTNs - t) > WINDOW_NS) continue
            total++
            if (confidences[i] >= MIN_VISUAL_CONFIDENCE && reasons[i] == VisualReason.NONE.ordinal) confident++
        }
        if (confident == 0) return VisualMotionEvidence.UNKNOWN

        var n = 0
        for (i in 0 until count) {
            val t = timesNs[i]
            if (t == Long.MIN_VALUE || abs(stepTNs - t) > WINDOW_NS) continue
            if (confidences[i] < MIN_VISUAL_CONFIDENCE || reasons[i] != VisualReason.NONE.ordinal) continue
            windowExpansions[n] = expansions[i]
            windowSigmas[n] = sigmas[i]
            windowWeights[n] = confidences[i]
            n++
        }
        if (n == 0) return VisualMotionEvidence.UNKNOWN

        // One bounded trim: an isolated spike is not evidence of sustained motion.
        var excluded = -1
        if (n >= 3) {
            val median = medianOfWindow(n)
            var worst = 0.0
            for (i in 0 until n) {
                val deviation = abs(windowExpansions[i] - median)
                if (deviation > worst) {
                    worst = deviation
                    excluded = i
                }
            }
            val typicalSigma = averageSigma(n)
            if (worst <= maxOf(OUTLIER_FLOOR_PER_SEC, 4.0 * typicalSigma)) excluded = -1
        }

        var weight = 0.0
        var mean = 0.0
        for (i in 0 until n) {
            if (i == excluded) continue
            weight += windowWeights[i]
            mean += windowWeights[i] * windowExpansions[i]
        }
        if (weight <= 0.0) return VisualMotionEvidence.UNKNOWN
        mean /= weight

        var weightedSigmaSquared = 0.0
        var spread = 0.0
        var used = 0
        for (i in 0 until n) {
            if (i == excluded) continue
            val w = windowWeights[i]
            weightedSigmaSquared += w * w * windowSigmas[i] * windowSigmas[i]
            spread += w * (windowExpansions[i] - mean) * (windowExpansions[i] - mean)
            used++
        }
        if (used == 0) return VisualMotionEvidence.UNKNOWN
        val propagated = sqrt(weightedSigmaSquared) / weight
        val empirical = sqrt(spread / weight / used)
        if (!propagated.isFinite() || !empirical.isFinite()) return VisualMotionEvidence.UNKNOWN
        val sigmaMean = sqrt(propagated * propagated + empirical * empirical)

        var supporting = 0
        for (i in 0 until n) {
            if (i != excluded && windowExpansions[i] >= MOVING_FLOOR_PER_SEC) supporting++
        }
        val moving = mean >= maxOf(MOVING_FLOOR_PER_SEC, 3.0 * sigmaMean) && supporting >= MIN_MOVING_SAMPLES
        if (moving) return VisualMotionEvidence.MOVING
        val still = confident == total &&
            mean in -STILL_CEILING_PER_SEC..STILL_CEILING_PER_SEC &&
            sigmaMean <= STILL_SIGMA_MAX_PER_SEC
        return if (still) VisualMotionEvidence.STILL else VisualMotionEvidence.UNKNOWN
    }

    /**
     * Evaluates an accelerometer step candidate at [stepTNs].
     * `MOVING` accepts, `STILL` rejects, and `UNKNOWN` is fail-open until the
     * deferred policy in R15-04 supplies late/missing-evidence handling.
     */
    fun evaluateStepCandidate(stepTNs: Long): StepGateVerdict {
        val evidence = evidenceAt(stepTNs)
        lastEvidence = evidence
        return when (evidence) {
            VisualMotionEvidence.STILL -> {
                rejectedPhantomCount++
                StepGateVerdict.REJECTED_PHANTOM
            }
            VisualMotionEvidence.MOVING, VisualMotionEvidence.UNKNOWN -> {
                if (evidence == VisualMotionEvidence.MOVING) movingWitnessCount++
                lastAcceptedStepNs = stepTNs
                consecutiveMovingFrames = 0
                acceptedStepCount++
                StepGateVerdict.ACCEPTED
            }
        }
    }

    /** Counts an accepted candidate with no timely witness (R15-04 policy). */
    fun onUnwitnessed() {
        unwitnessedCount++
    }

    /** True while a confident frame was observed in the last [ARMED_NS]. */
    fun isArmedAt(tNs: Long): Boolean =
        lastConfidentNs != Long.MIN_VALUE && tNs >= lastConfidentNs && tNs - lastConfidentNs <= ARMED_NS

    /** True when the latest camera evidence says the camera cannot see the scene. */
    fun cameraCannotSeeAt(tNs: Long): Boolean =
        lastCameraUnavailableNs != Long.MIN_VALUE && tNs >= lastCameraUnavailableNs &&
            tNs - lastCameraUnavailableNs <= WINDOW_NS

    /** Last observation of any kind, used by the deferred deadline policy. */
    fun lastSampleTimeNs(): Long = lastSampleNs

    fun reset() {
        timesNs.fill(Long.MIN_VALUE)
        expansions.fill(0.0)
        sigmas.fill(0.0)
        confidences.fill(0.0)
        reasons.fill(VisualReason.UNKNOWN.ordinal)
        cursor = 0
        count = 0
        lastAcceptedStepNs = Long.MIN_VALUE
        lastSuspectNs = Long.MIN_VALUE
        consecutiveMovingFrames = 0
        lastConfidentNs = Long.MIN_VALUE
        lastCameraUnavailableNs = Long.MIN_VALUE
        lastSampleNs = Long.MIN_VALUE
        rejectedPhantomCount = 0
        acceptedStepCount = 0
        unwitnessedCount = 0
        movingWitnessCount = 0
        missedStepSuspectCount = 0
        suspectLogCount = 0
        lastEvidence = VisualMotionEvidence.UNKNOWN
    }

    private fun medianOfWindow(n: Int): Double {
        for (i in 0 until n) orderedExpansions[i] = windowExpansions[i]
        for (i in 1 until n) {
            val value = orderedExpansions[i]
            var j = i - 1
            while (j >= 0 && orderedExpansions[j] > value) {
                orderedExpansions[j + 1] = orderedExpansions[j]
                j--
            }
            orderedExpansions[j + 1] = value
        }
        return if (n and 1 == 1) orderedExpansions[n / 2] else 0.5 * (orderedExpansions[n / 2 - 1] + orderedExpansions[n / 2])
    }

    private fun averageSigma(n: Int): Double {
        var sum = 0.0
        var used = 0
        for (i in 0 until n) {
            if (windowSigmas[i].isFinite()) {
                sum += windowSigmas[i]
                used++
            }
        }
        return if (used == 0) 0.0 else sum / used
    }

    private fun cameraCannotSee(reason: VisualReason): Boolean = when (reason) {
        VisualReason.FLAT, VisualReason.POCKET, VisualReason.EAR, VisualReason.SWINGING,
        VisualReason.BLANK_WALL, VisualReason.DARK, VisualReason.GLARE -> true
        else -> false
    }

    companion object {
        private const val CAPACITY = 16
        private const val MAX_SUSPECT_LOG = 128

        /** ±0.4 s coincidence window between accelerometer step and visual expansion (W16-04). */
        const val WINDOW_NS = 400_000_000L

        /** Minimum visual confidence to gate step candidates or flag missed steps. */
        const val MIN_VISUAL_CONFIDENCE = 0.5

        /** Minimum sustained signed expansion rate (1/s) indicating forward body motion. */
        const val MOVING_FLOOR_PER_SEC = 0.04

        /** Maximum signed mean expansion still treated as stationary. */
        const val STILL_CEILING_PER_SEC = 0.02

        /** Maximum standard error of that mean for a STILL verdict. */
        const val STILL_SIGMA_MAX_PER_SEC = 0.01

        /** A trimmed spike must exceed this deviation from the window median. */
        private const val OUTLIER_FLOOR_PER_SEC = 0.04

        /** At least two samples must support MOVING; one spike is not sustained motion. */
        private const val MIN_MOVING_SAMPLES = 2

        /** Accelerometer step drought before visual motion flags a missed-step suspect. */
        const val SHUFFLE_QUIET_NS = 800_000_000L

        /** Minimum cooldown between consecutive missed-step suspect flags. */
        const val SUSPECT_COOLDOWN_NS = 600_000_000L

        /** Consecutive MOVING windows required to flag a slow shuffle (R15-07). */
        const val MIN_SHUFFLE_FRAMES = 3

        /** Uncertainty inflation (m) applied per flagged `MISSED_STEP_SUSPECT` (W16-04). */
        const val MISSED_STEP_SIGMA_INFLATION_M = 0.45

        /** A confident observation arms the gate for this long. */
        const val ARMED_NS = 2_000_000_000L
    }
}
