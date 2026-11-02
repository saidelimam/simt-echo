package app.simt.echo.capture

import kotlin.math.PI
import kotlin.math.tan

enum class IntrinsicsSource { CHARACTERISTICS, DEFAULT }

/** Approximate pinhole model in the delivered decimated luma coordinates. */
data class CameraIntrinsics(val fPx: Double, val cx: Double, val cy: Double, val source: IntrinsicsSource) {
    init {
        require(fPx.isFinite() && fPx > 0.0 && cx.isFinite() && cy.isFinite())
    }

    companion object {
        /** Labelled 65 degree horizontal FOV, not a calibration claim. */
        fun defaultFor(width: Int, height: Int): CameraIntrinsics {
            require(width > 0 && height > 0)
            return CameraIntrinsics(width / (2.0 * tan(65.0 * PI / 360.0)), (width - 1) / 2.0, (height - 1) / 2.0, IntrinsicsSource.DEFAULT)
        }
    }
}

/** Session metadata read once from the selected Camera2 camera, not per frame. */
data class CameraOptics(
    val focalLengthMm: Double,
    val sensorWidthMm: Double,
    val activeWidthPx: Int,
    val activeHeightPx: Int,
) {
    /** Approximate centred crop to the analysis aspect ratio, with square pixels. */
    fun forPlane(width: Int, height: Int): CameraIntrinsics {
        require(width > 0 && height > 0)
        if (!focalLengthMm.isFinite() || focalLengthMm <= 0.0 || !sensorWidthMm.isFinite() ||
            sensorWidthMm <= 0.0 || activeWidthPx <= 0 || activeHeightPx <= 0
        ) return CameraIntrinsics.defaultFor(width, height)
        val scale = maxOf(width.toDouble() / activeWidthPx, height.toDouble() / activeHeightPx)
        return CameraIntrinsics(
            focalLengthMm / sensorWidthMm * activeWidthPx * scale,
            (width - 1) / 2.0,
            (height - 1) / 2.0,
            IntrinsicsSource.CHARACTERISTICS,
        )
    }
}