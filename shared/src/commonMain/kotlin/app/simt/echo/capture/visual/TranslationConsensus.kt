package app.simt.echo.capture.visual

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Depth-free focus from pairwise translation-flow intersections.
 * A shared expansion is accepted only when the supporting scales agree;
 * otherwise the focus stays with the intersections and a single-depth yaw
 * fit is not allowed to move it. Storage is fixed and reused.
 */
internal class TranslationConsensus {
    var valid: Boolean = false
    var ambiguous: Boolean = false
    var sharedDepth: Boolean = false
    var focusX: Double = 0.0
    var focusY: Double = 0.0
    var medianScalePerSec: Double = 0.0
    var inliers: Int = 0
    val keep = DoubleArray(CAPACITY)

    private val hitX = DoubleArray(MAX_HITS)
    private val hitY = DoubleArray(MAX_HITS)
    private val scales = DoubleArray(CAPACITY)
    private val sortScratch = DoubleArray(MAX_HITS)
    private var probeX = 0.0
    private var probeY = 0.0

    fun evaluate(flow: BlockFlow, inverseTime: Double, baseWeights: DoubleArray) {
        valid = false
        ambiguous = false
        sharedDepth = false
        inliers = 0
        keep.fill(0.0)
        var bestCount = 0
        var bestMask = 0
        var bestX = 0.0
        var bestY = 0.0
        var rivalCount = 0
        for (i in 0 until flow.count) {
            if (!usable(flow, baseWeights, i, inverseTime)) continue
            for (j in i + 1 until flow.count) {
                if (!usable(flow, baseWeights, j, inverseTime)) continue
                if (!intersect(flow, i, j)) continue
                val mask = alignedMask(flow, inverseTime, probeX, probeY, baseWeights)
                val count = mask.countOneBits()
                if (count > bestCount) {
                    if (bestCount > 0 && separated(probeX, probeY, bestX, bestY) &&
                        (bestMask and mask.inv()).countOneBits() >= MIN_RIVAL_DISTINCT
                    ) {
                        rivalCount = maxOf(rivalCount, bestCount)
                    }
                    bestCount = count
                    bestMask = mask
                    bestX = probeX
                    bestY = probeY
                } else if (count > rivalCount && separated(probeX, probeY, bestX, bestY) &&
                    (mask and bestMask.inv()).countOneBits() >= MIN_RIVAL_DISTINCT
                ) {
                    rivalCount = count
                }
            }
        }
        if (bestCount < MIN_INLIERS) return
        if (rivalCount >= bestCount && rivalCount >= MIN_INLIERS) {
            ambiguous = true
            return
        }
        markAligned(flow, inverseTime, bestX, bestY, baseWeights)
        var hits = 0
        for (i in 0 until flow.count) {
            if (keep[i] == 0.0) continue
            for (j in i + 1 until flow.count) {
                if (keep[j] == 0.0 || hits >= MAX_HITS) continue
                if (!intersect(flow, i, j)) continue
                hitX[hits] = probeX
                hitY[hits] = probeY
                hits++
            }
        }
        if (hits == 0) return
        focusX = median(hitX, hits)
        focusY = median(hitY, hits)
        markAligned(flow, inverseTime, focusX, focusY, baseWeights)
        var scaleCount = 0
        var scaleSum = 0.0
        for (i in 0 until flow.count) {
            if (keep[i] == 0.0) continue
            val scale = scaleAt(flow, inverseTime, i, focusX, focusY)
            if (!scale.isFinite()) {
                keep[i] = 0.0
                continue
            }
            scales[scaleCount++] = scale
            scaleSum += scale
        }
        inliers = scaleCount
        if (inliers < MIN_INLIERS) return
        medianScalePerSec = median(scales, inliers)
        val mean = scaleSum / inliers
        var variance = 0.0
        for (i in 0 until inliers) {
            val delta = scales[i] - mean
            variance += delta * delta
        }
        val spread = sqrt(variance / inliers)
        val scaleMagnitude = abs(medianScalePerSec)
        sharedDepth = scaleMagnitude >= MIN_SCALE_PER_SEC && spread <= SHARED_SPREAD * scaleMagnitude
        valid = true
    }

    private fun usable(flow: BlockFlow, baseWeights: DoubleArray, index: Int, inverseTime: Double): Boolean {
        if (baseWeights[index] == 0.0) return false
        return hypot(flow.dx[index], flow.dy[index]) * inverseTime >= MIN_RATE_PER_SEC
    }

    private fun intersect(flow: BlockFlow, i: Int, j: Int): Boolean {
        val dx1 = flow.dx[i]
        val dy1 = flow.dy[i]
        val dx2 = flow.dx[j]
        val dy2 = flow.dy[j]
        val determinant = dy1 * -dx2 - (-dx1) * dy2
        if (abs(determinant) <= PARALLEL_DET) return false
        val x1 = flow.x[i]
        val y1 = flow.y[i]
        val x2 = flow.x[j]
        val y2 = flow.y[j]
        val b1 = x1 * dy1 - y1 * dx1
        val b2 = x2 * dy2 - y2 * dx2
        probeX = (b1 * -dx2 - (-dx1) * b2) / determinant
        probeY = (dy1 * b2 - b1 * dy2) / determinant
        return probeX.isFinite() && probeY.isFinite() && abs(probeX) <= MAX_FOCUS && abs(probeY) <= MAX_FOCUS
    }

    private fun alignedMask(flow: BlockFlow, inverseTime: Double, focusX: Double, focusY: Double, baseWeights: DoubleArray): Int {
        var mask = 0
        for (i in 0 until flow.count) if (aligned(flow, inverseTime, i, focusX, focusY, baseWeights)) mask = mask or (1 shl i)
        return mask
    }

    private fun markAligned(flow: BlockFlow, inverseTime: Double, focusX: Double, focusY: Double, baseWeights: DoubleArray) {
        for (i in 0 until flow.count) keep[i] = if (aligned(flow, inverseTime, i, focusX, focusY, baseWeights)) 1.0 else 0.0
    }

    private fun aligned(flow: BlockFlow, inverseTime: Double, index: Int, focusX: Double, focusY: Double, baseWeights: DoubleArray): Boolean {
        if (baseWeights[index] == 0.0) return false
        val horizontal = flow.dx[index] * inverseTime
        val vertical = flow.dy[index] * inverseTime
        val radiusX = flow.x[index] - focusX
        val radiusY = flow.y[index] - focusY
        val radius = hypot(radiusX, radiusY)
        val magnitude = hypot(horizontal, vertical)
        if (radius < MIN_RADIUS || magnitude < MIN_RATE_PER_SEC) return false
        val sine = abs(radiusX * vertical - radiusY * horizontal) / (radius * magnitude)
        return sine <= MAX_DIRECTION_SINE
    }

    private fun scaleAt(flow: BlockFlow, inverseTime: Double, index: Int, focusX: Double, focusY: Double): Double {
        val radiusX = flow.x[index] - focusX
        val radiusY = flow.y[index] - focusY
        val radiusSquared = radiusX * radiusX + radiusY * radiusY
        if (radiusSquared < MIN_RADIUS * MIN_RADIUS) return Double.NaN
        val horizontal = flow.dx[index] * inverseTime
        val vertical = flow.dy[index] * inverseTime
        return (horizontal * radiusX + vertical * radiusY) / radiusSquared
    }

    private fun separated(x: Double, y: Double, otherX: Double, otherY: Double): Boolean =
        abs(x - otherX) > FOCUS_SEPARATION || abs(y - otherY) > FOCUS_SEPARATION

    private fun median(source: DoubleArray, count: Int): Double {
        for (i in 0 until count) sortScratch[i] = source[i]
        for (i in 1 until count) {
            val value = sortScratch[i]
            var hole = i
            while (hole > 0 && sortScratch[hole - 1] > value) {
                sortScratch[hole] = sortScratch[hole - 1]
                hole--
            }
            sortScratch[hole] = value
        }
        return if (count % 2 == 1) sortScratch[count / 2] else 0.5 * (sortScratch[count / 2 - 1] + sortScratch[count / 2])
    }

    private companion object {
        const val CAPACITY = 12
        const val MAX_HITS = 66
        const val MIN_INLIERS = 6
        const val MIN_RIVAL_DISTINCT = 2
        const val MIN_RATE_PER_SEC = 0.004
        const val MIN_RADIUS = 1e-4
        const val MAX_DIRECTION_SINE = 0.2
        const val MAX_FOCUS = 1.5
        const val FOCUS_SEPARATION = 0.08
        const val PARALLEL_DET = 1e-10
        const val MIN_SCALE_PER_SEC = 0.01
        const val SHARED_SPREAD = 0.08
    }
}
