package app.simt.echo.capture.visual

import app.simt.echo.capture.FrameConsumer
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTap
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.RoutePerception
import app.simt.echo.model.ViewStrip
import app.simt.echo.model.withPerception
import app.simt.echo.platform.MotionUpdate
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Parallax profile capture on Record (plan W17-01).
 *
 * Accumulates expansion-per-metre `ρ = expansionPerFrame / Δs_odometer`
 * (`expansionPerSec / v_odometer`) into 0.5 m buckets in the creator's own
 * PDR metres:
 * - Only frames clearing `confidence01 >= 0.5` and `Δs_odometer > 0`
 *   contribute to `ρ`, quantised log₂ at 1/16 octave per the W15-02 wire
 *   contract (`0` = unknown).
 * - Trackability `τ` is the mean confidence per bucket quantised to `0..255`.
 * - Every 2 m and at each route node, stores a 96-byte [ViewStrip]: 64 column
 *   values (band-mean of horizontal gradient over rows 20–70 of the 120×90
 *   level) and 32 row values (band-mean of vertical gradient), each normalised
 *   zero-mean/unit-variance and quantised to `int8`.
 */
class ParallaxProfiler(
    val estimatorId: String = DEFAULT_ESTIMATOR_ID,
    defaultIntrinsicsClass: String = DEFAULT_INTRINSICS_CLASS,
) : FrameConsumer {

    private class Bucket {
        val rhos = ArrayList<Double>(4)
        var confSum = 0.0
        var confCount = 0
    }

    private class RawStrip(val distanceM: Double, val bytes: ByteArray)

    private val lock = Any()
    private val buckets = ArrayList<Bucket>()
    private val rawStrips = ArrayList<RawStrip>()
    private val level120 = LumaView(LEVEL_WIDTH, LEVEL_HEIGHT)
    private val fullScratch = LumaView(240, 180)
    private val colSignal = DoubleArray(ViewStrip.COLUMNS)
    private val rowSignal = DoubleArray(ViewStrip.ROWS)

    @Volatile private var intrinsicsClass: String = defaultIntrinsicsClass
    @Volatile private var latestDistanceM: Double = 0.0
    @Volatile private var latestMotionNs: Long = Long.MIN_VALUE
    @Volatile private var prevStepDistanceM: Double = 0.0
    @Volatile private var prevStepTimeNs: Long = Long.MIN_VALUE
    @Volatile private var latestSpeedMps: Double = 0.0
    private var lastGridStripBucket: Int = -1

    /** Feeds one motion update during Record so frames know odometer distance and speed. */
    fun onMotion(update: MotionUpdate) {
        synchronized(lock) {
            if (!update.distanceM.isFinite() || update.distanceM < 0.0) return
            if (update.isStep) {
                if (prevStepTimeNs != Long.MIN_VALUE && update.tNs > prevStepTimeNs) {
                    val dtSec = (update.tNs - prevStepTimeNs) / 1e9
                    val ds = update.distanceM - prevStepDistanceM
                    if (dtSec in 0.05..2.5 && ds > 0.0) latestSpeedMps = ds / dtSec
                }
                prevStepDistanceM = update.distanceM
                prevStepTimeNs = update.tNs
            } else if (latestMotionNs != Long.MIN_VALUE && update.tNs > latestMotionNs) {
                val dtSec = (update.tNs - latestMotionNs) / 1e9
                val ds = update.distanceM - latestDistanceM
                if (dtSec > 0.01 && ds > 0.0) latestSpeedMps = ds / dtSec
            }
            latestDistanceM = update.distanceM
            latestMotionNs = update.tNs
        }
    }

    /** Records a visual ego sample from expansion-per-frame and odometer delta `Δs` at [distanceM]. */
    fun onFramePair(
        distanceM: Double,
        expansionPerFrame: Double,
        deltaOdometerM: Double,
        confidence01: Double,
        luma240x180: ByteArray? = null,
        intrinsicsSourceName: String? = null,
    ) {
        synchronized(lock) {
            if (!distanceM.isFinite() || distanceM < 0.0) return
            if (!intrinsicsSourceName.isNullOrBlank()) intrinsicsClass = intrinsicsSourceName
            val bucket = ensureBucket((distanceM / ParallaxProfile.STEP_M).toInt())
            val conf = if (confidence01.isFinite()) confidence01.coerceIn(0.0, 1.0) else 0.0
            bucket.confSum += conf
            bucket.confCount += 1
            if (conf >= MIN_CONFIDENCE && expansionPerFrame.isFinite() &&
                deltaOdometerM.isFinite() && deltaOdometerM > MIN_DELTA_ODOMETER_M
            ) {
                val rho = expansionPerFrame / deltaOdometerM
                if (rho.isFinite() && rho > 0.0) bucket.rhos.add(rho)
            }
            if (luma240x180 != null && luma240x180.size == 240 * 180) {
                maybeCaptureStripLocked(distanceM, luma240x180, force = false)
            }
        }
    }

    /** Records a drained [VisualEgo] at [distanceM] with creator walking speed [speedMps]. */
    fun onEgoAtSpeed(
        distanceM: Double,
        ego: VisualEgo,
        speedMps: Double = latestSpeedMps,
        intrinsicsSourceName: String? = null,
    ) {
        synchronized(lock) {
            if (!distanceM.isFinite() || distanceM < 0.0) return
            if (!intrinsicsSourceName.isNullOrBlank()) intrinsicsClass = intrinsicsSourceName
            val bucket = ensureBucket((distanceM / ParallaxProfile.STEP_M).toInt())
            val conf = if (ego.confidence01.isFinite()) ego.confidence01.coerceIn(0.0, 1.0) else 0.0
            bucket.confSum += conf
            bucket.confCount += 1
            if (conf >= MIN_CONFIDENCE && ego.expansionPerSec.isFinite() &&
                ego.expansionPerSec > 0.0 && speedMps.isFinite() && speedMps > MIN_SPEED_MPS
            ) {
                val rho = ego.expansionPerSec / speedMps
                if (rho.isFinite() && rho > 0.0) bucket.rhos.add(rho)
            }
        }
    }

    /** Captures a view strip explicitly at [distanceM] from a 240×180 luma plane. */
    fun captureStripAt(distanceM: Double, luma240x180: ByteArray) {
        synchronized(lock) {
            if (!distanceM.isFinite() || distanceM < 0.0 || luma240x180.size != 240 * 180) return
            maybeCaptureStripLocked(distanceM, luma240x180, force = true)
        }
    }

    override fun wantsFrame(tNs: Long): Boolean {
        val d = latestDistanceM
        val gridBucket = (d / STRIP_STEP_M).toInt()
        return gridBucket > lastGridStripBucket
    }

    override fun onFrame(sample: FrameSample) = synchronized(lock) {
        sample.intrinsics?.source?.name?.let { intrinsicsClass = it }
        val planeW = sample.width / sample.decimation.coerceAtLeast(1)
        val planeH = sample.height / sample.decimation.coerceAtLeast(1)
        if (planeW == 240 && planeH == 180 && sample.luma.size == 240 * 180) {
            maybeCaptureStripLocked(latestDistanceM, sample.luma, force = false)
        }
    }

    /** Builds the validated [ParallaxProfile], or null when nothing was recorded. */
    fun toParallaxProfile(
        nodeDistancesM: List<Double> = emptyList(),
        totalLengthM: Double? = null,
    ): ParallaxProfile? = synchronized(lock) {
        val targetBuckets = totalLengthM
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it / ParallaxProfile.STEP_M).roundToInt().coerceAtLeast(buckets.size) }
            ?: buckets.size
        if (targetBuckets == 0 && rawStrips.isEmpty()) return null

        val maxBuckets = targetBuckets.coerceAtMost(MAX_BUCKETS)
        val depthBytes = ByteArray(maxBuckets)
        val trackBytes = ByteArray(maxBuckets)

        for (i in 0 until maxBuckets) {
            val b = buckets.getOrNull(i)
            if (b == null || b.confCount == 0) {
                depthBytes[i] = 0
                trackBytes[i] = 0
                continue
            }
            val meanConf = (b.confSum / b.confCount).coerceIn(0.0, 1.0)
            trackBytes[i] = (meanConf * 255.0).roundToInt().coerceIn(0, 255).toByte()
            if (b.rhos.isNotEmpty()) {
                val sorted = b.rhos.sorted()
                val medianRho = if (sorted.size % 2 == 1) {
                    sorted[sorted.size / 2]
                } else {
                    0.5 * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
                }
                depthBytes[i] = quantiseRho(medianRho)
            } else {
                depthBytes[i] = 0
            }
        }

        val strips = selectStripsLocked(nodeDistancesM, maxBuckets * ParallaxProfile.STEP_M)
        return ParallaxProfile(
            parallaxVersion = ParallaxProfile.VERSION,
            stepM = ParallaxProfile.STEP_M,
            inverseDepth = Base64.encode(depthBytes),
            trackability = Base64.encode(trackBytes),
            viewStrips = strips,
            legSigmaRad = emptyList(),
            estimatorId = estimatorId,
            intrinsicsClass = intrinsicsClass.ifBlank { DEFAULT_INTRINSICS_CLASS },
        ).validate()
    }

    fun attachToTap(tap: FrameTap, enabled: Boolean): Boolean {
        if (!enabled) return false
        return tap.addConsumer(this)
    }

    fun detachFromTap(tap: FrameTap) {
        tap.removeConsumer(this)
    }

    private fun ensureBucket(index: Int): Bucket {
        while (buckets.size <= index) {
            buckets.add(Bucket())
        }
        return buckets[index]
    }

    private fun maybeCaptureStripLocked(distanceM: Double, luma240x180: ByteArray, force: Boolean) {
        val gridBucket = (distanceM / STRIP_STEP_M).toInt()
        if (!force && gridBucket <= lastGridStripBucket) return
        lastGridStripBucket = maxOf(lastGridStripBucket, gridBucket)
        luma240x180.copyInto(fullScratch.data)
        level120.reduceFrom(fullScratch.data, 240)
        val stripBytes = extractViewStrip120x90(level120.data, colSignal, rowSignal)
        val snapDistance = if (force) distanceM else gridBucket * STRIP_STEP_M
        rawStrips.add(RawStrip(snapDistance, stripBytes))
    }

    private fun selectStripsLocked(nodeDistancesM: List<Double>, maxRouteM: Double): List<ViewStrip> {
        if (rawStrips.isEmpty()) return emptyList()
        val desiredDistances = sortedSetOf<Double>()
        var d = 0.0
        val limitM = maxOf(maxRouteM, rawStrips.maxOf { it.distanceM })
        while (d <= limitM + 1e-6) {
            desiredDistances.add((d * 100.0).roundToInt() / 100.0)
            d += STRIP_STEP_M
        }
        for (nd in nodeDistancesM) {
            if (nd.isFinite() && nd >= 0.0) {
                val rounded = (nd * 100.0).roundToInt() / 100.0
                if (desiredDistances.none { abs(it - rounded) < 0.25 }) {
                    desiredDistances.add(rounded)
                }
            }
        }

        val chosen = ArrayList<ViewStrip>()
        var lastDist = -1.0
        for (target in desiredDistances) {
            val nearest = rawStrips.minByOrNull { abs(it.distanceM - target) } ?: continue
            if (abs(nearest.distanceM - target) > STRIP_STEP_M * 0.75 && rawStrips.size > 1) continue
            val emitDist = if (target > lastDist) target else nearest.distanceM
            if (emitDist <= lastDist) continue
            chosen.add(ViewStrip(atDistanceM = emitDist, dataB64 = Base64.encode(nearest.bytes)))
            lastDist = emitDist
            if (chosen.size >= MAX_STRIPS_FOR_BUDGET) break
        }
        return chosen
    }

    companion object {
        const val DEFAULT_ESTIMATOR_ID = "echo-parallax-01"
        const val DEFAULT_INTRINSICS_CLASS = "DEFAULT"
        const val MIN_CONFIDENCE = 0.5
        const val STRIP_STEP_M = 2.0
        const val LEVEL_WIDTH = 120
        const val LEVEL_HEIGHT = 90
        const val BAND_TOP = 20
        const val BAND_BOTTOM = 70
        private const val MIN_DELTA_ODOMETER_M = 0.01
        private const val MIN_SPEED_MPS = 0.1
        private const val MAX_BUCKETS = 1024
        private const val MAX_STRIPS_FOR_BUDGET = 36
        private val LN2 = ln(2.0)

        /**
         * Quantises positive `ρ` (expansion per metre) to the W15-02 signed
         * byte code (`0` = unknown).
         */
        fun quantiseRho(rho: Double): Byte {
            if (!rho.isFinite() || rho <= 0.0) return 0
            val log2Rho = ln(rho) / LN2
            if (log2Rho < -8.0 || log2Rho > 7.875) return 0
            val q = (log2Rho * 16.0).roundToInt().coerceIn(-128, 126)
            return (if (q < 0) q else q + 1).toByte()
        }

        /** Decodes a signed byte [code] from `inverseDepth` back to `ρ` (1/m), or `Double.NaN` for `0`. */
        fun dequantiseRho(code: Byte): Double {
            val c = code.toInt()
            if (c == 0) return Double.NaN
            val q = if (c < 0) c else c - 1
            return 2.0.pow(q / 16.0)
        }

        /** Extracts a 96-byte view strip from a 240×180 luma frame via the 120×90 level. */
        fun extractViewStripFrom240x180(luma240x180: ByteArray): ByteArray {
            require(luma240x180.size == 240 * 180) { "need 240x180 luma plane" }
            val reduced = LumaView(LEVEL_WIDTH, LEVEL_HEIGHT)
            reduced.reduceFrom(luma240x180, 240)
            return extractViewStrip120x90(reduced.data)
        }

        /**
         * Extracts the 96-byte view strip from a 120×90 luma level:
         * 64 column horizontal-gradient means (rows 20..70) + 32 row
         * vertical-gradient means, zero-mean/unit-variance quantised to `int8`.
         */
        fun extractViewStrip120x90(
            luma120x90: ByteArray,
            colScratch: DoubleArray = DoubleArray(ViewStrip.COLUMNS),
            rowScratch: DoubleArray = DoubleArray(ViewStrip.ROWS),
        ): ByteArray {
            require(luma120x90.size == LEVEL_WIDTH * LEVEL_HEIGHT) { "need 120x90 luma plane" }
            val bandHeight = BAND_BOTTOM - BAND_TOP
            // 64 columns spanning x in [4, 116)
            val colSpan = LEVEL_WIDTH - 8
            for (c in 0 until ViewStrip.COLUMNS) {
                val x0 = 4 + (c * colSpan) / ViewStrip.COLUMNS
                val x1 = (4 + ((c + 1) * colSpan) / ViewStrip.COLUMNS).coerceAtLeast(x0 + 1)
                var sum = 0.0
                var count = 0
                for (y in BAND_TOP until BAND_BOTTOM) {
                    val rowOff = y * LEVEL_WIDTH
                    for (x in x0 until x1) {
                        val right = luma120x90[rowOff + x + 1].toInt() and 0xFF
                        val left = luma120x90[rowOff + x - 1].toInt() and 0xFF
                        sum += (right - left)
                        count++
                    }
                }
                colScratch[c] = if (count > 0) sum / count else 0.0
            }

            // 32 rows spanning y in [BAND_TOP, BAND_BOTTOM)
            for (r in 0 until ViewStrip.ROWS) {
                val y0 = BAND_TOP + (r * bandHeight) / ViewStrip.ROWS
                val y1 = (BAND_TOP + ((r + 1) * bandHeight) / ViewStrip.ROWS).coerceAtLeast(y0 + 1)
                var sum = 0.0
                var count = 0
                for (y in y0 until y1) {
                    val prevRow = (y - 1) * LEVEL_WIDTH
                    val nextRow = (y + 1) * LEVEL_WIDTH
                    for (x in 8 until LEVEL_WIDTH - 8) {
                        val down = luma120x90[nextRow + x].toInt() and 0xFF
                        val up = luma120x90[prevRow + x].toInt() and 0xFF
                        sum += (down - up)
                        count++
                    }
                }
                rowScratch[r] = if (count > 0) sum / count else 0.0
            }

            val out = ByteArray(ViewStrip.BYTES)
            normaliseAndQuantise(colScratch, ViewStrip.COLUMNS, out, 0)
            normaliseAndQuantise(rowScratch, ViewStrip.ROWS, out, ViewStrip.COLUMNS)
            return out
        }

        private fun normaliseAndQuantise(values: DoubleArray, length: Int, out: ByteArray, outOffset: Int) {
            var mean = 0.0
            for (i in 0 until length) mean += values[i]
            mean /= length
            var variance = 0.0
            for (i in 0 until length) {
                val d = values[i] - mean
                variance += d * d
            }
            val std = sqrt(variance / length)
            if (std < 1e-6) {
                for (i in 0 until length) out[outOffset + i] = 0
                return
            }
            for (i in 0 until length) {
                val z = (values[i] - mean) / std
                out[outOffset + i] = (z * 32.0).roundToInt().coerceIn(-127, 127).toByte()
            }
        }
    }
}

/** Attaches [parallax] to [EchoRoute.perception], preserving any existing perception fields. */
fun EchoRoute.withParallax(parallax: ParallaxProfile?): EchoRoute {
    if (parallax == null) return this
    val base = perception ?: RoutePerception()
    return withPerception(base.copy(parallax = parallax))
}
