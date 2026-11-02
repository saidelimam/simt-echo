package app.simt.echo.capture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Frame Tap seam (plan W10-03): the phone watches its own camera cheaply.
 *
 * Frames arrive on the motion clock (`tNs`, `elapsedRealtimeNanos` base, so
 * they align with IMU `event.timestamp` once [TimestampAlignment] holds),
 * each carrying a decimated luma view for sharpness/novelty scoring. The
 * full frame is JPEG-encoded lazily — only when a consumer (W11-01 ledger)
 * actually keeps the frame — never for every analysed frame.
 *
 * Hot-path contract: the analyzer thread never suspends and never allocates
 * per camera frame. Decimation scratch comes from [LumaBufferPool] (three
 * slots); [FrameRateGovernor] drops frames before any copy; emission uses a
 * non-suspending `tryEmit` with keep-latest overflow, so a slow ledger
 * drops frames instead of back-pressuring the camera.
 */
interface FrameTap {
    /** Throttled analysed frames; empty until the platform binds analysis. */
    val frames: Flow<FrameSample>
}

/** One analysed frame. */
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
     * (height / decimation)` bytes. Pooled while a ledger holds it: call
     * [release] when done, exactly once.
     */
    val luma: ByteArray,
    /**
     * Lazy JPEG of the full frame, encoded on first call only. Null when
     * the source kept no full-frame copy (the common case for dropped or
     * ledger-ignored frames).
     */
    val jpeg: (() -> ByteArray?)? = null,
    private val releaseFn: () -> Unit = {},
) {
    /** Returns the pooled buffer; no-op for heap-owned samples. */
    fun release() = releaseFn()

    override fun equals(other: Any?): Boolean =
        other is FrameSample &&
            tNs == other.tNs &&
            width == other.width &&
            height == other.height &&
            decimation == other.decimation &&
            luma.contentEquals(other.luma)

    override fun hashCode(): Int {
        var result = tNs.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + decimation
        result = 31 * result + luma.contentHashCode()
        return result
    }
}

/** [FrameTap] with no frames: graphs without analysis, previews, iOS. */
object NoOpFrameTap : FrameTap {
    override val frames: Flow<FrameSample> = emptyFlow()
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
 * 500 ms (2 Hz) for Navigate. MODERATE doubles the interval; SEVERE never
 * emits. Deterministic: the clock is a parameter, so tests drive it
 * exactly; the analyzer thread calls this before touching any buffer, so
 * rejected frames cost one comparison and zero allocation.
 */
class FrameRateGovernor(val baseIntervalNs: Long) {
    private var lastEmitNs: Long? = null

    fun shouldEmit(nowNs: Long, thermal: ThermalState = ThermalState.NORMAL): Boolean {
        if (thermal == ThermalState.SEVERE) return false
        val interval = if (thermal == ThermalState.MODERATE) baseIntervalNs * 2 else baseIntervalNs
        val last = lastEmitNs
        if (last != null && nowNs - last < interval) return false
        lastEmitNs = nowNs
        return true
    }

    /** Forgets emission history; tests only. */
    fun reset() {
        lastEmitNs = null
    }

    companion object {
        /** 8 Hz governor interval for the Record graph. */
        const val RECORD_INTERVAL_NS = 125_000_000L

        /** 2 Hz governor interval for the Navigate graph. */
        const val NAVIGATE_INTERVAL_NS = 500_000_000L
    }
}

/**
 * Fixed pool of reusable luma buffers (three slots, plan W10-03).
 * Preallocated once; [acquire] returns null when all slots are out (the
 * caller drops the frame — keep-latest semantics), never grows. Thread-safe:
 * the analyzer thread acquires while the ledger thread releases.
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
 * @param offsetNs added to every raw frame timestamp when the source is
 * not REALTIME: recorded at bind time and applied in [alignTimestamp], so
 * the ledger never silently drifts from motion.
 */
data class TimestampAlignment(val isRealtime: Boolean, val offsetNs: Long) {
    companion object {
        /** The common case: REALTIME source, no correction. */
        val REALTIME = TimestampAlignment(isRealtime = true, offsetNs = 0L)
    }
}

/** Applies [TimestampAlignment.offsetNs] to a raw frame timestamp. */
fun alignTimestamp(rawNs: Long, alignment: TimestampAlignment): Long = rawNs + alignment.offsetNs

/**
 * Mutual exclusion for [LumaBufferPool]: the analyzer thread acquires while
 * the ledger thread releases. Android uses a [ReentrantLock][java.util.concurrent.locks.ReentrantLock],
 * iOS an `NSRecursiveLock` — same contract, platform lock.
 */
internal expect class PoolLock() {
    fun <T> withLock(block: () -> T): T
}
