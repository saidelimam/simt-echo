package app.simt.echo.capture

import kotlin.concurrent.Volatile

/**
 * Frame Tap seam (plan W10-03): the phone watches its own camera cheaply.
 *
 * Frames arrive on the motion clock (`tNs`, `elapsedRealtimeNanos` base, so
 * they align with IMU `event.timestamp` once [TimestampAlignment] holds),
 * each carrying a decimated luma view for sharpness/novelty scoring. The
 * full frame is JPEG-encoded lazily — only when the consumer (W11-01
 * ledger) actually keeps the frame — never for every analysed frame.
 *
 * Delivery is a synchronous sink, not a stream: the analyzer thread calls
 * [FrameConsumer.onFrame] and the sample is valid only for the duration of
 * that call. That is what makes the lazy JPEG possible (the camera image is
 * closed when analysis returns) and what makes the pooled luma buffer
 * leak-free by construction: the analyzer returns it to [LumaBufferPool]
 * itself after the consumer returns, so a consumer can neither forget nor
 * double-release it, and a slow consumer drops frames (keep-latest at the
 * camera) instead of queueing them.
 *
 * Hot-path contract: the analyzer thread never suspends and never allocates
 * per camera frame beyond the sample itself. Decimation scratch comes from
 * [LumaBufferPool] (three slots); [FrameRateGovernor] drops frames before
 * any copy; with no consumer registered the analyzer does no work at all.
 */
interface FrameTap {
    /**
     * Registers the single sink invoked on the analysis thread, or clears it
     * with null. Takes effect for the next frame; thread-safe.
     */
    fun setConsumer(consumer: FrameConsumer?)

    /** Guests never replace the recording sink. False when unsupported or full. */
    fun addConsumer(consumer: FrameConsumer): Boolean = false
    fun removeConsumer(consumer: FrameConsumer): Boolean = false

    /** 0 pauses analysis. A Record source retains its ledger's minimum rate. */
    fun setBaseInterval(intervalNs: Long) = Unit
}

/** Sink for [FrameTap] frames; runs on the analysis thread and must not block long. */
fun interface FrameConsumer {
    /** Asked before any luma copy; must be cheap and side-effect-free. */
    fun wantsFrame(tNs: Long): Boolean = true
    /**
     * Handles [sample] synchronously. The sample, its [FrameSample.luma] and
     * its [FrameSample.jpeg] are valid only until this call returns: copy
     * anything needed, never retain the sample.
     */
    fun onFrame(sample: FrameSample)
}

/** One analysed frame, valid only inside [FrameConsumer.onFrame]. */
class FrameSample(
    /** Frame time on the motion clock (already offset-corrected). */
    val tNs: Long,
    /** Full-frame dimensions in pixels. */
    val width: Int,
    val height: Int,
    /** Luma was decimated by this factor in each axis. */
    val decimation: Int,
    /**
     * Decimated Y plane, row-major, `(width / decimation) ×
     * (height / decimation)` bytes. Pooled and recycled after the consumer
     * returns: never retain it.
     */
    val luma: ByteArray,
    /**
     * Encodes the full frame as a JPEG on each call (≤ 960 px long side,
     * quality 80, upright). Only callable inside [FrameConsumer.onFrame];
     * null when the source cannot encode (tests, ledger-ignored frames).
     */
    val jpeg: (() -> ByteArray?)? = null,
    /** Intrinsics in this decimated plane's coordinates, with honest provenance. */
    val intrinsics: CameraIntrinsics? = null,
    /** False forbids visual rotation compensation, regardless of timestamp offset. */
    val visualClockAligned: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is FrameSample &&
            tNs == other.tNs &&
            width == other.width &&
            height == other.height &&
            decimation == other.decimation &&
            luma.contentEquals(other.luma) &&
            intrinsics == other.intrinsics &&
            visualClockAligned == other.visualClockAligned

    override fun hashCode(): Int {
        var result = tNs.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + decimation
        result = 31 * result + luma.contentHashCode()
        result = 31 * result + (intrinsics?.hashCode() ?: 0)
        result = 31 * result + visualClockAligned.hashCode()
        return result
    }
}

/** [FrameTap] with no frames: graphs without analysis, previews, iOS. */
object NoOpFrameTap : FrameTap {
    override fun setConsumer(consumer: FrameConsumer?) = Unit
}

/**
 * Thermal tiers driving the rate governor (plan W10-03): the base rate
 * holds while cool, halves at MODERATE, and analysis stops at SEVERE.
 * The platform maps `PowerManager.currentThermalStatus` onto this.
 */
enum class ThermalState {
    NORMAL,
    MODERATE,
    SEVERE,
}

/**
 * Pure frame-rate governor. [shouldEmit] returns true at most once per
 * [baseIntervalNs] of frame-clock time: 125 ms (8 Hz) for Record,
 * 500 ms (2 Hz) for Navigate. MODERATE doubles the interval except for the
 * declared 4 Hz visual-gate rate; SEVERE never emits. Deterministic: the
 * clock is a parameter, so tests drive it
 * exactly; the analyzer thread calls this before touching any buffer, so
 * rejected frames cost one comparison and zero allocation.
 */
class FrameRateGovernor(baseIntervalNs: Long) {
    @Volatile
    var baseIntervalNs: Long = baseIntervalNs
        private set
    private var lastEmitNs = 0L
    private var hasEmitted = false

    init {
        require(baseIntervalNs >= 0L)
    }

    /** Atomically changes duty; 0 means off, without forgetting the last emit. */
    fun setBaseInterval(intervalNs: Long) {
        require(intervalNs >= 0L)
        baseIntervalNs = intervalNs
    }

    fun shouldEmit(nowNs: Long, thermal: ThermalState = ThermalState.NORMAL): Boolean {
        val base = baseIntervalNs
        if (base == 0L || thermal == ThermalState.SEVERE) return false
        val interval = if (thermal == ThermalState.MODERATE && base != VISUAL_GATE_INTERVAL_NS) {
            base.coerceAtMost(Long.MAX_VALUE / 2) * 2
        } else {
            base
        }
        if (hasEmitted && (nowNs < lastEmitNs || nowNs - lastEmitNs < interval)) return false
        lastEmitNs = nowNs
        hasEmitted = true
        return true
    }

    /** Forgets emission history; tests only. */
    fun reset() {
        hasEmitted = false
    }

    companion object {
        /** 8 Hz governor interval for the Record graph. */
        const val RECORD_INTERVAL_NS = 125_000_000L

        /** 2 Hz governor interval for the Navigate graph. */
        const val NAVIGATE_INTERVAL_NS = 500_000_000L

        /**
         * Visual-assist evidence rate on Navigate (W16-04).
         *
         * [VisualStepGate] only rejects a phantom step when a confident sample
         * sits inside its ±1.2 s coincidence window. At the 2 Hz navigation
         * rate the newest frame can be 500 ms old when a candidate is judged,
         * still inside the window; 4 Hz keeps more margin by construction.
         * Record already runs at
         * 8 Hz ([RECORD_INTERVAL_NS]).
         */
        const val VISUAL_GATE_INTERVAL_NS = 250_000_000L
    }
}

/**
 * Fixed pool of reusable luma buffers (three slots, plan W10-03).
 * Preallocated once; [acquire] returns null when all slots are out (the
 * caller drops the frame — keep-latest semantics), never grows. Thread-safe.
 * Prefer [withBuffer], which makes release unforgettable.
 */
class LumaBufferPool(val slots: Int = POOL_SLOTS, val bufferSize: Int) {
    private val lock = PoolLock()
    private val buffers: Array<ByteArray> = Array(slots) { ByteArray(bufferSize) }
    private val inUse = BooleanArray(slots)

    /** A free buffer, or null when every slot is checked out. */
    fun acquire(): ByteArray? = lock.withLock {
        for (i in 0 until slots) {
            if (!inUse[i]) {
                inUse[i] = true
                return@withLock buffers[i]
            }
        }
        null
    }

    /** Returns a buffer from [acquire]; unknown arrays are ignored. */
    fun release(buffer: ByteArray) = lock.withLock {
        for (i in 0 until slots) {
            if (buffers[i] === buffer) {
                inUse[i] = false
                return@withLock
            }
        }
    }

    /**
     * Runs [block] on a free buffer and returns it to the pool afterwards,
     * even when [block] throws. Returns false (block not run) when every
     * slot is out: the caller drops the frame.
     */
    inline fun withBuffer(block: (ByteArray) -> Unit): Boolean {
        val buffer = acquire() ?: return false
        try {
            block(buffer)
        } finally {
            release(buffer)
        }
        return true
    }

    /** Free slots; tests and ledger-full telemetry only. */
    fun freeCount(): Int = lock.withLock { inUse.count { !it } }

    companion object {
        /** Analysis runs on three pooled buffers (plan W10-03). */
        const val POOL_SLOTS = 3
    }
}

/**
 * Camera-vs-motion clock alignment (plan W10-03).
 *
 * @param isRealtime true when `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME:
 * frames already share the IMU/motion clock and [offsetNs] is zero.
 * @param offsetNs added to every raw frame timestamp by [alignTimestamp];
 * zero for REALTIME (see [timestampAlignmentFor] for other sources).
 */
data class TimestampAlignment(val isRealtime: Boolean, val offsetNs: Long) {
    companion object {
        /** The common case: REALTIME source, no correction. */
        val REALTIME = TimestampAlignment(isRealtime = true, offsetNs = 0L)
    }
}

/**
 * Alignment for a camera whose timestamp source is REALTIME or not. Only
 * REALTIME frames share the motion clock; for any other source no honest
 * offset exists (it differs per device and drifts), so the result is null —
 * the tap is unusable and the platform binds without analysis rather than
 * pretending an offset of zero.
 */
fun timestampAlignmentFor(sourceIsRealtime: Boolean): TimestampAlignment? =
    if (sourceIsRealtime) TimestampAlignment.REALTIME else null

/** Applies [TimestampAlignment.offsetNs] to a raw frame timestamp. */
fun alignTimestamp(rawNs: Long, alignment: TimestampAlignment): Long = rawNs + alignment.offsetNs

/**
 * Mutual exclusion for [LumaBufferPool] and for state shared between the
 * analyzer thread and a consumer (see [RecordingLedger]). Android uses a [ReentrantLock][java.util.concurrent.locks.ReentrantLock],
 * iOS an `NSRecursiveLock` — same contract, platform lock.
 */
internal expect class PoolLock() {
    fun lock()
    fun unlock()
    fun <T> withLock(block: () -> T): T
}
