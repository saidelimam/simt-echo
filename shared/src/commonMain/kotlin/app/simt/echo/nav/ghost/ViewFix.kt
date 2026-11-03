package app.simt.echo.nav.ghost

import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.capture.wrapPi
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.ViewStrip
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * ViewFix strip-sequence matcher (plan W17-03).
 *
 * Correlates the live 96-byte view strip (64 column gradient marginals +
 * 32 row gradient marginals) against stored route [ViewStrip]s within
 * `s ± (3σ + 5 m)` over ±12 column shifts and 5 stretch factors
 * (`0.85, 0.925, 1.00, 1.075, 1.15`):
 * - Best shift ⇒ `Δheading = shiftCols * (fovRad / 64)`.
 * - Best stretch ⇒ `Δs = (1 - 1 / stretch) / ρ(nodeS)`.
 * - A SeqSLAM-style sequence score over the last ≤ 4 live strips against
 *   stored strips using the shared [Dtw] (W15-04) and a runner-up margin gate
 *   prevent repeated corridors from aliasing.
 * - Preallocates all scratch arrays so [match] allocates 0 bytes on the
 *   hot path.
 */
class ViewFixMatcher(
    profile: ParallaxProfile?,
    extraStrips: List<ViewStrip> = emptyList(),
) {
    private val stripDistancesM: DoubleArray
    private val stripBytes: Array<ByteArray>
    private val rhoBuckets: DoubleArray
    private val stepM: Double

    // Ring of last 4 live strips (64 + 32 bytes each) and their odometer distances.
    private val liveRing = Array(SEQ_LEN) { ByteArray(ViewStrip.BYTES) }
    private val liveDistM = DoubleArray(SEQ_LEN) { Double.NaN }
    private var liveCursor = 0
    private var liveCount = 0

    // Reusable scratch for candidate scoring (0 bytes per match).
    private val candScores = DoubleArray(MAX_CANDIDATES)
    private val candIndices = IntArray(MAX_CANDIDATES)
    private val candBestShift = DoubleArray(MAX_CANDIDATES)
    private val candBestStretch = DoubleArray(MAX_CANDIDATES)
    private val shiftCurve = DoubleArray(2 * MAX_SHIFT_COLS + 1)
    private val stretchCurve = DoubleArray(STRETCH_COUNT)
    private val dtwScratch = Dtw.Scratch(SEQ_LEN)

    var lastNodeS: Double = Double.NaN
        private set
    var lastDHeadingRad: Double = 0.0
        private set
    var lastDSM: Double = 0.0
        private set
    var lastConfidence01: Double = 0.0
        private set
    var lastScore01: Double = 0.0
        private set

    init {
        val merged = buildList {
            profile?.viewStrips?.let { addAll(it) }
            addAll(extraStrips)
        }.filter { it.atDistanceM.isFinite() && it.atDistanceM >= 0.0 }
            .sortedBy { it.atDistanceM }
            .take(MAX_STORED_STRIPS)

        val decodedStrips = ArrayList<Pair<Double, ByteArray>>(merged.size)
        for (vs in merged) {
            val raw = runCatching { Base64.decode(vs.dataB64) }.getOrNull() ?: continue
            if (raw.size == ViewStrip.BYTES) {
                decodedStrips.add(vs.atDistanceM to raw)
            }
        }
        stripDistancesM = DoubleArray(decodedStrips.size) { decodedStrips[it].first }
        stripBytes = Array(decodedStrips.size) { decodedStrips[it].second }

        val depthBytes = profile?.inverseDepth
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
        stepM = profile?.stepM ?: ParallaxProfile.STEP_M
        rhoBuckets = if (depthBytes != null && depthBytes.isNotEmpty()) {
            DoubleArray(depthBytes.size) { i -> ParallaxProfiler.dequantiseRho(depthBytes[i]) }
        } else {
            DoubleArray(0)
        }
    }

    val storedStripCount: Int get() = stripBytes.size

    fun reset() {
        liveDistM.fill(Double.NaN)
        liveCursor = 0
        liveCount = 0
        clearResult()
    }

    /**
     * Records [liveStrip] (96 bytes) at follower odometer [odoM] and matches
     * against stored route strips within `meanS ± (3 * sigmaM + 5 m)`.
     * Returns `true` and populates [lastNodeS], [lastDHeadingRad], [lastDSM],
     * [lastConfidence01] when a match clears both the correlation and margin gates.
     */
    fun match(
        liveStrip: ByteArray,
        odoM: Double,
        meanS: Double,
        sigmaM: Double,
        fovRad: Double = DEFAULT_FOV_RAD,
    ): Boolean {
        clearResult()
        if (liveStrip.size != ViewStrip.BYTES || stripBytes.isEmpty()) return false
        if (!odoM.isFinite() || !meanS.isFinite() || !sigmaM.isFinite() || sigmaM < 0.0) return false
        if (!fovRad.isFinite() || fovRad <= 0.0) return false

        // Push into the 4-strip live history ring if distance advanced >= 1.2 m or ring is empty.
        val prevIdx = (liveCursor - 1 + SEQ_LEN) % SEQ_LEN
        val prevOdo = if (liveCount > 0) liveDistM[prevIdx] else Double.NaN
        if (liveCount == 0 || !prevOdo.isFinite() || abs(odoM - prevOdo) >= MIN_SEQ_STEP_M) {
            liveStrip.copyInto(liveRing[liveCursor])
            liveDistM[liveCursor] = odoM
            liveCursor = (liveCursor + 1) % SEQ_LEN
            if (liveCount < SEQ_LEN) liveCount++
        } else {
            liveStrip.copyInto(liveRing[prevIdx])
            liveDistM[prevIdx] = odoM
        }

        val halfWindow = 3.0 * sigmaM + SEARCH_PAD_M
        val loS = meanS - halfWindow
        val hiS = meanS + halfWindow

        var candCount = 0
        for (idx in stripDistancesM.indices) {
            if (candCount >= MAX_CANDIDATES) break
            val d = stripDistancesM[idx]
            if (d < loS || d > hiS) continue
            val stored = stripBytes[idx]

            val singleScore = scoreStripPair(liveStrip, stored, recordPeaks = true)
            val seqScore = if (liveCount >= 2 && idx >= 1) {
                sequenceScoreAt(idx, singleScore)
            } else {
                singleScore
            }

            candIndices[candCount] = idx
            candScores[candCount] = seqScore
            candBestShift[candCount] = peakShiftCols
            candBestStretch[candCount] = peakStretch
            candCount++
        }

        if (candCount == 0) return false

        var bestCand = 0
        for (c in 1 until candCount) {
            if (candScores[c] > candScores[bestCand]) bestCand = c
        }

        val bestScore = candScores[bestCand]
        if (bestScore < MIN_MATCH_SCORE) return false

        val bestDistM = stripDistancesM[candIndices[bestCand]]
        var runnerUpScore = -1.0
        var hasDistinctRunnerUp = false
        for (c in 0 until candCount) {
            if (c == bestCand) continue
            val d = stripDistancesM[candIndices[c]]
            // Distinct waypoint (at least 1.5 m away, not a duplicate overlay strip at the same node).
            if (abs(d - bestDistM) >= DISTINCT_WAYPOINT_M) {
                hasDistinctRunnerUp = true
                if (candScores[c] > runnerUpScore) runnerUpScore = candScores[c]
            }
        }

        if (hasDistinctRunnerUp && (bestScore - runnerUpScore) < MIN_RUNNER_UP_MARGIN) {
            return false
        }

        val shiftCols = candBestShift[bestCand]
        val stretch = candBestStretch[bestCand]
        val rho = rhoAt(bestDistM)
        // Positive camera yaw moves the image left (shiftCols < 0), so dHeading = -shiftCols * (fovRad / 64).
        val dHeading = -shiftCols * (fovRad / ViewStrip.COLUMNS.toDouble())
        val dS = ((1.0 - 1.0 / stretch) / rho).coerceIn(-MAX_DS_M, MAX_DS_M)
        val marginFactor = if (hasDistinctRunnerUp) {
            ((bestScore - runnerUpScore) / 0.25).coerceIn(0.4, 1.0)
        } else {
            1.0
        }
        val confidence = (bestScore * marginFactor).coerceIn(0.0, 1.0)
        if (confidence < MIN_CONFIDENCE) return false

        lastNodeS = bestDistM
        lastDHeadingRad = dHeading
        lastDSM = dS
        lastConfidence01 = confidence
        lastScore01 = bestScore
        return true
    }

    /** Convenience wrapper returning [Observation.ViewFix] when [match] succeeds. */
    fun matchObservation(
        liveStrip: ByteArray,
        odoM: Double,
        meanS: Double,
        sigmaM: Double,
        fovRad: Double = DEFAULT_FOV_RAD,
    ): Observation.ViewFix? {
        if (!match(liveStrip, odoM, meanS, sigmaM, fovRad)) return null
        return Observation.ViewFix(
            nodeS = lastNodeS,
            dHeadingRad = lastDHeadingRad,
            dSM = lastDSM,
            confidence01 = lastConfidence01,
        )
    }

    private var peakShiftCols = 0.0
    private var peakStretch = 1.0

    /**
     * Correlates [live] against [stored] over ±12 column shifts and 5 stretch
     * factors. Uses parabolic sub-column and sub-stretch interpolation around
     * the correlation peak.
     */
    internal fun scoreStripPair(live: ByteArray, stored: ByteArray, recordPeaks: Boolean): Double {
        var bestColScore = -1.0
        var bestShiftIdx = MAX_SHIFT_COLS
        var bestStretchIdx = 2 // 1.00

        for (sIdx in 0 until STRETCH_COUNT) {
            val stretch = STRETCHES[sIdx]
            var bestForStretch = -1.0
            for (shift in -MAX_SHIFT_COLS..MAX_SHIFT_COLS) {
                val ncc = columnNcc(live, stored, shift, stretch)
                if (sIdx == 2) {
                    shiftCurve[shift + MAX_SHIFT_COLS] = ncc
                }
                if (ncc > bestForStretch) bestForStretch = ncc
                if (ncc > bestColScore) {
                    bestColScore = ncc
                    bestShiftIdx = shift + MAX_SHIFT_COLS
                    bestStretchIdx = sIdx
                }
            }
            stretchCurve[sIdx] = bestForStretch
        }

        // Row correlation at the best stretch factor.
        val rowScore = rowNcc(live, stored, STRETCHES[bestStretchIdx])

        if (recordPeaks) {
            // Re-populate shiftCurve at bestStretchIdx for sub-column peak interpolation.
            val chosenStretch = STRETCHES[bestStretchIdx]
            for (shift in -MAX_SHIFT_COLS..MAX_SHIFT_COLS) {
                shiftCurve[shift + MAX_SHIFT_COLS] = columnNcc(live, stored, shift, chosenStretch)
            }
            val subShift = parabolicPeakOffset(shiftCurve, bestShiftIdx, shiftCurve.size)
            peakShiftCols = (bestShiftIdx - MAX_SHIFT_COLS) + subShift

            // Re-evaluate stretchCurve at the winning shift column so stretch and shift
            // are not coupled across different shifts.
            val bestShift = bestShiftIdx - MAX_SHIFT_COLS
            var bestSForShift = 0
            for (sIdx in 0 until STRETCH_COUNT) {
                stretchCurve[sIdx] = columnNcc(live, stored, bestShift, STRETCHES[sIdx])
                if (stretchCurve[sIdx] > stretchCurve[bestSForShift]) bestSForShift = sIdx
            }
            val subStretch = parabolicPeakOffset(stretchCurve, bestSForShift, STRETCH_COUNT)
            peakStretch = (STRETCHES[bestSForShift] + subStretch * STRETCH_STEP).coerceIn(STRETCHES[0], STRETCHES[STRETCH_COUNT - 1])
        }

        return (0.75 * bestColScore + 0.25 * rowScore).coerceIn(-1.0, 1.0)
    }

    private fun sequenceScoreAt(candStripIdx: Int, currentPairScore: Double): Double {
        val m = minOf(liveCount, minOf(SEQ_LEN, candStripIdx + 1))
        if (m < 2) return currentPairScore
        val refStart = candStripIdx - m + 1
        val cost = Dtw.distance(m, m, band = 1, scratch = dtwScratch, mean = true) { i, j ->
            val liveIdx = (liveCursor - m + i + SEQ_LEN) % SEQ_LEN
            val refIdx = refStart + j
            val sim = if (i == m - 1 && refIdx == candStripIdx) {
                currentPairScore
            } else {
                scoreStripPair(liveRing[liveIdx], stripBytes[refIdx], recordPeaks = false)
            }
            (1.0 - sim).coerceAtLeast(0.0)
        }
        val dtwSim = (1.0 - cost).coerceIn(-1.0, 1.0)
        return 0.65 * currentPairScore + 0.35 * dtwSim
    }

    private fun columnNcc(live: ByteArray, stored: ByteArray, shiftCols: Int, stretch: Double): Double {
        val center = (ViewStrip.COLUMNS - 1) * 0.5
        val invStretch = 1.0 / stretch
        var dot = 0.0
        var normL = 0.0
        var normS = 0.0
        var count = 0

        for (c in 0 until ViewStrip.COLUMNS) {
            val srcCol = center + (c - shiftCols - center) * invStretch
            if (srcCol < 0.0 || srcCol > ViewStrip.COLUMNS - 1.001) continue
            val c0 = srcCol.toInt()
            val c1 = (c0 + 1).coerceAtMost(ViewStrip.COLUMNS - 1)
            val frac = srcCol - c0
            val sVal = stored[c0].toInt() * (1.0 - frac) + stored[c1].toInt() * frac
            val lVal = live[c].toInt().toDouble()
            dot += lVal * sVal
            normL += lVal * lVal
            normS += sVal * sVal
            count++
        }
        if (count < 24 || normL <= 1e-6 || normS <= 1e-6) return 0.0
        return dot / sqrt(normL * normS)
    }

    private fun rowNcc(live: ByteArray, stored: ByteArray, stretch: Double): Double {
        val base = ViewStrip.COLUMNS
        val center = (ViewStrip.ROWS - 1) * 0.5
        val invStretch = 1.0 / stretch
        var dot = 0.0
        var normL = 0.0
        var normS = 0.0
        var count = 0

        for (r in 0 until ViewStrip.ROWS) {
            val srcRow = center + (r - center) * invStretch
            if (srcRow < 0.0 || srcRow > ViewStrip.ROWS - 1.001) continue
            val r0 = srcRow.toInt()
            val r1 = (r0 + 1).coerceAtMost(ViewStrip.ROWS - 1)
            val frac = srcRow - r0
            val sVal = stored[base + r0].toInt() * (1.0 - frac) + stored[base + r1].toInt() * frac
            val lVal = live[base + r].toInt().toDouble()
            dot += lVal * sVal
            normL += lVal * lVal
            normS += sVal * sVal
            count++
        }
        if (count < 12 || normL <= 1e-6 || normS <= 1e-6) return 0.0
        return dot / sqrt(normL * normS)
    }

    private fun parabolicPeakOffset(curve: DoubleArray, index: Int, size: Int): Double {
        if (index <= 0 || index >= size - 1) return 0.0
        val left = curve[index - 1]
        val mid = curve[index]
        val right = curve[index + 1]
        val denom = 2.0 * (left - 2.0 * mid + right)
        if (abs(denom) <= 1e-9) return 0.0
        return ((left - right) / denom).coerceIn(-0.5, 0.5)
    }

    private fun rhoAt(distanceM: Double): Double {
        if (rhoBuckets.isEmpty() || stepM <= 0.0) return DEFAULT_RHO_PER_M
        val b = (distanceM / stepM).toInt().coerceIn(0, rhoBuckets.lastIndex)
        val r = rhoBuckets[b]
        if (r.isFinite() && r > 0.0) return r
        for (delta in 1..4) {
            val lo = (b - delta).coerceAtLeast(0)
            if (rhoBuckets[lo].isFinite() && rhoBuckets[lo] > 0.0) return rhoBuckets[lo]
            val hi = (b + delta).coerceAtMost(rhoBuckets.lastIndex)
            if (rhoBuckets[hi].isFinite() && rhoBuckets[hi] > 0.0) return rhoBuckets[hi]
        }
        return DEFAULT_RHO_PER_M
    }

    private fun clearResult() {
        lastNodeS = Double.NaN
        lastDHeadingRad = 0.0
        lastDSM = 0.0
        lastConfidence01 = 0.0
        lastScore01 = 0.0
    }

    companion object {
        const val MAX_SHIFT_COLS = 12
        const val STRETCH_COUNT = 5
        const val STRETCH_STEP = 0.075
        private val STRETCHES = doubleArrayOf(0.85, 0.925, 1.00, 1.075, 1.15)
        const val SEQ_LEN = 4
        const val SEARCH_PAD_M = 5.0
        const val MIN_MATCH_SCORE = 0.42
        const val MIN_RUNNER_UP_MARGIN = 0.08
        const val MIN_CONFIDENCE = 0.40
        const val DEFAULT_FOV_RAD = 65.0 * PI / 180.0
        private const val DEFAULT_RHO_PER_M = 0.25
        private const val MAX_DS_M = 2.5
        private const val MIN_SEQ_STEP_M = 1.2
        private const val DISTINCT_WAYPOINT_M = 1.5
        private const val MAX_CANDIDATES = 32
        private const val MAX_STORED_STRIPS = 128
        private const val VIEW_FIX_SIGMA_M = 1.2
        private const val VIEW_FIX_KAPPA = 4.0
        private const val VIEW_FIX_FLOOR = 0.15

        /**
         * Applies a soft [Observation.ViewFix] across particle positions [sA],
         * heading biases [bA], and weights [wA]:
         * - Gaussian likelihood on `s` around `nodeS + dSM`.
         * - Soft nudge of particle heading bias `b` toward `-dHeadingRad`
         *   (von Mises / weighted circular update).
         * Never a hard anchor and never an auto-confirm.
         */
        internal fun applyToParticles(
            obs: Observation.ViewFix,
            sA: DoubleArray,
            bA: DoubleArray,
            wA: DoubleArray,
            activeCount: Int,
        ): Boolean {
            if (!obs.nodeS.isFinite() || !obs.dHeadingRad.isFinite() ||
                !obs.dSM.isFinite() || !obs.confidence01.isFinite() || obs.confidence01 <= 0.0
            ) return false
            val targetS = (obs.nodeS + obs.dSM).coerceAtLeast(0.0)
            val conf = obs.confidence01.coerceIn(0.0, 1.0)
            val sigmaS = VIEW_FIX_SIGMA_M / conf.coerceAtLeast(0.35)
            val invTwoVar = 1.0 / (2.0 * sigmaS * sigmaS)
            val kappa = VIEW_FIX_KAPPA * conf
            val biasStep = (0.35 * conf).coerceIn(0.0, 0.5)

            for (i in 0 until activeCount) {
                val ds = sA[i] - targetS
                val posLike = exp(-ds * ds * invTwoVar)
                val headLike = exp(kappa * (cos(bA[i] + obs.dHeadingRad) - 1.0))
                wA[i] *= posLike * headLike + VIEW_FIX_FLOOR
                bA[i] = wrapPi(bA[i] + biasStep * wrapPi(-obs.dHeadingRad - bA[i]))
            }
            return true
        }
    }
}
