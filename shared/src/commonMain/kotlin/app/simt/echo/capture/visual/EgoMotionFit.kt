package app.simt.echo.capture.visual

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Radial-flow and residual-yaw fit, one analysis thread. Travel direction
 * comes from robust intersections of translation-flow lines, so each block
 * may have its own depth. The shared-depth yaw model is published only when
 * those scales agree; a low residual from that model is not itself a direction.
 * Three Huber IRLS passes then run on the supporting blocks. All storage,
 * including the 4x4 augmented solve, is reused between calls.
 */
class EgoMotionFit {
    private val result = VisualEgo()
    private val baseWeights = DoubleArray(CAPACITY)
    private val weights = DoubleArray(CAPACITY)
    private val normal = DoubleArray(20)
    private val equations = DoubleArray(20)
    private val row = DoubleArray(4)
    private val coefficients = DoubleArray(4)
    private val consensus = TranslationConsensus()
    private var inverseYawInformation = 0.0

    fun fit(flow: BlockFlow, usability01: Double): VisualEgo {
        result.clear()
        if (flow.count !in 1..minOf(CAPACITY, flow.x.size) || !flow.deltaSeconds.isFinite() || flow.deltaSeconds <= 0.0 ||
            !flow.fPx.isFinite() || flow.fPx <= 0.0 || !usability01.isFinite() || usability01 <= 0.0
        ) return result
        val inverseTime = 1.0 / flow.deltaSeconds
        val huber = HUBER_PIXELS * inverseTime / flow.fPx
        for (i in 0 until flow.count) {
            val valid = flow.x[i].isFinite() && flow.y[i].isFinite() && flow.dx[i].isFinite() && flow.dy[i].isFinite() &&
                flow.texture[i].isFinite() && flow.texture[i] >= VisualUsability.MIN_TEXTURE_EIGENVALUE && flow.quality[i].isFinite()
            baseWeights[i] = if (valid) VisualUsability.textureFactor01(flow.texture[i]) * flow.quality[i].coerceIn(0.0, 1.0) else 0.0
            weights[i] = baseWeights[i]
        }
        consensus.evaluate(flow, inverseTime, baseWeights)
        if (consensus.valid && !consensus.sharedDepth) return publishMixedDepth(flow, inverseTime, usability01)
        if (consensus.valid) {
            for (i in 0 until flow.count) {
                if (consensus.keep[i] == 0.0) {
                    baseWeights[i] = 0.0
                    weights[i] = 0.0
                }
            }
        }
        var dimensions = 4
        for (pass in 0..IRLS_PASSES) {
            normal.fill(0.0)
            for (i in 0 until flow.count) {
                if (weights[i] == 0.0) continue
                val x = flow.x[i]
                val y = flow.y[i]
                addRow(x, 1.0, 0.0, -(1.0 + x * x), flow.dx[i] * inverseTime, weights[i])
                addRow(y, 0.0, 1.0, -x * y, flow.dy[i] * inverseTime, weights[i])
            }
            if (!solve(dimensions)) return result
            if (pass == 0 && !yawSignificant(flow, inverseTime)) {
                dimensions = 3
                if (!solve(dimensions)) return result
            }
            if (pass < IRLS_PASSES) {
                for (i in 0 until flow.count) {
                    if (baseWeights[i] == 0.0) continue
                    val residual = residual(flow, i, inverseTime)
                    weights[i] = if (!residual.isFinite()) 0.0 else baseWeights[i] * if (residual <= huber) 1.0 else huber / residual
                }
            }
        }
        var yawResidual = coefficients[3]
        var yawSigma = Double.POSITIVE_INFINITY
        if (dimensions == 3) {
            val expansion = coefficients[0]
            val horizontal = coefficients[1]
            val vertical = coefficients[2]
            if (solve(4)) {
                yawResidual = coefficients[3]
                yawSigma = profileYawSigma(flow, inverseTime)
            }
            coefficients[0] = expansion
            coefficients[1] = horizontal
            coefficients[2] = vertical
            coefficients[3] = 0.0
        } else {
            yawSigma = profileYawSigma(flow, inverseTime)
        }
        var inliers = 0
        var residualSquares = 0.0
        var inlierWeight = 0.0
        for (i in 0 until flow.count) {
            if (baseWeights[i] == 0.0) continue
            val residual = residual(flow, i, inverseTime)
            if (residual <= 2.0 * huber) {
                inliers++
                inlierWeight += baseWeights[i]
                residualSquares += baseWeights[i] * residual * residual
            }
        }
        if (inliers == 0 || inlierWeight == 0.0) return result
        val rmsPixels = sqrt(residualSquares / inlierWeight) * flow.deltaSeconds * flow.fPx
        val countFactor = if (inliers < MIN_INLIERS) 0.49 * inliers / MIN_INLIERS else (inliers / 8.0).coerceAtMost(1.0)
        result.expansionPerSec = coefficients[0]
        result.yawResidualRadPerSec = yawResidual
        result.yawSigmaRadPerSec = yawSigma
        result.inliers = inliers
        result.confidence01 = (countFactor * (inlierWeight / inliers) * usability01.coerceIn(0.0, 1.0) *
            exp(-rmsPixels * rmsPixels / (RMS_SCALE_PIXELS * RMS_SCALE_PIXELS))).coerceIn(0.0, 1.0)
        if (consensus.valid && abs(coefficients[0]) >= MIN_EXPANSION_PER_SEC) {
            val foeX = -coefficients[1] / coefficients[0]
            val foeY = -coefficients[2] / coefficients[0]
            if (foeX.isFinite() && foeY.isFinite()) {
                result.foeX = foeX
                result.foeY = foeY
                result.hasFoe = true
            }
        }
        if (consensus.ambiguous) result.confidence01 = result.confidence01.coerceAtMost(AMBIGUOUS_CONFIDENCE)
        return result
    }

    /** Direction is identified; a single depth is not, so yaw stays unresolved. */
    private fun publishMixedDepth(flow: BlockFlow, inverseTime: Double, usability01: Double): VisualEgo {
        var residualSquares = 0.0
        var weight = 0.0
        var used = 0
        for (i in 0 until flow.count) {
            if (consensus.keep[i] == 0.0) continue
            val radiusX = flow.x[i] - consensus.focusX
            val radiusY = flow.y[i] - consensus.focusY
            val radiusSquared = radiusX * radiusX + radiusY * radiusY
            if (radiusSquared <= 0.0) continue
            val horizontal = flow.dx[i] * inverseTime - consensus.medianScalePerSec * radiusX
            val vertical = flow.dy[i] * inverseTime - consensus.medianScalePerSec * radiusY
            val radial = (horizontal * radiusX + vertical * radiusY) / radiusSquared
            val tangentX = horizontal - radial * radiusX
            val tangentY = vertical - radial * radiusY
            val sampleWeight = baseWeights[i]
            residualSquares += sampleWeight * (tangentX * tangentX + tangentY * tangentY)
            weight += sampleWeight
            used++
        }
        if (used == 0 || weight == 0.0) return result
        val rmsPixels = sqrt(residualSquares / weight) * flow.deltaSeconds * flow.fPx
        val countFactor = if (used < MIN_INLIERS) 0.49 * used / MIN_INLIERS else (used / 8.0).coerceAtMost(1.0)
        result.expansionPerSec = consensus.medianScalePerSec
        result.foeX = consensus.focusX
        result.foeY = consensus.focusY
        result.hasFoe = abs(consensus.medianScalePerSec) >= MIN_EXPANSION_PER_SEC
        result.inliers = used
        result.confidence01 = (countFactor * (weight / used) * usability01.coerceIn(0.0, 1.0) *
            exp(-rmsPixels * rmsPixels / (RMS_SCALE_PIXELS * RMS_SCALE_PIXELS))).coerceIn(0.0, 1.0)
        return result
    }

    private fun yawSignificant(flow: BlockFlow, inverseTime: Double): Boolean =
        abs(coefficients[3]) > 3.0 * profileYawSigma(flow, inverseTime)

    /** Conditional model covariance; this cannot resolve depth/rotation ambiguity. */
    private fun profileYawSigma(flow: BlockFlow, inverseTime: Double): Double {
        var residualSquares = 0.0
        var observations = 0
        for (i in 0 until flow.count) {
            if (weights[i] == 0.0) continue
            val error = residual(flow, i, inverseTime)
            residualSquares += weights[i] * error * error
            observations += 2
        }
        if (observations <= 4 || !solve(4, inverseYaw = true)) return Double.POSITIVE_INFINITY
        val sigma = sqrt(residualSquares / (observations - 4) * inverseYawInformation)
        return if (sigma.isFinite()) sigma else Double.POSITIVE_INFINITY
    }

    private fun residual(flow: BlockFlow, i: Int, inverseTime: Double): Double {
        val x = flow.x[i]
        val y = flow.y[i]
        val ex = coefficients[0] * x + coefficients[1] - (1.0 + x * x) * coefficients[3] - flow.dx[i] * inverseTime
        val ey = coefficients[0] * y + coefficients[2] - x * y * coefficients[3] - flow.dy[i] * inverseTime
        return sqrt(ex * ex + ey * ey)
    }

    private fun addRow(a: Double, b: Double, c: Double, d: Double, observed: Double, weight: Double) {
        row[0] = a; row[1] = b; row[2] = c; row[3] = d
        for (i in 0..3) {
            val weighted = weight * row[i]
            for (j in 0..3) normal[i * 5 + j] += weighted * row[j]
            normal[i * 5 + 4] += weighted * observed
        }
    }

    /** Partial-pivot Gauss–Jordan; singular/non-finite systems fail closed. */
    private fun solve(dimensions: Int, inverseYaw: Boolean = false): Boolean {
        normal.copyInto(equations)
        if (inverseYaw) for (i in 0..3) equations[i * 5 + 4] = if (i == 3) 1.0 else 0.0
        var scale = 0.0
        for (i in 0 until dimensions) scale = maxOf(scale, abs(equations[i * 5 + i]))
        if (!scale.isFinite() || scale == 0.0) return false
        for (column in 0 until dimensions) {
            var pivot = column
            for (r in column + 1 until dimensions) if (abs(equations[r * 5 + column]) > abs(equations[pivot * 5 + column])) pivot = r
            if (abs(equations[pivot * 5 + column]) <= scale * 1e-10) return false
            if (pivot != column) {
                for (j in column..4) {
                    val value = equations[column * 5 + j]
                    equations[column * 5 + j] = equations[pivot * 5 + j]
                    equations[pivot * 5 + j] = value
                }
            }
            val divisor = equations[column * 5 + column]
            for (j in column..4) equations[column * 5 + j] /= divisor
            for (r in 0 until dimensions) {
                if (r == column) continue
                val factor = equations[r * 5 + column]
                for (j in column..4) equations[r * 5 + j] -= factor * equations[column * 5 + j]
            }
        }
        if (inverseYaw) {
            inverseYawInformation = equations[19]
            return inverseYawInformation.isFinite() && inverseYawInformation > 0.0
        }
        coefficients.fill(0.0)
        for (i in 0 until dimensions) {
            coefficients[i] = equations[i * 5 + 4]
            if (!coefficients[i].isFinite()) return false
        }
        return true
    }

    companion object {
        private const val CAPACITY = 12
        private const val IRLS_PASSES = 3
        private const val MIN_INLIERS = 6
        private const val MIN_EXPANSION_PER_SEC = 0.01
        private const val HUBER_PIXELS = 0.25
        private const val RMS_SCALE_PIXELS = 0.3
        private const val AMBIGUOUS_CONFIDENCE = 0.49
    }
}
