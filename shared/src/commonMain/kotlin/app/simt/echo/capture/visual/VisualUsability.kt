package app.simt.echo.capture.visual

import app.simt.echo.capture.FrameQuality

enum class VisualReason { NONE, FLAT, BLANK_WALL, BLUR, DARK, GLARE, POCKET, SWINGING, EAR, UNKNOWN }

/** Reused output; one analysis thread owns it. No samples or pixels retained. */
class VisualUsability {
    var value01 = 0.0
        private set
    var reason = VisualReason.UNKNOWN
        private set
    var textureEigenvalue = 0.0
        private set
    var sharpnessFactor01 = 0.0
        private set
    var exposureFraction01 = 0.0
        private set
    var stabilityFactor01 = 0.0
        private set

    fun evaluate(luma: ByteArray, width: Int, height: Int, pose: PoseMode, angularRateRadPerSec: Double): Double {
        value01 = 0.0
        reason = VisualReason.UNKNOWN
        textureEigenvalue = 0.0
        sharpnessFactor01 = 0.0
        exposureFraction01 = 0.0
        stabilityFactor01 = 0.0
        if (width < 4 || height < 4 || width.toLong() * height != luma.size.toLong() || !angularRateRadPerSec.isFinite()) return value01
        if (pose != PoseMode.HELD_UP) {
            reason = when (pose) {
                PoseMode.FLAT -> VisualReason.FLAT
                PoseMode.POCKET -> VisualReason.POCKET
                PoseMode.SWINGING -> VisualReason.SWINGING
                PoseMode.EAR -> VisualReason.EAR
                else -> VisualReason.UNKNOWN
            }
            return value01
        }
        exposureFraction01 = FrameQuality.exposureFraction(luma)
        sharpnessFactor01 = (FrameQuality.sharpness(luma, width, height) / SHARPNESS_FULL).coerceIn(0.0, 1.0)
        stabilityFactor01 = FrameQuality.stability(angularRateRadPerSec)
        textureEigenvalue = StructureTensor.minEigenvalue(luma, width, height)
        val textureFactor = (textureEigenvalue / TEXTURE_FULL).coerceIn(0.0, 1.0)
        value01 = sharpnessFactor01 * exposureFraction01 * textureFactor * stabilityFactor01
        reason = when {
            exposureFraction01 < 0.5 -> if (meanLuma(luma) >= 128.0) VisualReason.GLARE else VisualReason.DARK
            textureEigenvalue < MIN_TEXTURE_EIGENVALUE -> VisualReason.BLANK_WALL
            sharpnessFactor01 < 0.3 || stabilityFactor01 < 0.5 -> VisualReason.BLUR
            else -> VisualReason.NONE
        }
        return value01
    }

    companion object {
        const val MIN_TEXTURE_EIGENVALUE = 4.0
        private const val TEXTURE_FULL = 64.0
        private const val SHARPNESS_FULL = 256.0

        fun meanLuma(luma: ByteArray): Double {
            if (luma.isEmpty()) return 0.0
            var sum = 0L
            for (value in luma) sum += value.toInt() and 255
            return sum.toDouble() / luma.size
        }
    }
}
