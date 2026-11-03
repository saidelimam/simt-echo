package app.simt.echo.capture.visual

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Fixed storage owned by the flow kernel, not a retained FrameSample plane. */
class LumaView(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
    }

    val data = ByteArray(width * height)

    fun reduceFrom(source: ByteArray, sourceWidth: Int) {
        require(sourceWidth == width * 2 && source.size == width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = y * 2 * sourceWidth + x * 2
                data[y * width + x] = (((source[at].toInt() and 255) + (source[at + 1].toInt() and 255) +
                    (source[at + sourceWidth].toInt() and 255) + (source[at + sourceWidth + 1].toInt() and 255) + 2) / 4).toByte()
            }
        }
    }

    /** Global gain/offset cancellation before integer SAD; no second image. */
    fun normalise() {
        var sum = 0.0
        var squares = 0.0
        for (pixel in data) {
            val value = pixel.toInt() and 255
            sum += value
            squares += value.toDouble() * value
        }
        val mean = sum / data.size
        val sigma = sqrt((squares / data.size - mean * mean).coerceAtLeast(1.0))
        for (i in data.indices) {
            data[i] = (((data[i].toInt() and 255) - mean) * 32.0 / sigma + 128.0).roundToInt().coerceIn(0, 255).toByte()
        }
    }
}
