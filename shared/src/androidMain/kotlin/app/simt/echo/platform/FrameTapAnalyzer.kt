package app.simt.echo.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.simt.echo.capture.FrameConsumer
import app.simt.echo.capture.FrameRateGovernor
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.FrameTapMux
import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.CameraOptics
import app.simt.echo.capture.LumaBufferPool
import app.simt.echo.capture.ThermalState
import app.simt.echo.capture.TimestampAlignment
import app.simt.echo.capture.alignTimestamp
import app.simt.echo.capture.timestampAlignmentFor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Frame Tap platform source (plan W10-03).
 *
 * A single [ImageAnalysis.Analyzer] on a dedicated single-thread executor
 * hands frames to the registered [FrameConsumer] synchronously. Frames are
 * dropped before any copy when no consumer is registered, when
 * [FrameRateGovernor] holds them back, or when all three pooled buffers are
 * checked out — keep-latest semantics matching `STRATEGY_KEEP_ONLY_LATEST`.
 *
 * Ownership is leak-free by construction: the pooled luma buffer is lent to
 * the consumer for the duration of the call and returned in a `finally`, and
 * the camera image closes only after that, so [FrameSample.jpeg] can encode
 * the full frame (≤ 960 px, quality 80, upright) while the image is still
 * open — and only for frames the consumer chooses to keep. A consumer that
 * throws costs one dropped frame, never the analyzer thread.
 */
class FrameTapSource(
    private val governor: FrameRateGovernor,
    private val thermalNow: () -> ThermalState = { ThermalState.NORMAL },
    private val alignmentNow: () -> TimestampAlignment = { TimestampAlignment.REALTIME },
    private val recordingIntervalNs: Long = 0L,
) : FrameTap {
    private val mux = FrameTapMux()
    private var requestedIntervalNs = governor.baseIntervalNs
    private var hasPrimary = false

    @Volatile
    private var optics: CameraOptics? = null
    private var cachedIntrinsics: CameraIntrinsics? = null
    private var cachedOptics: CameraOptics? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    fun setOptics(optics: CameraOptics?) {
        this.optics = optics
    }

    /** True while a consumer is registered; analysis-only graphs bind only then. */
    val hasConsumer: Boolean
        get() = mux.hasConsumers

    @Synchronized
    override fun setConsumer(consumer: FrameConsumer?) {
        mux.setConsumer(consumer)
        hasPrimary = consumer != null
        updateDuty()
    }

    override fun addConsumer(consumer: FrameConsumer): Boolean = mux.addConsumer(consumer)
    override fun removeConsumer(consumer: FrameConsumer): Boolean = mux.removeConsumer(consumer)

    @Synchronized
    override fun setBaseInterval(intervalNs: Long) {
        require(intervalNs >= 0L)
        requestedIntervalNs = intervalNs
        updateDuty()
    }

    private fun updateDuty() {
        governor.setBaseInterval(
            if (hasPrimary && recordingIntervalNs > 0L) {
                if (requestedIntervalNs == 0L) recordingIntervalNs else minOf(requestedIntervalNs, recordingIntervalNs)
            } else requestedIntervalNs,
        )
    }

    @Volatile
    private var pool: LumaBufferPool? = null

    @Volatile
    private var cachedThermal: ThermalState = ThermalState.NORMAL

    @Volatile
    private var lastThermalCheckNs: Long = 0L

    val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            // No consumer: zero work beyond closing the frame.
            if (!mux.hasConsumers) return@Analyzer
            val alignment = alignmentNow()
            // A guessed offset is not evidence that gyro/frame clocks agree.
            if (!alignment.isRealtime) return@Analyzer
            val tNs = alignTimestamp(image.imageInfo.timestamp, alignment)
            if (!mux.wantsFrame(tNs)) return@Analyzer
            if (tNs - lastThermalCheckNs >= THERMAL_POLL_NS) {
                cachedThermal = runCatching { thermalNow() }.getOrDefault(ThermalState.NORMAL)
                lastThermalCheckNs = tNs
            }
            if (!governor.shouldEmit(tNs, cachedThermal)) return@Analyzer
            deliver(image, tNs, mux)
        } finally {
            image.close()
        }
    }

    private fun deliver(image: ImageProxy, tNs: Long, sink: FrameConsumer) {
        val plane = image.planes.firstOrNull() ?: return
        val downW = image.width / DECIMATION
        val downH = image.height / DECIMATION
        if (downW <= 0 || downH <= 0) return
        val currentOptics = optics
        if (cachedIntrinsics == null || cachedWidth != downW || cachedHeight != downH || cachedOptics !== currentOptics) {
            cachedIntrinsics = currentOptics?.forPlane(downW, downH) ?: CameraIntrinsics.defaultFor(downW, downH)
            cachedWidth = downW
            cachedHeight = downH
            cachedOptics = currentOptics
        }
        val need = downW * downH
        var current = pool
        if (current == null || current.bufferSize != need) {
            // Resolution change only: one allocation outside the hot path.
            current = LumaBufferPool(bufferSize = need)
            pool = current
        }
        try {
            // Pool exhausted: withBuffer drops the frame, keep-latest.
            current.withBuffer { luma ->
                decimateLuma(plane.buffer, plane.rowStride, plane.pixelStride, downW, downH, DECIMATION, luma)
                sink.onFrame(
                    FrameSample(
                        tNs = tNs,
                        width = image.width,
                        height = image.height,
                        decimation = DECIMATION,
                        luma = luma,
                        jpeg = { image.toLedgerJpeg() },
                        intrinsics = cachedIntrinsics,
                        visualClockAligned = true,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame Tap frame dropped", e)
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

/**
 * Copies every [decimation]-th pixel of the Y plane into [dst] as exactly
 * [downW] × [downH] row-major samples (the caller sizes both from
 * `width / decimation`, so a ragged edge is cropped, never overrun).
 */
internal fun decimateLuma(
    src: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    downW: Int,
    downH: Int,
    decimation: Int,
    dst: ByteArray,
) {
    var out = 0
    for (row in 0 until downH) {
        val rowBase = row * decimation * rowStride
        for (col in 0 until downW) {
            dst[out++] = src.get(rowBase + col * decimation * pixelStride)
        }
    }
}

/**
 * Output size for a kept-frame JPEG: the long side capped at [maxSide],
 * aspect ratio preserved, never upscaled, never below 1 px.
 */
internal fun jpegTargetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val longSide = maxOf(width, height)
    if (longSide <= maxSide) return width to height
    val scale = maxSide.toDouble() / longSide
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/**
 * Encodes this frame for the ledger: upright (rotated by the image's
 * `rotationDegrees`), long side ≤ [LEDGER_JPEG_MAX_SIDE], quality
 * [LEDGER_JPEG_QUALITY]. Must run before the image closes. A failed encode
 * degrades to a metadata-only ledger entry (null), never a crash.
 */
internal fun ImageProxy.toLedgerJpeg(): ByteArray? = try {
    val source = toBitmap()
    val (w, h) = jpegTargetSize(source.width, source.height, LEDGER_JPEG_MAX_SIDE)
    val matrix = Matrix().apply {
        postScale(w.toFloat() / source.width, h.toFloat() / source.height)
        postRotate(imageInfo.rotationDegrees.toFloat())
    }
    val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    val out = ByteArrayOutputStream()
    upright.compress(Bitmap.CompressFormat.JPEG, LEDGER_JPEG_QUALITY, out)
    if (upright !== source) upright.recycle()
    source.recycle()
    out.toByteArray()
} catch (e: Exception) {
    Log.w(TAG, "Ledger JPEG encode failed, metadata only", e)
    null
}

private const val LEDGER_JPEG_MAX_SIDE = 960
private const val LEDGER_JPEG_QUALITY = 80

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
 * share the IMU/motion clock (plan W10-03). Returns null when they cannot
 * be placed on that clock — a non-REALTIME source, or one that cannot be
 * read — so the recorder binds without analysis (`LEDGER_OFF`) instead of
 * scoring frames against the wrong odometer.
 */
internal fun queryTimestampAlignment(context: Context, cameraId: String): TimestampAlignment? = runCatching {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    val source = manager.getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
    val alignment = timestampAlignmentFor(source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
    if (alignment == null) Log.w(TAG, "Camera timestamp source is not REALTIME (source=$source); ledger off")
    alignment
}.getOrNull()

/** Selected-camera optics; unavailable metadata is a labelled default, not an error. */
internal fun queryCameraOptics(context: Context, cameraId: String): CameraOptics? = runCatching {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    val characteristics = manager.getCameraCharacteristics(cameraId)
    val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
    val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    CameraOptics(focal.toDouble(), sensor.width.toDouble(), active.width(), active.height())
}.getOrNull()

private const val TAG = "EchoFrameTap"
