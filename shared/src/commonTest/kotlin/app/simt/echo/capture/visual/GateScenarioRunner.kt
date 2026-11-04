package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.FrameConsumer
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.ImuSample
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.capture.PdrEngine
import app.simt.echo.sim.SimHandHold
import app.simt.echo.sim.SimMotion
import app.simt.echo.sim.SimWorld
import kotlin.math.roundToLong

/** Counters observed after one end-to-end scenario. */
data class GateScenarioResult(
    val candidates: Int,
    val accepted: Int,
    val rejected: Int,
    val suspects: Int,
    val unwitnessed: Int,
    val movingWitnessed: Int,
    val framesProcessed: Long,
    val hasUsableFrame: Boolean,
    val lastReason: VisualReason,
    val lastUsability01: Double,
    val lastConfidence01: Double,
    val confidentFrames: Int,
    val unconfidentFrames: Int,
    val blurFrames: Int,
    val stillVerdicts: Int,
    val unknownVerdicts: Int,
    val movingVerdicts: Int,
    val unknownNoConfident: Int,
    val unknownTooFew: Int,
    val unknownSigma: Int,
    val unknownBand: Int,
    val unknownCameraBlind: Int,
    val usabilityRejected: Long,
    val measuredPairs: Long,
    val lastUsability: Double,
    val fits: Int,
    val maxInliers: Int,
    val maxConfidence: Double,
    val maxExpansion: Double,
    val maxFlowBlocks: Int,
    val unknownTimesSeconds: String,
    val unknownMeanSum: Double,
    val unknownSigmaSum: Double,
    val unknownConfidentSum: Int,
)

/**
 * Drives the shipping paths with a single consistent 50 Hz stream:
 * `SimHandHold` → `VisualImuHistory` → `VisualMotionConsumer` → `PdrEngine`.
 *
 * Camera frames are captured at [frameHz], rendered with pitch/yaw blur and
 * rolling shutter, and delivered [latencyMs] later. The IMU keeps running past
 * the last capture so a deferred candidate can reach its deadline.
 */
class GateScenarioRunner(
    private val world: SimWorld,
    private val handHold: SimHandHold,
    private val frameHz: Int,
    private val latencyMs: Long,
    private val seed: Long = 1L,
) {
    init {
        require(frameHz > 0)
        require(latencyMs >= 0L)
    }

    fun run(engine: PdrEngine, durationSec: Double = 12.0): GateScenarioResult {
        require(durationSec > 0.0)
        val tap = SingleConsumerTap()
        val history = VisualImuHistory()
        val intervalNs = 1_000_000_000L / frameHz
        val consumer = VisualMotionConsumer(
            tap = tap,
            input = history,
            intervalNs = intervalNs,
            onEgo = { ego, tNs, reason -> engine.onVisualEgo(ego, tNs, reason = reason) },
        )
        check(consumer.start()) { "visual consumer must attach" }
        engine.start()

        val startNs = START_NS
        val hz = 50
        val stepNs = 1_000_000_000L / hz
        val frameCount = (durationSec * frameHz).roundToLong()
        val totalTicks = ((durationSec + 0.8) * hz).roundToLong()
        val intrinsics = CameraIntrinsics(
            fPx = world.phone.focalLengthPx,
            cx = SimWorld.LUMA_WIDTH / 2.0,
            cy = SimWorld.LUMA_HEIGHT / 2.0,
            source = IntrinsicsSource.CHARACTERISTICS,
        )
        var nextFrame = 0L
        var lastTrace: VisualMotionFrame? = null
        var fits = 0
        var maxInliers = 0
        var maxConfidence = 0.0
        var maxExpansion = Double.NEGATIVE_INFINITY
        var maxFlowBlocks = 0
        val pending = ArrayDeque<Pair<Long, FrameSample>>()
        for (tick in 0..totalTicks) {
            val tNs = startNs + tick * stepNs
            while (nextFrame < frameCount && startNs + nextFrame * intervalNs <= tNs) {
                val captureNs = startNs + nextFrame * intervalNs
                val captureSec = (captureNs - startNs) / 1_000_000_000.0
                val pose = handHold.poseAt(captureSec)
                val (yawRate, pitchRate) = handHold.angularRatesAt(captureSec)
                val rendered = world.render(pose, captureNs, yawRate, pitchRate)
                val sample = FrameSample(
                    tNs = rendered.tNs,
                    width = rendered.width,
                    height = rendered.height,
                    decimation = rendered.decimation,
                    luma = rendered.luma,
                    intrinsics = intrinsics,
                    visualClockAligned = true,
                )
                pending.addLast((captureNs + latencyMs * 1_000_000L) to sample)
                nextFrame++
            }
            while (pending.isNotEmpty() && pending.first().first <= tNs) {
                val delivered = pending.removeFirst().second
                tap.deliver(delivered)
                maxFlowBlocks = maxOf(maxFlowBlocks, consumer.lastFlowBlocks)
                consumer.drain(delivered.tNs)?.let { trace ->
                    lastTrace = trace
                    fits++
                    maxInliers = maxOf(maxInliers, trace.ego.inliers)
                    maxConfidence = maxOf(maxConfidence, trace.ego.confidence01)
                    maxExpansion = maxOf(maxExpansion, trace.ego.expansionPerSec)
                }
            }
            val tSec = (tNs - startNs) / 1_000_000_000.0
            history.onRotation(tNs, handHold.orientationMatrixAt(tSec))
            val acceleration = handHold.accelerationMagnitudeAt(tSec)
            val gravity = handHold.gravityUnitAt(tSec)
            history.onAccelerometer(
                tNs,
                acceleration * gravity[0],
                acceleration * gravity[1],
                acceleration * gravity[2],
            )
            engine.onAccelerometer(
                ImuSample(
                    tNs = tNs,
                    ax = acceleration * gravity[0],
                    ay = acceleration * gravity[1],
                    az = acceleration * gravity[2],
                ),
            )
        }
        consumer.stop()
        val gate = engine.stepGate
        return GateScenarioResult(
            candidates = gate.acceptedStepCount + gate.rejectedPhantomCount,
            accepted = gate.acceptedStepCount,
            rejected = gate.rejectedPhantomCount,
            suspects = gate.missedStepSuspectCount,
            unwitnessed = gate.unwitnessedCount,
            movingWitnessed = gate.movingWitnessCount,
            framesProcessed = consumer.framesProcessed,
            hasUsableFrame = consumer.hasUsableFrame,
            lastReason = lastTrace?.reason ?: VisualReason.UNKNOWN,
            lastUsability01 = lastTrace?.usability01 ?: 0.0,
            lastConfidence01 = lastTrace?.ego?.confidence01 ?: 0.0,
            confidentFrames = gate.confidentFrameCount,
            unconfidentFrames = gate.unconfidentFrameCount,
            blurFrames = gate.reasonCount(VisualReason.BLUR),
            stillVerdicts = gate.stillVerdictCount,
            unknownVerdicts = gate.unknownVerdictCount,
            movingVerdicts = gate.movingVerdictCount,
            unknownNoConfident = gate.unknownNoConfidentCount,
            unknownTooFew = gate.unknownTooFewCount,
            unknownSigma = gate.unknownSigmaCount,
            unknownBand = gate.unknownBandCount,
            unknownCameraBlind = gate.unknownCameraBlindCount,
            usabilityRejected = consumer.usabilityRejectedCount,
            measuredPairs = consumer.measuredPairCount,
            lastUsability = consumer.lastUsability01,
            fits = fits,
            maxInliers = maxInliers,
            maxConfidence = maxConfidence,
            maxExpansion = if (maxExpansion.isFinite()) maxExpansion else 0.0,
            maxFlowBlocks = maxFlowBlocks,
            unknownTimesSeconds = gate.unknownTimestampsNs().joinToString(",") {
                ((it - startNs) / 1_000_000_000.0).toString()
            },
            unknownMeanSum = gate.unknownMeanSum,
            unknownSigmaSum = gate.unknownSigmaSum,
            unknownConfidentSum = gate.unknownConfidentSum,
        )
    }

    /** The producer side of a one-consumer tap; no governor is involved. */
    private class SingleConsumerTap : FrameTap {
        private var consumer: FrameConsumer? = null

        override fun setConsumer(consumer: FrameConsumer?) {
            this.consumer = consumer
        }

        override fun addConsumer(consumer: FrameConsumer): Boolean {
            if (this.consumer != null) return false
            this.consumer = consumer
            return true
        }

        override fun removeConsumer(consumer: FrameConsumer): Boolean {
            if (this.consumer !== consumer) return false
            this.consumer = null
            return true
        }

        fun deliver(sample: FrameSample) {
            consumer?.let {
                if (it.wantsFrame(sample.tNs)) it.onFrame(sample)
            }
        }
    }

    companion object {
        private const val START_NS = 1_000_000_000L
    }
}

/** Convenience factory for the three acceptance environments. */
fun gateWorld(environment: app.simt.echo.sim.SimEnvironment, seed: Long = 1_234_567L): SimWorld =
    SimWorld(
        environment,
        phone = app.simt.echo.sim.SimPhone(horizontalFovDeg = 90.0, exposureSeconds = 0.001),
        seed = seed,
        pixelSamplesPerAxis = 2,
        temporalNoiseDn = 2.0,
        readoutSeconds = 0.004,
    )

/** The review's stationary and walking motions at a selected frame rate. */
fun gateScenario(
    environment: app.simt.echo.sim.SimEnvironment,
    motion: SimMotion,
    frameHz: Int,
    speedMps: Double = 1.2,
): GateScenarioResult {
    val world = gateWorld(environment)
    val hand = SimHandHold(motion, speedMps)
    val engine = PdrEngine(visualAssist = true)
    return try {
        GateScenarioRunner(world, hand, frameHz, latencyMs = 120L).run(
            engine,
            durationSec = if (motion == SimMotion.STILL_IN_HAND) 60.0 else 12.0,
        )
    } finally {
        engine.stop()
    }
}
