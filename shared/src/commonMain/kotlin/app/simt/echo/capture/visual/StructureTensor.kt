package app.simt.echo.capture.visual

import kotlin.math.sqrt

/** Mean 2x2 gradient tensor; eigenvalues are squared luma units per pixel. */
object StructureTensor {
    fun minEigenvalue(
        luma: ByteArray,
        width: Int,
        height: Int,
        left: Int = width / 4,
        top: Int = height / 4,
        regionWidth: Int = width / 2,
        regionHeight: Int = height / 2,
    ): Double {
        if (width < 3 || height < 3 || width.toLong() * height > luma.size || left < 1 || top < 1 ||
            regionWidth <= 0 || regionHeight <= 0 || left.toLong() + regionWidth >= width || top.toLong() + regionHeight >= height
        ) return 0.0
        var xx = 0.0
        var xy = 0.0
        var yy = 0.0
        for (y in top until top + regionHeight) {
            for (x in left until left + regionWidth) {
                val at = y * width + x
                val gx = ((luma[at + 1].toInt() and 255) - (luma[at - 1].toInt() and 255)) * 0.5
                val gy = ((luma[at + width].toInt() and 255) - (luma[at - width].toInt() and 255)) * 0.5
                xx += gx * gx
                xy += gx * gy
                yy += gy * gy
            }
        }
        val count = regionWidth.toDouble() * regionHeight
        xx /= count
        xy /= count
        yy /= count
        return ((xx + yy - sqrt((xx - yy) * (xx - yy) + 4.0 * xy * xy)) * 0.5).coerceAtLeast(0.0)
    }
}
