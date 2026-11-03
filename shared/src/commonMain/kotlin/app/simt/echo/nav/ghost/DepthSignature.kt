package app.simt.echo.nav.ghost

import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.model.ParallaxProfile
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Depth-profile geometric signature matcher (plan W17-04).
 *
 * The creator's `ρ(s)` profile is a lighting-proof geometric signature of
 * the corridor (door gaps, junctions, openings change it). The follower
 * accumulates up to [QUERY_M] metres (default 10 m, or shorter down to
 * [MIN_RELOCK_QUERY_M] during Relock) of `(log2(ρ), s_odometer)` buckets and
 * matches the subsequence by the shared [Dtw] (W15-04) against the route's
 * [ParallaxProfile].
 *
 * Emits a soft [Observation.Magnetic]-style position fix only when:
 * - both the reference and observed windows clear [STD_GATE_LOG2] (a uniform
 *   corridor emits nothing);
 * - the best zero-mean DTW cost beats [COST_GATE];
 * - the winner beats every non-adjacent candidate by [MARGIN_FRAC].
 */
object DepthSignatureMatch {

    /** Standard observed window length, metres (20 buckets at 0.5 m). */
    const val QUERY_M = 10.0

    /** Minimum observed window length during Relock (4 m = 8 buckets at 0.5 m). */
    const val MIN_RELOCK_QUERY_M = 4.0

    /** Profile std gate in log₂(ρ) octaves: uniform corridors stay below this. */
    const val STD_GATE_LOG2 = 0.12

    /** Mean squared error per aligned DTW step in zero-mean log₂(ρ) units. */
    const val COST_GATE = 0.20

    /** Sakoe–Chiba band half-width as a fraction of the query length. */
    const val BAND_FRAC = 0.10

    /** Ambiguity margin: winner must cost < MARGIN_FRAC * runner-up outside half-query. */
    const val MARGIN_FRAC = 0.55

    private val LN2 = ln(2.0)

    data class Fix(val estimatedS: Double, val cost: Double, val profileStd: Double)

    /**
     * Matches observed `ρ` buckets [observedRho] (length `m`, at 0.5 m steps,
     * oldest→newest ending at the walker's current position) against [route]
     * around `[meanS] ± 3·[sigma]`. Returns [Fix] or `null` when any gate fails.
     */
    fun match(
        route: ParallaxProfile,
        observedRho: DoubleArray,
        meanS: Double,
        sigma: Double,
        liveEstimatorId: String = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
    ): Fix? {
        if (!route.compatibleWith(liveEstimatorId)) return null
        val step = route.stepM
        val m = observedRho.size
        val minBuckets = (MIN_RELOCK_QUERY_M / step).roundToInt()
        if (m < minBuckets) return null
        if (!meanS.isFinite() || !sigma.isFinite() || sigma < 0.0) return null
        for (v in observedRho) {
            if (!v.isFinite() || v <= 0.0) return null
        }

        val depthBytes = runCatching { Base64.decode(route.inverseDepth) }.getOrNull() ?: return null
        if (depthBytes.size < m) return null

        val totalBuckets = depthBytes.size
        val querySpanM = m * step
        val loS = max(0.0, meanS - 3.0 * sigma - querySpanM)
        val hiS = meanS + 3.0 * sigma
        val loBucket = (loS / step).toInt().coerceIn(0, totalBuckets)
        val hiBucket = (hiS / step).toInt().coerceIn(0, totalBuckets)
        if (hiBucket - loBucket < m) return null

        // Convert reference buckets to log2(rho), interpolating small internal gaps.
        val refLog = DoubleArray(hiBucket - loBucket) { i ->
            val r = ParallaxProfiler.dequantiseRho(depthBytes[loBucket + i])
            if (r.isFinite() && r > 0.0) ln(r) / LN2 else Double.NaN
        }
        fillGaps(refLog)
        if (refLog.any { !it.isFinite() }) return null

        val refStd = std(refLog)
        if (refStd <= STD_GATE_LOG2) return null

        val obsLog = DoubleArray(m) { i -> ln(observedRho[i]) / LN2 }
        if (std(obsLog) <= STD_GATE_LOG2 * 0.7) return null
        val qNorm = zeroMean(obsLog)

        val candidates = refLog.size - m + 1
        val costs = DoubleArray(candidates)
        val scratch = Dtw.Scratch(m)
        val band = max(1, (BAND_FRAC * m).roundToInt())

        for (j in 0 until candidates) {
            if (stdSlice(refLog, j, m) <= STD_GATE_LOG2 * 0.7) {
                costs[j] = Double.POSITIVE_INFINITY
                continue
            }
            val rNorm = zeroMean(refLog, j, m)
            costs[j] = Dtw.distance(m, m, band, scratch, mean = true) { rIdx, cIdx ->
                val d = qNorm[rIdx] - rNorm[cIdx]
                d * d
            }
        }

        var best = 0
        for (j in 1 until candidates) {
            if (costs[j] < costs[best]) best = j
        }
        if (!costs[best].isFinite() || costs[best] >= COST_GATE) return null

        val halfQuery = max(1, m / 2)
        for (j in 0 until candidates) {
            if (abs(j - best) < halfQuery) continue
            if (costs[best] >= MARGIN_FRAC * costs[j]) return null
        }

        val estimatedS = (loBucket + best + m) * step
        return Fix(estimatedS = estimatedS, cost = costs[best], profileStd = refStd)
    }

    private fun zeroMean(series: DoubleArray): DoubleArray {
        var sum = 0.0
        for (v in series) sum += v
        val mean = sum / series.size
        return DoubleArray(series.size) { i -> series[i] - mean }
    }

    private fun zeroMean(series: DoubleArray, from: Int, len: Int): DoubleArray {
        var sum = 0.0
        for (i in 0 until len) sum += series[from + i]
        val mean = sum / len
        return DoubleArray(len) { i -> series[from + i] - mean }
    }

    private fun std(series: DoubleArray): Double = stdSlice(series, 0, series.size)

    private fun stdSlice(series: DoubleArray, from: Int, len: Int): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until len) sum += series[from + i]
        val mean = sum / len
        var variance = 0.0
        for (i in 0 until len) {
            val d = series[from + i] - mean
            variance += d * d
        }
        return sqrt(variance / len)
    }

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
 * Follower-side accumulator of `(ρ, s_odometer)` buckets per 0.5 m (plan W17-04).
 */
class DepthSignatureTracker {
    private val rhoSum = ArrayList<Double>()
    private val counts = ArrayList<Int>()

    fun reset() {
        rhoSum.clear()
        counts.clear()
    }

    fun onSample(distanceM: Double, rhoPerM: Double) {
        if (!distanceM.isFinite() || distanceM < 0.0 || !rhoPerM.isFinite() || rhoPerM <= 0.0) return
        val b = (distanceM / ParallaxProfile.STEP_M).toInt()
        while (rhoSum.size <= b) {
            rhoSum.add(0.0)
            counts.add(0)
        }
        rhoSum[b] += rhoPerM
        counts[b] += 1
    }

    fun lastWindow(lengthM: Double, endDistanceM: Double): DoubleArray? {
        val step = ParallaxProfile.STEP_M
        val m = (lengthM / step).roundToInt()
        if (m <= 0) return null
        val endBucket = (endDistanceM / step).toInt()
        val startBucket = endBucket - m
        if (startBucket < 0 || endBucket > rhoSum.size) return null
        val out = DoubleArray(m)
        for (i in 0 until m) {
            val idx = startBucket + i
            val c = counts[idx]
            if (c <= 0) return null
            out[i] = rhoSum[idx] / c
        }
        return out
    }
}
