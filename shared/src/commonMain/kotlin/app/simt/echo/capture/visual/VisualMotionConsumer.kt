package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.FrameConsumer
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTap
import app.simt.echo.model.ViewStrip
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
    /**
     * Handed every analysed frame's result on the analysis thread, at frame
     * time: an accepted ego with its reason, or a zero-confidence ego carrying
     * the rejection reason. A rejected frame is evidence that the camera could
     * not measure, not silence.
     *
     * The step gate judges a candidate on the IMU thread at that instant, so it
     * must already hold a frame from inside its ±1.2 s window. Draining only
     * after a step was emitted (the old path) left the window one step stale,
     * which is why every phantom step passed. Navigation readers still take
     * their copy from [ring] through [drain].
     */
    private val onEgo: ((VisualEgo, Long, VisualReason) -> Unit)? = null,
) : FrameConsumer {
    init { require(intervalNs > 0L) }

    private val flow = VisualFlow(width, height)
    private val fit = EgoMotionFit()
    private val usability = VisualUsability()
    private val pose = VisualFramePose()
    private val unavailable = VisualEgo()
    private val drained = VisualMotionFrame()
    private val stripLevel = LumaView(ParallaxProfiler.LEVEL_WIDTH, ParallaxProfiler.LEVEL_HEIGHT)
    private val stripCols = DoubleArray(ViewStrip.COLUMNS)
    private val stripRows = DoubleArray(ViewStrip.ROWS)
    private val stripBytes = ByteArray(ViewStrip.BYTES)
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
    @Volatile var lastUsableFrameNs = Long.MIN_VALUE
        private set
    @Volatile var usabilityRejectedCount = 0L
        private set
    @Volatile var measuredPairCount = 0L
        private set
    @Volatile var lastUsability01 = 0.0
        private set
    @Volatile var lastFlowBlocks = 0
        private set

    fun start(): Boolean {
        if (!enabled) return false
        if (running) return true
        if (!tap.addConsumer(this)) return false
        running = true
        lastUsableFrameNs = Long.MIN_VALUE
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
        lastUsability01 = usable
        if (usable < 0.5) {
            usabilityRejectedCount++
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
        lastFlowBlocks = flow.flow.count
        if (measured) measuredPairCount++
        previousW = pose.qw; previousX = pose.qx; previousY = pose.qy; previousZ = pose.qz
        hasPose = true
        val ego = if (measured) fit.fit(flow.flow, usable) else unavailable
        if (ego.confidence01 >= 0.5) {
            hasUsableFrame = true
            lastUsableFrameNs = sample.tNs
        }
        ring.publish(sample.tNs, ego, usable, usability.reason, viewStrip = extractViewStrip(sample.luma))
        onEgo?.invoke(ego, sample.tNs, usability.reason)
    }

    /** Reuses the 120x90 level and scratch; must stay allocation-free per frame. */
    private fun extractViewStrip(luma: ByteArray): ByteArray? {
        if (width != ParallaxProfiler.LEVEL_WIDTH * 2 || height != ParallaxProfiler.LEVEL_HEIGHT * 2) return null
        if (luma.size != width * height) return null
        stripLevel.reduceFrom(luma, width)
        return ParallaxProfiler.extractViewStrip120x90(stripLevel.data, stripCols, stripRows, stripBytes)
    }

    private fun reject(tNs: Long, reason: VisualReason, usable: Double = 0.0) {
        // A blurred frame is a dropout, not a scene change: keep the last good
        // frame so the next sharp frame can still form a pair. A camera that
        // cannot see (flat/pocket/dark/glare) does reset the flow.
        if (reason != VisualReason.BLUR) {
            flow.reset()
            hasPose = false
        }
        ring.publish(tNs, unavailable, usable, reason)
        onEgo?.invoke(unavailable, tNs, reason)
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

    /** A confident frame within [windowNs] of [nowNs]; never a session latch. */
    fun isLockedAt(nowNs: Long, windowNs: Long = LOCK_WINDOW_NS): Boolean {
        val last = lastUsableFrameNs
        return last != Long.MIN_VALUE && nowNs >= last && nowNs - last <= windowNs
    }

    companion object {
        private const val MAX_AGE_NS = 2_000_000_000L
        const val LOCK_WINDOW_NS = 2_000_000_000L
    }
}
