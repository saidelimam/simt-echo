package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One producer, fixed ping-pong 240x180 / 120x90 / 60x45 pyramids.
 * Positive camera yaw moves the image left; positive pitch moves it down.
 * SAD is centred on the pinhole rotational prior, never the unrotated block.
 * No frame, lambda, collection, or result is allocated by [onFrame].
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
    private var bestX = 0
    private var bestY = 0
    private var bestCost = Int.MAX_VALUE

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
        val oldFull = previousFull
        previousFull = currentFull
        currentFull = oldFull
        val old = previous
        previous = current
        current = old
        val oldCoarse = previousCoarse
        previousCoarse = currentCoarse
        currentCoarse = oldCoarse
        previousTimeNs = tNs
        previousIntrinsics = intrinsics
        ready = true
        return flow.count > 0
    }

    private fun match(intrinsics: CameraIntrinsics, yaw: Double, pitch: Double, roll: Double) {
        val focal = flow.fPx
        val cx = intrinsics.cx / 2.0
        val cy = intrinsics.cy / 2.0
        for (row in 0 until 3) {
            for (column in 0 until 4) {
                val left = ((column + 0.5) * previous.width / 4.0 - BLOCK_SIZE / 2.0).roundToInt()
                val top = ((row + 0.5) * previous.height / 3.0 - BLOCK_SIZE / 2.0).roundToInt()
                val texture = StructureTensor.minEigenvalue(previous.data, previous.width, previous.height, left, top, BLOCK_SIZE, BLOCK_SIZE)
                if (texture < VisualUsability.MIN_TEXTURE_EIGENVALUE) continue
                val x = (left + BLOCK_SIZE / 2.0 - cx) / focal
                val y = (top + BLOCK_SIZE / 2.0 - cy) / focal
                val priorX = focal * (-(1.0 + x * x) * yaw + x * y * pitch + y * roll)
                val priorY = focal * (-x * y * yaw + (1.0 + y * y) * pitch - x * roll)
                var centreX = priorX.roundToInt()
                var centreY = priorY.roundToInt()
                if (abs(priorX) > RADIUS || abs(priorY) > RADIUS) {
                    if (!search(previousCoarse, currentCoarse, left / 2, top / 2, BLOCK_SIZE / 2, (priorX / 2.0).roundToInt(), (priorY / 2.0).roundToInt())) continue
                    centreX = bestX * 2
                    centreY = bestY * 2
                }
                if (!search(previous, current, left, top, BLOCK_SIZE, centreX, centreY)) continue
                // A minimum on the search boundary is not a measured displacement.
                if (abs(bestX - centreX) == RADIUS || abs(bestY - centreY) == RADIUS) continue
                val offsetX = bestX
                val offsetY = bestY
                val cost = bestCost
                val targetTexture = StructureTensor.minEigenvalue(
                    current.data, current.width, current.height, left + offsetX, top + offsetY, BLOCK_SIZE, BLOCK_SIZE,
                )
                if (targetTexture < VisualUsability.MIN_TEXTURE_EIGENVALUE) continue
                val squareCost = patchCost(previous, current, left, top, BLOCK_SIZE, offsetX, offsetY, squared = true)
                val fineX = subpixel(
                    patchCost(previous, current, left, top, BLOCK_SIZE, offsetX - 1, offsetY, squared = true), squareCost,
                    patchCost(previous, current, left, top, BLOCK_SIZE, offsetX + 1, offsetY, squared = true),
                )
                val fineY = subpixel(
                    patchCost(previous, current, left, top, BLOCK_SIZE, offsetX, offsetY - 1, squared = true), squareCost,
                    patchCost(previous, current, left, top, BLOCK_SIZE, offsetX, offsetY + 1, squared = true),
                )
                // Refine in the original tap's detail, not an already box-filtered plane.
                if (!search(previousFull, currentFull, left * 2, top * 2, BLOCK_SIZE * 2,
                        ((offsetX + fineX) * 2).roundToInt(), ((offsetY + fineY) * 2).roundToInt(), radius = 1)) continue
                val fullX = bestX
                val fullY = bestY
                val fullCost = patchCost(previousFull, currentFull, left * 2, top * 2, BLOCK_SIZE * 2, fullX, fullY, squared = true)
                val fullFineX = subpixel(
                    patchCost(previousFull, currentFull, left * 2, top * 2, BLOCK_SIZE * 2, fullX - 1, fullY, squared = true), fullCost,
                    patchCost(previousFull, currentFull, left * 2, top * 2, BLOCK_SIZE * 2, fullX + 1, fullY, squared = true),
                )
                val fullFineY = subpixel(
                    patchCost(previousFull, currentFull, left * 2, top * 2, BLOCK_SIZE * 2, fullX, fullY - 1, squared = true), fullCost,
                    patchCost(previousFull, currentFull, left * 2, top * 2, BLOCK_SIZE * 2, fullX, fullY + 1, squared = true),
                )
                val at = flow.count++
                flow.x[at] = x
                flow.y[at] = y
                flow.dx[at] = ((fullX + fullFineX) / 2.0 - priorX) / focal
                flow.dy[at] = ((fullY + fullFineY) / 2.0 - priorY) / focal
                flow.texture[at] = minOf(texture, targetTexture)
                flow.quality[at] = (1.0 - cost.toDouble() / (BLOCK_SIZE * BLOCK_SIZE * 64.0)).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun search(reference: LumaView, target: LumaView, left: Int, top: Int, size: Int, centreX: Int, centreY: Int, radius: Int = RADIUS): Boolean {
        bestX = centreX
        bestY = centreY
        bestCost = sad(reference, target, left, top, size, centreX, centreY)
        for (dy in centreY - radius..centreY + radius) {
            for (dx in centreX - radius..centreX + radius) {
                val cost = sad(reference, target, left, top, size, dx, dy)
                if (cost < bestCost) {
                    bestCost = cost
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return bestCost != Int.MAX_VALUE
    }

    private fun sad(reference: LumaView, target: LumaView, left: Int, top: Int, size: Int, dx: Int, dy: Int): Int =
        patchCost(reference, target, left, top, size, dx, dy, squared = false)

    /** Squared costs have a quadratic minimum for the subpixel fit; SAD finds the integer basin. */
    private fun patchCost(reference: LumaView, target: LumaView, left: Int, top: Int, size: Int, dx: Int, dy: Int, squared: Boolean): Int {
        val tx = left + dx
        val ty = top + dy
        if (tx < 0 || ty < 0 || tx + size > target.width || ty + size > target.height) return Int.MAX_VALUE
        var sum = 0
        for (y in 0 until size) {
            var a = (top + y) * reference.width + left
            var b = (ty + y) * target.width + tx
            for (x in 0 until size) {
                val difference = (reference.data[a++].toInt() and 255) - (target.data[b++].toInt() and 255)
                sum += if (squared) difference * difference else abs(difference)
            }
        }
        return sum
    }

    private fun subpixel(negative: Int, centre: Int, positive: Int): Double {
        if (negative == Int.MAX_VALUE || positive == Int.MAX_VALUE) return 0.0
        val curvature = negative.toDouble() - 2.0 * centre + positive
        return if (curvature > 0.0) (0.5 * (negative - positive) / curvature).coerceIn(-0.5, 0.5) else 0.0
    }

    companion object {
        private const val BLOCK_SIZE = 16
        private const val RADIUS = 3
        private const val MAX_PAIR_NS = 2_000_000_000L
        private const val MAX_ROTATION_RAD = 0.15
    }
}
