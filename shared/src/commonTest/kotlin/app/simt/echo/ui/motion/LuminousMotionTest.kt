package app.simt.echo.ui.motion

import androidx.compose.animation.core.SpringSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W13-02: spring tokens are exactly the luminous-freeze §3 table. */
class LuminousMotionTest {

    private fun assertSpring(spec: SpringSpec<Float>, damping: Float, stiffness: Float) {
        assertEquals(damping, spec.dampingRatio)
        assertEquals(stiffness, spec.stiffness)
    }

    @Test
    fun springTokensMatchTheFreeze() {
        assertSpring(LuminousSprings.spatialFast(), 0.90f, 1400f)
        assertSpring(LuminousSprings.spatialDefault(), 0.85f, 700f)
        assertSpring(LuminousSprings.spatialSlow(), 0.82f, 320f)
        assertSpring(LuminousSprings.spatialExpressive(), 0.55f, 420f)
        assertSpring(LuminousSprings.effectsFast(), 1f, 1600f)
        assertSpring(LuminousSprings.effectsDefault(), 1f, 800f)
    }

    @Test
    fun onlyTheExpressiveSpringOvershoots() {
        val springs = listOf(
            LuminousSprings.spatialFast<Float>(),
            LuminousSprings.spatialDefault(),
            LuminousSprings.spatialSlow(),
            LuminousSprings.effectsFast(),
            LuminousSprings.effectsDefault(),
        )
        assertTrue(springs.all { it.dampingRatio >= 0.8f }, "calm springs settle without a visible bounce")
        assertTrue(LuminousSprings.spatialExpressive<Float>().dampingRatio < 0.7f, "blooms are allowed to bounce")
    }

    @Test
    fun sigilKeyIsPerRoute() {
        assertEquals(sigilSharedKey("a"), sigilSharedKey("a"))
        assertTrue(sigilSharedKey("a") != sigilSharedKey("b"))
    }
}
