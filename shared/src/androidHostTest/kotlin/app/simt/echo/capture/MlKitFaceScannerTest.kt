package app.simt.echo.capture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan W11-04: the decode bound. ML Kit's native model cannot load on the
 * host JVM, so the scan itself is device evidence; the part that guards
 * memory is pure and pinned here.
 */
class MlKitFaceScannerTest {
    @Test
    fun imagesWithinTheBoundDecodeAtFullSize() {
        assertEquals(1, sampleSizeFor(960, 720, 2048))
        assertEquals(1, sampleSizeFor(2048, 100, 2048))
    }

    @Test
    fun oversizedImagesShrinkByPowersOfTwoUntilTheLongSideFits() {
        assertEquals(2, sampleSizeFor(4000, 3000, 2048))
        assertEquals(4, sampleSizeFor(8000, 6000, 2048))
        // The long side decides, whichever axis it is.
        assertEquals(2, sampleSizeFor(3000, 4000, 2048))
    }

    @Test
    fun aFailedBoundsDecodeFallsThroughToTheRealDecode() {
        assertEquals(1, sampleSizeFor(-1, -1, 2048))
        assertEquals(1, sampleSizeFor(0, 0, 2048))
    }
}
