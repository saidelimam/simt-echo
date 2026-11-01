package app.simt.echo.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W5-02 gate: overlay helpers are pure and deterministic — scrim lightens
 * near the turn, the crop slot never draws without bytes.
 */
class NavigateOverlayTest {

    @Test
    fun scrimIsLightestAtTheTurnAndDarkestFarAway() {
        val near = overlayScrimAlpha(0.0)
        val mid = overlayScrimAlpha(15.0)
        val far = overlayScrimAlpha(30.0)
        val beyond = overlayScrimAlpha(120.0)
        assertTrue(near < mid)
        assertTrue(mid < far)
        assertTrue(kotlin.math.abs(far - beyond) < 0.001f)
    }

    @Test
    fun scrimStaysInsideReadableBounds() {
        for (d in listOf(-5.0, 0.0, 5.0, 15.0, 29.9, 30.0, 200.0)) {
            val alpha = overlayScrimAlpha(d)
            assertTrue(alpha >= 0.35f, "alpha $alpha at $d below floor")
            assertTrue(alpha <= 0.65f, "alpha $alpha at $d above ceiling")
        }
    }

    @Test
    fun cropSlotNeedsBytes() {
        assertFalse(shouldShowLandmarkCrop(null))
        assertFalse(shouldShowLandmarkCrop(byteArrayOf()))
        assertTrue(shouldShowLandmarkCrop(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun maskPointsMapFromFrameSpaceIntoCropSpace() {
        // Real live-check geometry: mask raster 120x799 over a 720x1280
        // frame, SAM box [300,300,420,1100] within it.
        val box = listOf(300, 300, 420, 1100)
        // The mask point at the box's top-left corner maps to (0,0).
        val corner = maskPointInCrop(50f, 300f / 1280f * 799f, 120, 799, box, 720, 1280)
        assertTrue(kotlin.math.abs(corner!!.first - 0f) < 0.01f)
        assertTrue(kotlin.math.abs(corner.second - 0f) < 0.01f)
        // The box centre maps to (0.5, 0.5).
        val centre = maskPointInCrop(60f, 700f / 1280f * 799f, 120, 799, box, 720, 1280)
        assertTrue(kotlin.math.abs(centre!!.first - 0.5f) < 0.01f)
        assertTrue(kotlin.math.abs(centre.second - 0.5f) < 0.01f)
    }

    @Test
    fun maskPointDegenerateGeometryIsNull() {
        assertTrue(maskPointInCrop(1f, 1f, 0, 10, listOf(0, 0, 5, 5), 100, 100) == null)
        assertTrue(maskPointInCrop(1f, 1f, 10, 10, listOf(0, 0, 0, 5), 100, 100) == null)
        assertTrue(maskPointInCrop(1f, 1f, 10, 10, listOf(0, 0), 100, 100) == null)
    }
}
