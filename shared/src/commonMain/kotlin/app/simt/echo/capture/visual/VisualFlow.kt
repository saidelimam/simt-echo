package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One producer, fixed ping-pong 240x180 / 120x90 / 60x45 pyramids.
 * Positive camera yaw moves the image left; positive pitch moves it down.
 * Each block is warped by the finite pinhole rotation, then SAD searches a
 * ±3 px fine window. When the fine window lacks an interior minimum, bounded
 * radial expansion steps cover 0.9–1.1 m/s walking at 8 Hz Record and 2 Hz
 * Navigate spacings. Full-resolution refinement scores both the 32×32 patch
 * and its central 16×16 window from one warp, selecting the smaller window
 * when oblique depth variation biases the 32×32 mean and dropping blocks
 * that clip the frame border or straddle a surface crease. It does not claim
 * 1 Hz duty. No frame, lambda, collection, or result is allocated by [onFrame].
 */
class VisualFlow(private val width: Int = 240, private val height: Int = 180) {
    init {
        require(width >= 160 && height >= 120 && width % 4 == 0 && height % 4 == 0)
    }

    val flow = BlockFlow()
    private var previousFull = LumaView(width, height)
    private var currentFull = LumaView(width, height)
    private var previous = LumaView(width / 2, height / 2)
    private var current = LumaView(width / 2, height / 2)
    private var previousCoarse = LumaView(width / 4, height / 4)
    private var currentCoarse = LumaView(width / 4, height / 4)
    private var ready = false
    private var previousTimeNs = 0L
    private var previousIntrinsics: CameraIntrinsics? = null
    private val rotation = PinholeRotation()
    private val scratch = ByteArray(MAX_BLOCK * MAX_BLOCK)
    private val blockLeft = IntArray(BLOCK_CAPACITY)
    private val blockTop = IntArray(BLOCK_CAPACITY)
    private val blockX = DoubleArray(BLOCK_CAPACITY)
    private val blockY = DoubleArray(BLOCK_CAPACITY)
    private val rotatedX = DoubleArray(BLOCK_CAPACITY)
    private val rotatedY = DoubleArray(BLOCK_CAPACITY)
    private val priorX = DoubleArray(BLOCK_CAPACITY)
    private val priorY = DoubleArray(BLOCK_CAPACITY)
    private val originX = IntArray(BLOCK_CAPACITY)
    private val originY = IntArray(BLOCK_CAPACITY)
    private val blockTexture = DoubleArray(BLOCK_CAPACITY)
    private val fineValid = BooleanArray(BLOCK_CAPACITY)
    private val offsetX = IntArray(BLOCK_CAPACITY)
    private val offsetY = IntArray(BLOCK_CAPACITY)
    private val blockCost = IntArray(BLOCK_CAPACITY)
    private val expansions = DoubleArray(BLOCK_CAPACITY)
    private var bestX = 0
    private var bestY = 0
    private var bestCost = Int.MAX_VALUE
    private var refinedX = 0.0
    private var refinedY = 0.0

    fun reset() {
        ready = false
        flow.count = 0
        flow.deltaSeconds = 0.0
    }

    fun onFrame(
        luma: ByteArray,
        tNs: Long,
        intrinsics: CameraIntrinsics,
        deltaYawRad: Double = 0.0,
        deltaPitchRad: Double = 0.0,
        deltaRollRad: Double = 0.0,
        clockAligned: Boolean = true,
    ): Boolean {
        flow.count = 0
        flow.deltaSeconds = 0.0
        if (!clockAligned || luma.size.toLong() != width.toLong() * height) {
            reset()
            return false
        }
        luma.copyInto(currentFull.data)
        currentFull.normalise()
        current.reduceFrom(currentFull.data, width)
        currentCoarse.reduceFrom(current.data, current.width)
        val elapsed = tNs - previousTimeNs
        val usablePair = ready && intrinsics == previousIntrinsics && elapsed in 1..MAX_PAIR_NS &&
            deltaYawRad.isFinite() && deltaPitchRad.isFinite() && deltaRollRad.isFinite() &&
            abs(deltaYawRad) <= MAX_ROTATION_RAD && abs(deltaPitchRad) <= MAX_ROTATION_RAD && abs(deltaRollRad) <= MAX_ROTATION_RAD
        flow.fPx = intrinsics.fPx / 2.0
        if (usablePair) {
            flow.deltaSeconds = elapsed / 1e9
            match(intrinsics, deltaYawRad, deltaPitchRad, deltaRollRad)
        }
        previousFull = currentFull.also { currentFull = previousFull }
        previous = current.also { current = previous }
        previousCoarse = currentCoarse.also { currentCoarse = previousCoarse }
        previousTimeNs = tNs
        previousIntrinsics = intrinsics
        ready = true
        return flow.count > 0
    }

    private fun match(intrinsics: CameraIntrinsics, yaw: Double, pitch: Double, roll: Double) {
        val focal = flow.fPx
        val cx = intrinsics.cx / 2.0
        val cy = intrinsics.cy / 2.0
        if (!rotationSupported(yaw, pitch, roll, focal, previous.width, previous.height, cx, cy)) return
        var expansionSamples = 0
        var needExpansion = false
        for (row in 0 until 3) {
            for (column in 0 until 4) {
                val index = row * 4 + column
                fineValid[index] = false
                blockTexture[index] = 0.0
                val left = ((column + 0.5) * previous.width / 4.0 - BLOCK_SIZE / 2.0).roundToInt()
                val top = ((row + 0.5) * previous.height / 3.0 - BLOCK_SIZE / 2.0).roundToInt()
                blockLeft[index] = left
                blockTop[index] = top
                val texture = StructureTensor.minEigenvalue(previous.data, previous.width, previous.height, left, top, BLOCK_SIZE, BLOCK_SIZE)
                if (texture < VisualUsability.MIN_TEXTURE_EIGENVALUE) continue
                val x = (left + BLOCK_SIZE / 2.0 - cx) / focal
                val y = (top + BLOCK_SIZE / 2.0 - cy) / focal
                if (!rotation.map(x, y, yaw, pitch, roll, inverse = false)) continue
                blockX[index] = x
                blockY[index] = y
                rotatedX[index] = rotation.x
                rotatedY[index] = rotation.y
                priorX[index] = focal * (rotation.x - x)
                priorY[index] = focal * (rotation.y - y)
                val ox = left + priorX[index].roundToInt()
                val oy = top + priorY[index].roundToInt()
                originX[index] = ox
                originY[index] = oy
                if (!inFrame(current, ox, oy, BLOCK_SIZE)) continue
                val shiftX = left + priorX[index] - ox
                val shiftY = top + priorY[index] - oy
                if (!warp(previous, ox, oy, BLOCK_SIZE, yaw, pitch, roll, focal, cx, cy, rotation.x, rotation.y, 1.0, shiftX, shiftY)) continue
                blockTexture[index] = texture
                if (searchResidual(current, ox, oy, BLOCK_SIZE, 0, 0, RADIUS, interior = true)) {
                    fineValid[index] = true
                    offsetX[index] = bestX
                    offsetY[index] = bestY
                    blockCost[index] = bestCost
                    val rx = x * focal
                    val ry = y * focal
                    val exp = (rx * bestX + ry * bestY) / (rx * rx + ry * ry)
                    if (exp >= MIN_GLOBAL_EXPANSION && (abs(bestX) >= 2 || abs(bestY) >= 2)) expansions[expansionSamples++] = exp
                    if (abs(bestX) == RADIUS - 1 || abs(bestY) == RADIUS - 1) needExpansion = true
                } else {
                    blockCost[index] = Int.MAX_VALUE
                    needExpansion = true
                }
            }
        }
        val frameScale = if (needExpansion) resolveFrameScale(expansionSamples, yaw, pitch, roll, focal, cx, cy) else 1.0
        val fullFocal = focal * 2.0
        for (index in 0 until BLOCK_CAPACITY) {
            if (!fineValid[index]) continue
            val left = blockLeft[index]
            val top = blockTop[index]
            val ox = originX[index]
            val oy = originY[index]
            val bestDx = offsetX[index]
            val bestDy = offsetY[index]
            val cost = blockCost[index]
            val rx = blockX[index] * focal
            val ry = blockY[index] * focal
            val radiusSquared = rx * rx + ry * ry
            if (frameScale > 1.0) {
                val predictedLeft = ox + ((frameScale - 1.0) * rx).roundToInt()
                if (predictedLeft < 0 || predictedLeft + BLOCK_SIZE > current.width) continue
            }
            val tensorTop = if (frameScale > 1.0) maxOf(1, oy + bestDy) else oy + bestDy
            val tensorBottom = if (frameScale > 1.0) minOf(current.height - 1, oy + bestDy + BLOCK_SIZE) else oy + bestDy + BLOCK_SIZE
            val targetTexture = StructureTensor.minEigenvalue(
                current.data, current.width, current.height, ox + bestDx, tensorTop, BLOCK_SIZE, tensorBottom - tensorTop,
            )
            if (targetTexture < VisualUsability.MIN_TEXTURE_EIGENVALUE) continue
            val shiftX = left + priorX[index] - ox
            val shiftY = top + priorY[index] - oy
            val initialScale = if (frameScale > 1.0) (1.0 + (rx * (bestDx - shiftX) + ry * (bestDy - shiftY)) / radiusSquared).coerceIn(1.0, MAX_EXPANSION_SCALE) else 1.0
            if (!warp(previous, ox, oy, BLOCK_SIZE, yaw, pitch, roll, focal, cx, cy, rotatedX[index], rotatedY[index], initialScale, shiftX, shiftY)) continue
            val squareCost = warpedCost(current, ox, oy, BLOCK_SIZE, bestDx, bestDy, squared = true)
            val fineX = subpixel(
                warpedCost(current, ox, oy, BLOCK_SIZE, bestDx - 1, bestDy, squared = true), squareCost,
                warpedCost(current, ox, oy, BLOCK_SIZE, bestDx + 1, bestDy, squared = true), rejectBoundary = false,
            )
            val fineY = subpixel(
                warpedCost(current, ox, oy, BLOCK_SIZE, bestDx, bestDy - 1, squared = true), squareCost,
                warpedCost(current, ox, oy, BLOCK_SIZE, bestDx, bestDy + 1, squared = true), rejectBoundary = false,
            )
            val halfClamped = frameScale > 1.0 && (abs(fineX) == 0.5 || abs(fineY) == 0.5)
            val resHalfX = ox + bestDx + fineX - left - priorX[index]
            val resHalfY = oy + bestDy + fineY - top - priorY[index]
            val fullScale = if (frameScale > 1.0) (1.0 + (rx * resHalfX + ry * resHalfY) / radiusSquared).coerceIn(1.0, MAX_EXPANSION_SCALE) else 1.0
            val fullOriginX = left * 2 + (priorX[index] * 2.0).roundToInt()
            val fullOriginY = top * 2 + (priorY[index] * 2.0).roundToInt()
            val fullCentreX = (2 * (ox + bestDx) - fullOriginX + fineX * 2.0).roundToInt()
            val fullCentreY = (2 * (oy + bestDy) - fullOriginY + fineY * 2.0).roundToInt()
            val requiredInset = if (frameScale > 1.0 && abs(rx) < BLOCK_SIZE * 2) CENTRAL_INSET else 0
            if (!inFrame(currentFull, fullOriginX + fullCentreX + requiredInset, fullOriginY + fullCentreY + requiredInset, MAX_BLOCK - 2 * requiredInset)) continue
            val fullShiftX = left * 2 + priorX[index] * 2.0 - fullOriginX
            val fullShiftY = top * 2 + priorY[index] * 2.0 - fullOriginY
            if (!warp(previousFull, fullOriginX, fullOriginY, MAX_BLOCK, yaw, pitch, roll, fullFocal, intrinsics.cx, intrinsics.cy, rotatedX[index], rotatedY[index], fullScale, fullShiftX, fullShiftY)) continue
            val valid32 = refineWindow(fullOriginX, fullOriginY, fullCentreX, fullCentreY, inset = 0, rejectBoundary = frameScale > 1.0)
            val e32X = refinedX - left - priorX[index]
            val e32Y = refinedY - top - priorY[index]
            val valid16 = refineWindow(fullOriginX, fullOriginY, fullCentreX, fullCentreY, inset = CENTRAL_INSET, rejectBoundary = true)
            val e16X = refinedX - left - priorX[index]
            val e16Y = refinedY - top - priorY[index]
            if (!valid32 && !(valid16 && frameScale > 1.0 && abs(rx) < BLOCK_SIZE * 2)) continue
            val d = if (valid32 && valid16) hypot(e32X - e16X, e32Y - e16Y) else 0.0
            if (halfClamped && (!valid16 || d > MIN_BIAS_SHIFT_PX)) continue
            if (frameScale > 1.0 && valid32 && valid16 && d > MAX_WINDOW_DISAGREEMENT_PX) continue
            val cornerBlock = abs(rx) > BLOCK_SIZE * 2 && abs(ry) > BLOCK_SIZE
            val useCentral = !valid32 || (cornerBlock && valid16 && hypot(e32X, e32Y) >= MIN_BIAS_FLOW_PX && d in MIN_BIAS_SHIFT_PX..MAX_CENTRE_WINDOW_SHIFT_PX)
            val chosenX = if (useCentral) e16X else e32X
            val chosenY = if (useCentral) e16Y else e32Y
            val at = flow.count++
            flow.x[at] = blockX[index]
            flow.y[at] = blockY[index]
            flow.dx[at] = chosenX / focal
            flow.dy[at] = chosenY / focal
            flow.texture[at] = minOf(blockTexture[index], targetTexture)
            flow.quality[at] = (1.0 - cost.toDouble() / (BLOCK_SIZE * BLOCK_SIZE * 64.0)).coerceIn(0.0, 1.0)
        }
    }

    private fun refineWindow(fullOriginX: Int, fullOriginY: Int, fullCentreX: Int, fullCentreY: Int, inset: Int, rejectBoundary: Boolean): Boolean {
        if (!searchResidual(currentFull, fullOriginX, fullOriginY, MAX_BLOCK, fullCentreX, fullCentreY, radius = 1, interior = false, inset = inset)) return false
        val fullX = bestX
        val fullY = bestY
        val fullCost = warpedCost(currentFull, fullOriginX, fullOriginY, MAX_BLOCK, fullX, fullY, squared = true, inset = inset)
        val fullFineX = subpixel(
            warpedCost(currentFull, fullOriginX, fullOriginY, MAX_BLOCK, fullX - 1, fullY, squared = true, inset = inset), fullCost,
            warpedCost(currentFull, fullOriginX, fullOriginY, MAX_BLOCK, fullX + 1, fullY, squared = true, inset = inset), rejectBoundary = rejectBoundary,
        )
        val fullFineY = subpixel(
            warpedCost(currentFull, fullOriginX, fullOriginY, MAX_BLOCK, fullX, fullY - 1, squared = true, inset = inset), fullCost,
            warpedCost(currentFull, fullOriginX, fullOriginY, MAX_BLOCK, fullX, fullY + 1, squared = true, inset = inset), rejectBoundary = rejectBoundary,
        )
        if (!fullFineX.isFinite() || !fullFineY.isFinite()) return false
        refinedX = (fullOriginX + fullX + fullFineX) / 2.0
        refinedY = (fullOriginY + fullY + fullFineY) / 2.0
        return true
    }

    private fun resolveFrameScale(samples: Int, yaw: Double, pitch: Double, roll: Double, focal: Double, cx: Double, cy: Double): Double {
        var count = samples
        var unscaledFailed = 0
        for (index in 0 until BLOCK_CAPACITY) {
            if (blockTexture[index] < VisualUsability.MIN_TEXTURE_EIGENVALUE) continue
            if (!fineValid[index]) unscaledFailed++
            if (fineValid[index] && abs(offsetX[index]) < 2 && abs(offsetY[index]) < 2) continue
            val ox = originX[index]
            val oy = originY[index]
            val shiftX = blockLeft[index] + priorX[index] - ox
            val shiftY = blockTop[index] + priorY[index] - oy
            val rx = blockX[index] * focal
            val ry = blockY[index] * focal
            val radiusSquared = rx * rx + ry * ry
            var bestStepCost = if (fineValid[index]) blockCost[index] else Int.MAX_VALUE
            var bestDx = offsetX[index]
            var bestDy = offsetY[index]
            var matched = fineValid[index]
            for (step in 1..EXPANSION_STEPS) {
                val expansion = step * EXPANSION_STEP
                val predictedX = (expansion * rx).roundToInt()
                val predictedY = (expansion * ry).roundToInt()
                if (!inFrame(current, ox + predictedX, oy + predictedY, BLOCK_SIZE)) continue
                if (!warp(previous, ox, oy, BLOCK_SIZE, yaw, pitch, roll, focal, cx, cy, rotatedX[index], rotatedY[index], 1.0 + expansion, shiftX, shiftY)) continue
                if (searchResidual(current, ox, oy, BLOCK_SIZE, predictedX, predictedY, RADIUS, interior = true) && bestCost < bestStepCost) {
                    bestStepCost = bestCost
                    bestDx = bestX
                    bestDy = bestY
                    matched = true
                }
            }
            if (matched && (!fineValid[index] || unscaledFailed > 0)) {
                fineValid[index] = true
                offsetX[index] = bestDx
                offsetY[index] = bestDy
                blockCost[index] = bestStepCost
                val exp = (rx * bestDx + ry * bestDy) / radiusSquared
                if (exp > MIN_GLOBAL_EXPANSION && count < BLOCK_CAPACITY) expansions[count++] = exp
            }
        }
        if (unscaledFailed == 0 && count < 6) return 1.0
        if (count < 2) return 1.0
        for (i in 1 until count) {
            val value = expansions[i]
            var j = i - 1
            while (j >= 0 && expansions[j] > value) {
                expansions[j + 1] = expansions[j]
                j--
            }
            expansions[j + 1] = value
        }
        val median = if (count and 1 == 1) expansions[count / 2] else 0.5 * (expansions[count / 2 - 1] + expansions[count / 2])
        return if (median >= MIN_GLOBAL_EXPANSION) (1.0 + median).coerceIn(1.0, MAX_EXPANSION_SCALE) else 1.0
    }

    private fun inFrame(view: LumaView, left: Int, top: Int, size: Int): Boolean =
        left >= 0 && top >= 0 && left + size <= view.width && top + size <= view.height

    /** Corner rays that rotate behind the camera make the pair unsupported. */
    private fun rotationSupported(yaw: Double, pitch: Double, roll: Double, focal: Double, frameWidth: Int, frameHeight: Int, cx: Double, cy: Double): Boolean {
        val horizontal = maxOf(cx, frameWidth - 1.0 - cx) / focal
        val vertical = maxOf(cy, frameHeight - 1.0 - cy) / focal
        return rotation.map(horizontal, vertical, yaw, pitch, roll, inverse = false) &&
            rotation.map(horizontal, -vertical, yaw, pitch, roll, inverse = false) &&
            rotation.map(-horizontal, vertical, yaw, pitch, roll, inverse = false) &&
            rotation.map(-horizontal, -vertical, yaw, pitch, roll, inverse = false)
    }

    private fun warp(
        reference: LumaView, originX: Int, originY: Int, size: Int, yaw: Double, pitch: Double, roll: Double,
        focal: Double, cx: Double, cy: Double, rotatedX: Double, rotatedY: Double, scale: Double,
        priorShiftX: Double = 0.0, priorShiftY: Double = 0.0,
    ): Boolean {
        var at = 0
        for (row in 0 until size) {
            val pixelY = originY + row + priorShiftY
            val ny = rotatedY + ((pixelY + 0.5 - cy) / focal - rotatedY) / scale - 0.5 / focal
            for (column in 0 until size) {
                val pixelX = originX + column + priorShiftX
                val nx = rotatedX + ((pixelX + 0.5 - cx) / focal - rotatedX) / scale - 0.5 / focal
                if (!rotation.map(nx, ny, yaw, pitch, roll, inverse = true)) return false
                val sample = bilinear(reference, rotation.x * focal + cx, rotation.y * focal + cy)
                if (sample < 0) return false
                scratch[at++] = sample.toByte()
            }
        }
        return true
    }

    private fun bilinear(view: LumaView, x: Double, y: Double): Int {
        if (!x.isFinite() || !y.isFinite() || x < 0.0 || y < 0.0 || x > view.width - 1.0 || y > view.height - 1.0) return -1
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = if (x0 + 1 < view.width) x0 + 1 else x0
        val y1 = if (y0 + 1 < view.height) y0 + 1 else y0
        val tx = x - x0
        val ty = y - y0
        val row0 = y0 * view.width
        val row1 = y1 * view.width
        val top = (view.data[row0 + x0].toInt() and 255) * (1.0 - tx) + (view.data[row0 + x1].toInt() and 255) * tx
        val bottom = (view.data[row1 + x0].toInt() and 255) * (1.0 - tx) + (view.data[row1 + x1].toInt() and 255) * tx
        return (top * (1.0 - ty) + bottom * ty).roundToInt().coerceIn(0, 255)
    }

    /** [bestX] and [bestY] are residual pixels relative to the rotational origin. */
    private fun searchResidual(target: LumaView, originX: Int, originY: Int, size: Int, centreX: Int, centreY: Int, radius: Int, interior: Boolean, inset: Int = 0): Boolean {
        bestX = centreX
        bestY = centreY
        bestCost = warpedCost(target, originX, originY, size, centreX, centreY, squared = false, inset = inset)
        for (dy in centreY - radius..centreY + radius) {
            for (dx in centreX - radius..centreX + radius) {
                val cost = warpedCost(target, originX, originY, size, dx, dy, squared = false, inset = inset)
                if (cost < bestCost) {
                    bestCost = cost
                    bestX = dx
                    bestY = dy
                }
            }
        }
        if (bestCost == Int.MAX_VALUE) return false
        if (interior && (abs(bestX - centreX) == radius || abs(bestY - centreY) == radius)) return false
        return true
    }

    /** Squared costs have a quadratic minimum for the subpixel fit; SAD finds the integer basin. */
    private fun warpedCost(target: LumaView, originX: Int, originY: Int, size: Int, dx: Int, dy: Int, squared: Boolean, inset: Int = 0): Int {
        val tx = originX + dx
        val ty = originY + dy
        if (tx + inset < 0 || ty + inset < 0 || tx + size - inset > target.width || ty + size - inset > target.height) return Int.MAX_VALUE
        var sum = 0
        for (y in inset until size - inset) {
            var sample = y * size + inset
            var targetAt = (ty + y) * target.width + tx + inset
            for (x in inset until size - inset) {
                val difference = (scratch[sample++].toInt() and 255) - (target.data[targetAt++].toInt() and 255)
                sum += if (squared) difference * difference else abs(difference)
            }
        }
        return sum
    }

    private fun subpixel(negative: Int, centre: Int, positive: Int, rejectBoundary: Boolean): Double {
        if (negative == Int.MAX_VALUE || positive == Int.MAX_VALUE) return 0.0
        val curvature = negative.toDouble() - 2.0 * centre + positive
        if (curvature <= 0.0) return 0.0
        val raw = (0.5 * (negative - positive) / curvature).coerceIn(-0.5, 0.5)
        return if (rejectBoundary && abs(raw) == 0.5) Double.NaN else raw
    }

    companion object {
        private const val BLOCK_CAPACITY = 12
        private const val BLOCK_SIZE = 16
        private const val MAX_BLOCK = BLOCK_SIZE * 2
        private const val CENTRAL_INSET = BLOCK_SIZE / 2
        private const val RADIUS = 3
        private const val EXPANSION_STEPS = 7
        private const val EXPANSION_STEP = 0.04
        private const val MIN_GLOBAL_EXPANSION = 0.06
        private const val MAX_EXPANSION_SCALE = 1.35
        private const val MIN_BIAS_FLOW_PX = 1.5
        private const val MIN_BIAS_SHIFT_PX = 0.05
        private const val MAX_CENTRE_WINDOW_SHIFT_PX = 0.15
        private const val MAX_WINDOW_DISAGREEMENT_PX = 0.35
        private const val MAX_PAIR_NS = 2_000_000_000L
        private const val MAX_ROTATION_RAD = 0.15
    }
}
