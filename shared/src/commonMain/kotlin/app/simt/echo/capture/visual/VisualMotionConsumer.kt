package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.FrameConsumer
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTap
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Frame-thread pipeline. The only cross-thread result transport is [ring]. */
class VisualMotionConsumer(
    private val tap: FrameTap,
    private val input: VisualMotionInput,
    private val enabled: Boolean = true,
    intervalNs: Long = 500_000_000L,
    private val width: Int = 240,
    private val height: Int = 180,
    val ring: VisualEgoRing = VisualEgoRing(),
) : FrameConsumer {
    init { require(intervalNs > 0L) }

    private val flow = VisualFlow(width, height)
    private val fit = EgoMotionFit()
    private val usability = VisualUsability()
    private val pose = VisualFramePose()
    private val unavailable = VisualEgo()
    private val drained = VisualMotionFrame()
    private var hasDrained = false
    private var defaultIntrinsics: CameraIntrinsics? = null
    @Volatile private var running = false
    @Volatile private var interval = intervalNs
    @Volatile private var lastFrameNs = Long.MIN_VALUE
    private var previousW = 1.0
    private var previousX = 0.0
    private var previousY = 0.0
    private var previousZ = 0.0
    private var hasPose = false
    @Volatile var framesProcessed = 0L
        private set
    @Volatile var hasUsableFrame = false
        private set

    fun start(): Boolean {
        if (!enabled) return false
        if (running) return true
        if (!tap.addConsumer(this)) return false
        running = true
        tap.setBaseInterval(interval)
        return true
    }

    fun stop() {
        running = false
        tap.removeConsumer(this)
    }

    fun setInterval(intervalNs: Long) {
        require(intervalNs >= 0L)
        interval = intervalNs
        if (running) tap.setBaseInterval(intervalNs)
    }

    override fun wantsFrame(tNs: Long): Boolean {
        if (!running || interval == 0L) return false
        val last = lastFrameNs
        return (last == Long.MIN_VALUE || (tNs >= last && tNs - last >= interval)) && input.isAvailable(tNs)
    }

    override fun onFrame(sample: FrameSample) {
        if (!wantsFrame(sample.tNs)) return
        lastFrameNs = sample.tNs
        framesProcessed++
        if (!sample.visualClockAligned || sample.decimation <= 0 || sample.width / sample.decimation != width ||
            sample.height / sample.decimation != height || sample.luma.size.toLong() != width.toLong() * height ||
            !input.readAt(sample.tNs, VisualUsability.meanLuma(sample.luma), pose)
        ) {
            reject(sample.tNs, VisualReason.UNKNOWN)
            return
        }
        val norm = pose.qw * pose.qw + pose.qx * pose.qx + pose.qy * pose.qy + pose.qz * pose.qz
        if (!norm.isFinite() || abs(norm - 1.0) > 0.001 || !pose.angularRateRadPerSec.isFinite()) {
            reject(sample.tNs, VisualReason.UNKNOWN)
            return
        }
        val usable = usability.evaluate(sample.luma, width, height, pose.mode, pose.angularRateRadPerSec)
        if (usable < 0.5) {
            reject(sample.tNs, usability.reason, usable)
            return
        }
        val intrinsics = sample.intrinsics ?: (defaultIntrinsics ?: CameraIntrinsics.defaultFor(width, height).also { defaultIntrinsics = it })
        var yaw = 0.0
        var pitch = 0.0
        var roll = 0.0
        if (hasPose) {
            val w = previousW * pose.qw + previousX * pose.qx + previousY * pose.qy + previousZ * pose.qz
            val x = previousW * pose.qx - previousX * pose.qw - previousY * pose.qz + previousZ * pose.qy
            val y = previousW * pose.qy + previousX * pose.qz - previousY * pose.qw - previousZ * pose.qx
            val z = previousW * pose.qz - previousX * pose.qy + previousY * pose.qx - previousZ * pose.qw
            val vectorNorm = sqrt(x * x + y * y + z * z)
            val scale = (if (vectorNorm > 1e-12) 2.0 * atan2(vectorNorm, abs(w)) / vectorNorm else 2.0) * if (w < 0.0) -1.0 else 1.0
            // Rear camera axes are (device x, -device y, -device z).
            pitch = x * scale
            yaw = -y * scale
            roll = -z * scale
        }
        val measured = flow.onFrame(sample.luma, sample.tNs, intrinsics, yaw, pitch, roll, clockAligned = true)
        previousW = pose.qw; previousX = pose.qx; previousY = pose.qy; previousZ = pose.qz
        hasPose = true
        val ego = if (measured) fit.fit(flow.flow, usable) else unavailable
        if (ego.confidence01 >= 0.5) hasUsableFrame = true
        ring.publish(sample.tNs, ego, usable, usability.reason)
    }

    private fun reject(tNs: Long, reason: VisualReason, usable: Double = 0.0) {
        flow.reset()
        hasPose = false
        ring.publish(tNs, unavailable, usable, reason)
    }

    /** Navigation-thread only, called at a step; future frames stay queued. */
    fun drain(throughNs: Long): VisualMotionFrame? {
        for (i in 0 until ring.capacity) {
            if (!ring.poll(drained, throughNs)) break
            hasDrained = true
        }
        if (!hasDrained || drained.tNs > throughNs || throughNs - drained.tNs > MAX_AGE_NS) return null
        return drained
    }

    companion object {
        private const val MAX_AGE_NS = 2_000_000_000L
    }
}
