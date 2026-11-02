package app.simt.echo.nav.ghost

import app.simt.echo.model.MagProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Magnetic offset observation (plan W12-04).
 *
 * The last [QUERY_M] metres observed are matched against the route's
 * stored [MagProfile] around `s ± 3σ` by subsequence DTW: the query is
 * compared, with a Sakoe–Chiba band of [BAND_FRAC], against every
 * query-length subsequence of the search window, and the best start wins.
 * Both channels (|B| and vertical) are zero-mean normalised per compared
 * pair, so a phone-dependent hard-iron offset cancels — the match is on
 * shape (steel anomalies), never on absolute level.
 *
 * An observation is emitted only when all three gates pass:
 * - the route window's magnitude std exceeds [STD_GATE_UT] (a flat
 *   corridor carries no information — silence, not a wrong fix);
 * - the best normalised DTW cost beats [COST_GATE];
 * - the best cost beats every non-adjacent candidate by [MARGIN_FRAC]
 *   (a repeated anomaly — two identical steel doors — is ambiguous, and
 *   an ambiguous fix is worse than none).
 *
 * Pure, deterministic, allocation-tolerant (runs at most once per 0.5 m
 * bucket, never in the ≤ 1 ms filter hot loop). No HTTP — see `nav/`
 * contract.
 *
 * Device trace on one real corridor: owed (W14 field work). Host evidence
 * is the synthetic steel-anomaly corridor in `MagneticMatchTest`.
 */
object MagneticMatch {

    /** Observed window length, metres (20 buckets at 0.5 m). */
    const val QUERY_M = 10.0

    /** Profile std gate, µT: at or below this the corridor is flat. */
    const val STD_GATE_UT = 3.0

    /**
     * DTW cost gate: mean squared error per aligned step (µT², over both
     * channels after zero-mean normalisation). A correct alignment leaves
     * sensor noise (~1 µT RMSE); a wrong one leaves anomaly amplitude.
     */
    const val COST_GATE = 9.0

    /** Sakoe–Chiba band half-width as a fraction of the query length. */
    const val BAND_FRAC = 0.10

    /**
     * Ambiguity margin: the winner must cost less than this fraction of
     * every candidate starting at least half a query away.
     */
    const val MARGIN_FRAC = 0.5

    /** One emitted fix: the walker's estimated current route position. */
    data class Fix(val estimatedS: Double, val cost: Double, val profileStd: Double)

    /**
     * Matches the last [QUERY_M] metres observed ([observedMag],
     * [observedVert], oldest→newest, µT floats, ending at the walker's
     * current position) against [route] around filter belief ([meanS] ±
     * 3·[sigma]). Returns the fix, or null when any gate fails.
     *
     * Both series share the [MagProfile.MAG_STEP_M] bucket grid; route
     * bucket `j` covers `[j·step, (j+1)·step)`.
     */
    fun match(
        route: MagProfile,
        observedMag: DoubleArray,
        observedVert: DoubleArray,
        meanS: Double,
        sigma: Double,
    ): Fix? {
        val step = MagProfile.MAG_STEP_M
        val m = (QUERY_M / step).roundToInt()
        if (observedMag.size != m || observedVert.size != m) return null
        if (!meanS.isFinite() || !sigma.isFinite() || sigma < 0.0) return null
        for (i in 0 until m) {
            if (!observedMag[i].isFinite() || !observedVert[i].isFinite()) return null
        }
        if (route.magnitude.size != route.vertical.size || route.magnitude.isEmpty()) return null

        val totalBuckets = route.magnitude.size
        val loS = max(0.0, meanS - 3.0 * sigma - QUERY_M)
        val hiS = meanS + 3.0 * sigma
        val loBucket = (loS / step).toInt().coerceIn(0, totalBuckets)
        val hiBucket = (hiS / step).toInt().coerceIn(0, totalBuckets)
        if (hiBucket - loBucket < m) return null

        val refMag = DoubleArray(hiBucket - loBucket) { i -> route.magnitude[loBucket + i] / 10.0 }
        val refVert = DoubleArray(hiBucket - loBucket) { i -> route.vertical[loBucket + i] / 10.0 }

        if (std(refMag) <= STD_GATE_UT) return null

        val qMag = zeroMean(observedMag)
        val qVert = zeroMean(observedVert)

        val candidates = refMag.size - m + 1
        val costs = DoubleArray(candidates)
        for (j in 0 until candidates) {
            val rMag = zeroMean(refMag, j, m)
            val rVert = zeroMean(refVert, j, m)
            costs[j] = dtwMeanCost(qMag, qVert, rMag, rVert)
        }
        var best = 0
        for (j in 1 until candidates) if (costs[j] < costs[best]) best = j
        if (costs[best] >= COST_GATE) return null

        val halfQuery = max(1, m / 2)
        for (j in 0 until candidates) {
            if (abs(j - best) < halfQuery) continue
            if (costs[best] >= MARGIN_FRAC * costs[j]) return null
        }

        val estimatedS = (loBucket + best + m) * step
        return Fix(estimatedS = estimatedS, cost = costs[best], profileStd = std(refMag))
    }

    /**
     * DTW mean cost per aligned step between zero-mean query [q] (length m)
     * and zero-mean reference [r] (length m) over both channels, under a
     * Sakoe–Chiba band of [BAND_FRAC]·m. Local cost is squared Euclidean;
     * the total is divided by the warping-path length so short cuts cannot
     * win by taking fewer steps.
     */
    internal fun dtwMeanCost(
        qMag: DoubleArray,
        qVert: DoubleArray,
        rMag: DoubleArray,
        rVert: DoubleArray,
    ): Double {
        val m = qMag.size
        val band = max(1, (BAND_FRAC * m).roundToInt())
        val inf = Double.POSITIVE_INFINITY
        var prev = DoubleArray(m + 1) { inf }
        var curr = DoubleArray(m + 1) { inf }
        var prevLen = IntArray(m + 1)
        var currLen = IntArray(m + 1)
        prev[0] = 0.0
        prevLen[0] = 0
        for (i in 1..m) {
            curr[0] = inf
            val klo = max(1, i - band)
            val khi = minOf(m, i + band)
            for (k in 1..m) {
                if (k < klo || k > khi) {
                    curr[k] = inf
                    continue
                }
                val dm = qMag[i - 1] - rMag[k - 1]
                val dv = qVert[i - 1] - rVert[k - 1]
                val local = dm * dm + dv * dv
                var bestPrev = prev[k]
                var bestLen = prevLen[k]
                if (curr[k - 1] < bestPrev) {
                    bestPrev = curr[k - 1]
                    bestLen = currLen[k - 1]
                }
                if (prev[k - 1] < bestPrev) {
                    bestPrev = prev[k - 1]
                    bestLen = prevLen[k - 1]
                }
                curr[k] = if (bestPrev.isInfinite()) inf else bestPrev + local
                currLen[k] = if (bestPrev.isInfinite()) 0 else bestLen + 1
            }
            val tmp = prev
            prev = curr
            curr = tmp
            val tmpLen = prevLen
            prevLen = currLen
            currLen = tmpLen
        }
        if (prev[m].isInfinite() || prevLen[m] == 0) return inf
        return prev[m] / prevLen[m]
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

    private fun std(series: DoubleArray): Double {
        if (series.isEmpty()) return 0.0
        var sum = 0.0
        for (v in series) sum += v
        val mean = sum / series.size
        var variance = 0.0
        for (v in series) {
            val d = v - mean
            variance += d * d
        }
        return sqrt(variance / series.size)
    }
}
