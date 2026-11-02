package app.simt.echo.capture

import app.simt.echo.model.CaptureSample
import app.simt.echo.model.MagProfile
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Magnetic signature capture (plan W12-04).
 *
 * `TYPE_MAGNETIC_FIELD` is sampled at GAME rate into [PdrEngine]; each
 * sample is reduced immediately to two orientation-invariant scalars —
 * the total-field magnitude |B| (rotation-invariant by construction) and
 * the gravity-aligned vertical component (device-frame field dotted with
 * the world-up row of the game rotation vector) — so no raw axis data is
 * ever stored. Per [MagProfile.MAG_STEP_M] bucket the means accumulate;
 * zero-mean normalisation happens at match time, which is what makes the
 * signature phone-independent (hard-iron offsets cancel).
 *
 * Pure commonMain: no sensor imports. Host tests drive [onSample] with
 * synthetic distances; the Android source only forwards sensor values.
 */

/** Raw magnetometer sample, µT per device-frame axis. */
data class MagSample(val tNs: Long, val bx: Double, val by: Double, val bz: Double)

/** Total-field magnitude |B|, µT. Rotation-invariant by construction. */
fun magneticMagnitude(bx: Double, by: Double, bz: Double): Double =
    sqrt(bx * bx + by * by + bz * bz)

/**
 * Gravity-aligned vertical component, µT: the device-frame field dotted
 * with the world-up row of the device→world rotation matrix [r] (row-major
 * 9, from `SensorManager.getRotationMatrixFromVector`). Orientation-
 * invariant like |B|: turning the phone in the hand changes the axes, not
 * this projection.
 */
fun magneticVertical(bx: Double, by: Double, bz: Double, r: FloatArray): Double {
    require(r.size >= 9) { "rotation matrix needs 9 elements, got ${r.size}" }
    return r[6] * bx + r[7] * by + r[8] * bz
}

/**
 * Per-bucket mean accumulator over odometer distance. Buckets are dense
 * from 0: bucket `b` covers `[b·step, (b+1)·step)`. Gaps (no samples in a
 * bucket) stay null until [filledSeries] interpolates them.
 */
class MagProfiler {
    private val magSum = mutableListOf<Double>()
    private val vertSum = mutableListOf<Double>()
    private val counts = mutableListOf<Int>()

    /** Buckets touched so far (dense size, includes empty gaps). */
    val bucketCount: Int get() = magSum.size

    /** Total samples accumulated. */
    var sampleCount: Int = 0
        private set

    /** Feeds one reduced sample at odometer distance [distanceM]. */
    fun onSample(distanceM: Double, mag: Double, vert: Double) {
        if (!distanceM.isFinite() || !mag.isFinite() || !vert.isFinite()) return
        if (distanceM < 0.0) return
        val b = (distanceM / MagProfile.MAG_STEP_M).toInt()
        while (magSum.size <= b) {
            magSum += 0.0
            vertSum += 0.0
            counts += 0
        }
        magSum[b] += mag
        vertSum[b] += vert
        counts[b] += 1
        sampleCount += 1
    }

    /** Mean of bucket [b], or null when the bucket has no samples. */
    fun meanAt(b: Int): Pair<Double, Double>? {
        if (b < 0 || b >= magSum.size) return null
        val n = counts[b]
        if (n == 0) return null
        return (magSum[b] / n) to (vertSum[b] / n)
    }

    /**
     * Dense means over buckets `[0, bucketCount)`: internal gaps linearly
     * interpolated, edges filled with the nearest observed bucket. Empty
     * when nothing was ever sampled.
     */
    fun filledSeries(): Pair<DoubleArray, DoubleArray> {
        val n = magSum.size
        val mag = DoubleArray(n)
        val vert = DoubleArray(n)
        for (b in 0 until n) {
            val m = meanAt(b)
            if (m != null) {
                mag[b] = m.first
                vert[b] = m.second
            } else {
                mag[b] = Double.NaN
                vert[b] = Double.NaN
            }
        }
        fillGaps(mag)
        fillGaps(vert)
        return mag to vert
    }

    /**
     * Dense means for the last [lengthM] metres ending at [endDistanceM]:
     * exactly `round(lengthM / step)` buckets, or null when the window is
     * not fully observed (every bucket needs ≥ 1 sample — interpolation
     * must never fabricate most of a match window). Gaps inside a fully
     * observed window cannot occur by construction.
     */
    fun lastWindow(lengthM: Double, endDistanceM: Double): Pair<DoubleArray, DoubleArray>? {
        val step = MagProfile.MAG_STEP_M
        val m = (lengthM / step).roundToInt()
        if (m <= 0) return null
        val endBucket = (endDistanceM / step).toInt()
        val startBucket = endBucket - m
        if (startBucket < 0) return null
        if (endBucket > magSum.size) return null
        val mag = DoubleArray(m)
        val vert = DoubleArray(m)
        for (i in 0 until m) {
            val mean = meanAt(startBucket + i) ?: return null
            mag[i] = mean.first
            vert[i] = mean.second
        }
        return mag to vert
    }

    /**
     * Quantised route profile (µT×10 int16) over all touched buckets, or
     * null when nothing was sampled. Values clamp to the int16 range
     * (±3276.7 µT — Earth's field never comes close).
     */
    fun toMagProfile(): MagProfile? {
        if (sampleCount == 0) return null
        val (mag, vert) = filledSeries()
        return MagProfile(
            magnitude = mag.map { quantise(it) },
            vertical = vert.map { quantise(it) },
        )
    }

    private fun quantise(uT: Double): Short =
        (uT * 10.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun fillGaps(series: DoubleArray) {
        var prev = -1
        for (i in series.indices) {
            if (!series[i].isNaN()) {
                if (prev < 0) {
                    for (k in 0 until i) series[k] = series[i]
                } else if (i - prev > 1) {
                    val a = series[prev]
                    val b = series[i]
                    for (k in prev + 1 until i) {
                        val f = (k - prev).toDouble() / (i - prev)
                        series[k] = a + (b - a) * f
                    }
                }
                prev = i
            }
        }
        if (prev >= 0) {
            for (k in prev + 1 until series.size) series[k] = series[prev]
        }
    }
}

/**
 * Route-save producer: buckets the log's magnetic samples by distance and
 * quantises them into [MagProfile]. Null when the log carries no magnetic
 * data (pre-W12-04 logs, sensor-less replays) — the route then saves
 * without perception, exactly as before.
 */
fun magProfileFromSamples(samples: List<CaptureSample>): MagProfile? {
    val profiler = MagProfiler()
    for (s in samples) {
        val mag = s.mag ?: continue
        val vert = s.magV ?: continue
        profiler.onSample(s.d, mag, vert)
    }
    return profiler.toMagProfile()
}
