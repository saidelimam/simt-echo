package app.simt.echo.capture

import app.simt.echo.capture.visual.VisualEgo
import app.simt.echo.capture.visual.VisualEgoInbox
import app.simt.echo.capture.visual.VisualReason
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.Volatile
import kotlin.math.pow
import kotlin.math.sqrt

/** Raw accelerometer sample, m/s² per axis. */
data class ImuSample(val tNs: Long, val ax: Double, val ay: Double, val az: Double)

/** Raw gyroscope sample, rad/s per device axis (W16-03). */
data class GyroSample(val tNs: Long, val wx: Double, val wy: Double, val wz: Double)

/** Rotation-vector sample: timestamp + 9-element row-major matrix. */
data class RotSample(val tNs: Long, val r: FloatArray)

/** Barometer sample, hPa. */
data class PressureSample(val tNs: Long, val hPa: Double)

/**
 * Built-in pedestrian dead reckoning (implementation plan §6.2).
 *
 * This is ECHO's shipping `MotionSource` (the W0-01 decision). The
 * step/heading approach (per-step emissions, Weinberg step length,
 * scalar distance smoothing) follows the problem as already solved in
 * `simt-app`'s track module — copied conventions, vendored here as
 * ECHO-owned code with no dependency on that repo. Thresholds are spec
 * constants; calibration (step K) lands in W4-02 and only touches [stepK].
 *
 * The engine is pure: feed samples in, read [updates] out. Android
 * supplies samples from `SensorManager` (see `AndroidImuSource`).
 */
class PdrEngine(
    /** Weinberg step-length constant, calibratable (W4-02). */
    k: Double = 0.48,
    /** When true (W16-03), applies [HeadingQuality.correctedYaw] and FOE travel offset. */
    @Volatile var visualAssist: Boolean = false,
) : MotionSource, StepCalibratable {
    @Volatile
    override var stepK: Double = k

    private val _updates = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 128)
    override val updates: Flow<MotionUpdate> = _updates.asSharedFlow()

    @Volatile
    private var running = false

    // Step detection state (§6.2 steps 1–2).
    private var gravity = 9.81
    private var signal = 0.0
    private var prevSignal = 0.0
    private var amax = 0.0
    private var amin = 0.0
    private var lastStepNs: Long? = null

    // Heading state (§6.1).
    private var yaw0: Double? = null
    private var yawRad = 0.0
    private var cameraAxis = false

    // Integral state (§6.2 step 4).
    private var stepCount = 0
    private var distanceM = 0.0

    // Clock sanity (§6.2 step 5): correct a bogus first timestamp once.
    private var clockOffsetNs: Long? = null
    private var sawFirstEvent = false

    // Latest barometer reading; null without a sensor.
    private var pressureHpa: Double? = null

    // Latest magnetometer reduction (W12-04); null without the sensor or
    // before the first game-rotation matrix aligns the vertical.
    private var lastRotMatrix: FloatArray? = null
    private var magMag: Double? = null
    private var magVert: Double? = null

    /**
     * Heading quality estimator (W12-01). Fed additively from the same
     * samples; step detection and yaw above are untouched. W12-02 reads
     * [HeadingQuality.quality], [HeadingQuality.driftRateRadPerSec] and
     * [HeadingQuality.correctedYaw] from here.
     */
    val headingQuality = HeadingQuality()

    /** Visual step gate (W16-04): rejects shake-in-place phantom steps and flags slow shuffles. */
    val stepGate = VisualStepGate()

    private class PendingStep {
        var tNs = 0L
        var lengthM = 0.0
        var priorLastStepNs = 0L
        var hadPrior = false
    }

    private val pendingSteps = Array(PENDING_CAPACITY) { PendingStep() }
    private var pendingHead = 0
    private var pendingCount = 0

    private val visualInbox = VisualEgoInbox(VISUAL_INBOX_CAPACITY)
    private val visualSample = VisualEgoInbox.Sample()
    private var lastEmittedNs = Long.MIN_VALUE

    override fun start() {
        gravity = 9.81
        signal = 0.0
        prevSignal = 0.0
        amax = 0.0
        amin = 0.0
        lastStepNs = null
        yaw0 = null
        yawRad = 0.0
        cameraAxis = false
        stepCount = 0
        distanceM = 0.0
        clockOffsetNs = null
        sawFirstEvent = false
        pressureHpa = null
        lastRotMatrix = null
        magMag = null
        magVert = null
        headingQuality.reset()
        stepGate.reset()
        pendingHead = 0
        pendingCount = 0
        visualInbox.reset()
        lastEmittedNs = Long.MIN_VALUE
        running = true
    }

    override fun stop() {
        running = false
    }

    /** Applies §6.2 step 5 timestamp correction to one sample clock. */
    private fun correctNs(tNs: Long, wallNowNs: Long): Long {
        if (!sawFirstEvent) {
            sawFirstEvent = true
            if (kotlin.math.abs(tNs - wallNowNs) > 1_000_000_000L) {
                clockOffsetNs = wallNowNs - tNs
            }
        }
        return tNs + (clockOffsetNs ?: 0L)
    }

    fun onRotation(sample: RotSample, wallNowNs: Long = sample.tNs) {
        if (!running) return
        val tNs = correctNs(sample.tNs, wallNowNs)
        drainVisualInbox()
        resolvePending(tNs)
        lastRotMatrix = sample.r.copyOf()
        val (heading, camera) = headingRad(sample.r, cameraAxis)
        val axisSwitched = camera != cameraAxis
        cameraAxis = camera
        val origin = yaw0 ?: heading.also { yaw0 = it }
        val rawYaw = wrapPi(heading - origin)
        headingQuality.onYaw(rawYaw, tNs, axisSwitched)
        yawRad = if (visualAssist) headingQuality.correctedYaw(rawYaw) else rawYaw
        emit(snapshot(tNs, isStep = false))
    }

    /** Device-frame gyroscope feed (W16-03); projected onto world-up once rotation is known. */
    fun onGyro(sample: GyroSample, wallNowNs: Long = sample.tNs) {
        if (!running) return
        val r = lastRotMatrix ?: return
        val tNs = correctNs(sample.tNs, wallNowNs)
        headingQuality.onGyro(gyroYawRateRadPerSec(sample.wx, sample.wy, sample.wz, r), tNs)
    }

    /** Direct world-up clockwise-positive gyroscope yaw rate feed (rad/s, W16-03). */
    fun onGyroYawRate(yawRateRadPerSec: Double, tNs: Long, wallNowNs: Long = tNs) {
        if (!running) return
        headingQuality.onGyro(yawRateRadPerSec, correctNs(tNs, wallNowNs))
    }

    /**
     * Visual ego-motion feed (W16-03, W16-04); active only when
     * [visualAssist] is enabled. Rejected frames arrive with confidence 0 and
     * their [VisualReason], so absence of evidence is distinguishable from a
     * camera that cannot see. This call only copies the result into the
     * single-writer inbox; the IMU thread applies it.
     */
    fun onVisualEgo(
        ego: VisualEgo,
        tNs: Long,
        wallNowNs: Long = tNs,
        reason: VisualReason = VisualReason.NONE,
    ) {
        if (!running || !visualAssist) return
        visualInbox.publish(tNs, wallNowNs, ego, reason)
    }

    fun onPressure(sample: PressureSample) {
        if (!running) return
        pressureHpa = sample.hPa
    }

    /**
     * Magnetometer feed (W12-04, W16-03): `TYPE_MAGNETIC_FIELD` at GAME rate.
     * Reduced immediately to |B| and the gravity-aligned vertical via the
     * latest game-rotation matrix — raw axes never persist. Samples before
     * the first rotation matrix are dropped (no honest vertical yet).
     */
    fun onMagnetic(sample: MagSample) {
        if (!running) return
        val r = lastRotMatrix ?: return
        val tNs = correctNs(sample.tNs, sample.tNs)
        val mag = magneticMagnitude(sample.bx, sample.by, sample.bz)
        val vert = magneticVertical(sample.bx, sample.by, sample.bz, r)
        magMag = mag
        magVert = vert
        headingQuality.onMagnetic(mag, vert, tNs)
    }

    fun onAccelerometer(sample: ImuSample, wallNowNs: Long = sample.tNs) {
        if (!running) return
        val tNs = correctNs(sample.tNs, wallNowNs)
        drainVisualInbox()
        resolvePending(tNs)
        val magnitude = sqrt(sample.ax * sample.ax + sample.ay * sample.ay + sample.az * sample.az)
        headingQuality.onAccelerometer(magnitude / GRAVITY, tNs)
        gravity += 0.02 * (magnitude - gravity)
        prevSignal = signal
        signal += 0.25 * ((magnitude - gravity) - signal)
        if (signal > amax) amax = signal
        if (signal < amin) amin = signal

        val crossedDown = prevSignal > 0.0 && signal <= 0.0
        if (!crossedDown || amax <= 0.9) return

        val last = lastStepNs
        val dtMs = if (last == null) null else (tNs - last) / 1_000_000L
        when {
            dtMs == null -> acceptStep(tNs)
            dtMs > 2_000L -> {
                // Stationary gap: reset the window, do not count a step.
                amin = signal
                amax = signal
                lastStepNs = tNs
            }
            dtMs >= 280L -> acceptStep(tNs)
            else -> Unit // too soon after the previous step: shake, ignore
        }
    }

    private fun acceptStep(tNs: Long) {
        val range = (amax - amin).coerceAtLeast(0.0)
        val length = (stepK * range.pow(0.25)).coerceIn(0.35, 0.95)
        val prior = lastStepNs
        val priorExists = prior != null
        lastStepNs = tNs
        amax = signal
        amin = signal
        if (!visualAssist) {
            commitStep(tNs, length)
            return
        }
        if (pendingCount == PENDING_CAPACITY) resolvePending(tNs, force = true)
        val index = (pendingHead + pendingCount) % PENDING_CAPACITY
        val pending = pendingSteps[index]
        pending.tNs = tNs
        pending.lengthM = length
        pending.priorLastStepNs = prior ?: tNs
        pending.hadPrior = priorExists
        pendingCount++
    }

    private fun commitStep(tNs: Long, lengthM: Double) {
        headingQuality.onStep(tNs)
        stepCount += 1
        distanceM += lengthM
        emit(snapshot(tNs, isStep = true))
    }

    /** Applies queued visual samples on the IMU thread, never on analysis. */
    private fun drainVisualInbox() {
        if (!visualAssist) return
        while (visualInbox.poll(visualSample)) {
            val frameNs = correctNs(visualSample.tNs, visualSample.wallNowNs)
            headingQuality.onVisualEgo(visualSample.ego, frameNs)
            if (stepGate.onVisualEgo(visualSample.ego, frameNs, visualSample.reason) == StepGateVerdict.MISSED_STEP_SUSPECT) {
                emit(snapshot(maxOf(lastEmittedNs, frameNs), isStep = false, missedStepSuspect = true))
            }
        }
    }

    /** Emission clock is monotonic even when a deferred step resolves late. */
    private fun emit(update: MotionUpdate) {
        val tNs = maxOf(update.tNs, lastEmittedNs)
        lastEmittedNs = tNs
        _updates.tryEmit(if (tNs == update.tNs) update else update.copy(tNs = tNs))
    }

    /**
     * Resolves queued candidates in order. A candidate is decided when a frame
     * captured at least [POST_CANDIDATE_NS] after it has arrived, or at
     * [VERDICT_DEADLINE_NS]. MOVING accepts, STILL rejects, and UNKNOWN is
     * fail-open; a late/missing witness while armed is counted.
     */
    private fun resolvePending(nowNs: Long, force: Boolean = false) {
        while (pendingCount > 0) {
            val pending = pendingSteps[pendingHead]
            val postFrame = stepGate.lastSampleTimeNs() >= pending.tNs + POST_CANDIDATE_NS
            val deadline = force || nowNs >= pending.tNs + VERDICT_DEADLINE_NS
            if (!postFrame && !deadline) return
            val verdict = stepGate.evaluateStepCandidate(pending.tNs)
            val evidence = stepGate.lastEvidence
            val cameraBlind = stepGate.cameraCannotSeeAt(nowNs) || stepGate.cameraCannotSeeAt(pending.tNs)
            if (verdict == StepGateVerdict.ACCEPTED) {
                if (evidence == VisualMotionEvidence.UNKNOWN && !cameraBlind) {
                    stepGate.onUnwitnessed()
                }
                commitStep(pending.tNs, pending.lengthM)
            } else if (pendingCount == 1) {
                lastStepNs = if (pending.hadPrior) pending.priorLastStepNs else null
            }
            pendingHead = (pendingHead + 1) % PENDING_CAPACITY
            pendingCount--
        }
    }

    private fun snapshot(
        tNs: Long,
        isStep: Boolean,
        missedStepSuspect: Boolean = false,
    ) = MotionUpdate(
        tNs = tNs,
        distanceM = distanceM,
        yawRad = yawRad,
        stepCount = stepCount,
        isStep = isStep,
        pressureHpa = pressureHpa,
        source = SOURCE,
        headingQuality = headingQuality.quality,
        magMag = magMag,
        magVert = magVert,
        travelOffsetRad = if (visualAssist) headingQuality.travelOffsetRad else 0.0,
        missedStepSuspect = missedStepSuspect,
    )

    private companion object {
        const val SOURCE = "echo_builtin_pdr"
        const val GRAVITY = 9.81
        const val PENDING_CAPACITY = 4
        const val POST_CANDIDATE_NS = 150_000_000L
        const val VERDICT_DEADLINE_NS = 600_000_000L
        const val VISUAL_INBOX_CAPACITY = 8
    }
}
