package app.simt.echo.platform

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.simt.echo.capture.FrameRateGovernor
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.LumaBufferPool
import app.simt.echo.capture.ThermalState
import app.simt.echo.capture.TimestampAlignment
import app.simt.echo.capture.alignTimestamp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Frame Tap platform source (plan W10-03).
 *
 * A single [ImageAnalysis.Analyzer] on a dedicated single-thread executor
 * feeds [FrameTap.frames]. Hot-path contract: no suspension, no per-frame
 * allocation. Frames are dropped before any copy when nobody subscribes
 * (the W11-01 ledger is the first subscriber), when [FrameRateGovernor]
 * holds them back, or when all three pooled buffers are checked out —
 * keep-latest semantics matching `STRATEGY_KEEP_ONLY_LATEST`.
 *
 * Full-frame JPEG is intentionally not retained here: [FrameSample.jpeg]
 * stays null until the ledger (W11-01) owns retention, encoding, and the
 * matching [FrameSample.release] discipline.
 */
class FrameTapSource(
    private val governor: FrameRateGovernor,
    private val thermalNow: () -> ThermalState = { ThermalState.NORMAL },
    private val alignmentNow: () -> TimestampAlignment = { TimestampAlignment.REALTIME },
) : FrameTap {
    private val _frames = MutableSharedFlow<FrameSample>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    override val frames: Flow<FrameSample> = _frames

    @Volatile
    private var pool: LumaBufferPool? = null

    @Volatile
    private var cachedThermal: ThermalState = ThermalState.NORMAL

    @Volatile
    private var lastThermalCheckNs: Long = 0L

    val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            // No ledger subscribed yet: zero work beyond closing the frame.
            if (_frames.subscriptionCount.value == 0) return@Analyzer
            val tNs = alignTimestamp(image.imageInfo.timestamp, alignmentNow())
            if (tNs - lastThermalCheckNs >= THERMAL_POLL_NS) {
                cachedThermal = runCatching { thermalNow() }.getOrDefault(ThermalState.NORMAL)
                lastThermalCheckNs = tNs
            }
            if (!governor.shouldEmit(tNs, cachedThermal)) return@Analyzer
            emitDecimated(image, tNs)
        } finally {
            image.close()
        }
    }

    private fun emitDecimated(image: ImageProxy, tNs: Long) {
        val plane = image.planes.firstOrNull() ?: return
        val downW = image.width / DECIMATION
        val downH = image.height / DECIMATION
        if (downW <= 0 || downH <= 0) return
        val need = downW * downH
        var current = pool
        if (current == null || current.bufferSize != need) {
            // Resolution change only: one allocation outside the hot path.
            current = LumaBufferPool(bufferSize = need)
            pool = current
        }
        val owned = current.acquire() ?: return // Pool exhausted: drop, keep-latest.
        try {
            val src = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            var dst = 0
            var y = 0
            while (y < image.height) {
                val rowBase = y * rowStride
                var x = 0
                while (x < image.width) {
                    owned[dst++] = src.get(rowBase + x * pixelStride)
                    x += DECIMATION
                }
                y += DECIMATION
            }
            val sample = FrameSample(
                tNs = tNs,
                width = image.width,
                height = image.height,
                decimation = DECIMATION,
                luma = owned,
            ) { current.release(owned) }
            if (!_frames.tryEmit(sample)) current.release(owned)
        } catch (e: Exception) {
            Log.d(TAG, "Frame Tap copy failed, frame dropped", e)
            current.release(owned)
        }
    }

    private companion object {
        const val TAG = "EchoFrameTap"

        /** Luma decimation factor per axis (plan W10-03). */
        const val DECIMATION = 8

        /** Thermal status is a binder call: re-read at most every 5 s. */
        const val THERMAL_POLL_NS = 5_000_000_000L
    }
}

/** Dedicated single-thread executor for analysis (plan W10-03). */
internal fun newAnalysisExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EchoFrameTap").apply { isDaemon = true }
    }

/**
 * Maps `PowerManager.currentThermalStatus` onto [ThermalState]: base rate
 * while cool, halved at MODERATE, analysis off at SEVERE. Pre-Q devices
 * have no thermal API and always report NORMAL.
 */
internal fun Context.currentThermalState(): ThermalState {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.NORMAL
    val status = runCatching {
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
    }.getOrNull() ?: return ThermalState.NORMAL
    return when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
        status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
        else -> ThermalState.NORMAL
    }
}

/**
 * Verifies `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME so frame timestamps
 * share the IMU/motion clock (plan W10-03). Anything else records a
 * non-realtime alignment: the offset is calibrated against first frames by
 * the W11-01 ledger on-device (no honest static estimate exists), and every
 * frame is still corrected through [alignTimestamp], never silently raw.
 */
internal fun queryTimestampAlignment(context: Context): TimestampAlignment = runCatching {
    val manager = context.getSystemService(CameraManager::class.java) ?: return TimestampAlignment.REALTIME
    val backId = manager.cameraIdList.firstOrNull { id ->
        runCatching {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
    } ?: return TimestampAlignment.REALTIME
    val source = manager.getCameraCharacteristics(backId)
        .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
    if (source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
        TimestampAlignment.REALTIME
    } else {
        Log.w(TAG, "Camera timestamp source is not REALTIME (source=$source); offset calibrated by ledger")
        TimestampAlignment(isRealtime = false, offsetNs = 0L)
    }
}.getOrDefault(TimestampAlignment.REALTIME)

private const val TAG = "EchoFrameTap"
