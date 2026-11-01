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
}
