package app.simt.echo.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** Plan W10-03: the pure pieces of the Android frame tap (decimation, JPEG sizing). */
class FrameTapAnalyzerTest {

    /** Y plane whose byte at (x, y) is `(x + 3 * y) mod 251`, with row padding. */
    private fun plane(width: Int, height: Int, rowStride: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) buffer.put(y * rowStride + x, ((x + 3 * y) % 251).toByte())
        }
        return buffer
    }

    @Test
    fun decimationFillsExactlyFloorSizedPlaneFromARaggedFrame() {
        // 100 x 67 is not a multiple of 8: floor gives 12 x 8, ceil would be 13 x 9.
        val width = 100
        val height = 67
        val downW = width / 8
        val downH = height / 8
        // A sentinel past the plane proves nothing writes beyond downW * downH.
        val dst = ByteArray(downW * downH + 1) { 99 }

        decimateLuma(plane(width, height, rowStride = 112), 112, 1, downW, downH, 8, dst)

        val expected = ByteArray(downW * downH) { i ->
            val row = i / downW
            val col = i % downW
            ((col * 8 + 3 * row * 8) % 251).toByte()
        }
        assertArrayEquals(expected, dst.copyOf(downW * downH))
        assertEquals(99.toByte(), dst[downW * downH])
    }

    @Test
    fun decimationHonoursPixelStride() {
        // Interleaved plane (pixelStride 2): only every other byte is luma.
        val width = 16
        val height = 16
        val buffer = ByteBuffer.allocate(width * 2 * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(y * width * 2 + x * 2, (x + y).toByte())
                buffer.put(y * width * 2 + x * 2 + 1, 127)
            }
        }
        val dst = ByteArray(4)

        decimateLuma(buffer, width * 2, 2, 2, 2, 8, dst)

        assertArrayEquals(byteArrayOf(0, 8, 8, 16), dst)
    }

    @Test
    fun jpegTargetCapsTheLongSideAndKeepsAspect() {
        assertEquals(960 to 540, jpegTargetSize(1920, 1080, 960))
        assertEquals(540 to 960, jpegTargetSize(1080, 1920, 960))
    }

    @Test
    fun jpegTargetNeverUpscales() {
        assertEquals(640 to 480, jpegTargetSize(640, 480, 960))
        assertEquals(960 to 960, jpegTargetSize(960, 960, 960))
    }

    @Test
    fun jpegTargetNeverCollapsesBelowOnePixel() {
        assertEquals(1 to 960, jpegTargetSize(1, 5000, 960))
    }
}
