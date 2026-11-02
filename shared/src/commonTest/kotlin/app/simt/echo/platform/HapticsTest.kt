package app.simt.echo.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan W13-09 acceptance: pattern per cue, plus waveform fallback when
 * primitives are unsupported. Asserts the freeze §7 grammar verbatim.
 */
class HapticsTest {

    @Test
    fun tickIsSingleTick() {
        assertEquals(
            listOf(PrimitiveStep(HapticPrimitive.TICK, 0.6f)),
            HapticCueMap.composition(HapticPattern.TICK),
        )
    }

    @Test
    fun confirmIsDoubleClick() {
        assertEquals(
            listOf(
                PrimitiveStep(HapticPrimitive.CLICK, 0.8f),
                PrimitiveStep(HapticPrimitive.CLICK, 0.5f, delayMs = 60L),
            ),
            HapticCueMap.composition(HapticPattern.CONFIRM),
        )
    }

    @Test
    fun leftIsRiseThenKnock() {
        assertEquals(
            listOf(
                PrimitiveStep(HapticPrimitive.QUICK_RISE, 0.6f),
                PrimitiveStep(HapticPrimitive.CLICK, 1.0f),
            ),
            HapticCueMap.composition(HapticPattern.LEFT),
        )
    }

    @Test
    fun rightIsKnockKnockRising() {
        assertEquals(
            listOf(
                PrimitiveStep(HapticPrimitive.CLICK, 0.8f),
                PrimitiveStep(HapticPrimitive.CLICK, 0.8f, delayMs = 60L),
                PrimitiveStep(HapticPrimitive.QUICK_RISE, 0.6f),
            ),
            HapticCueMap.composition(HapticPattern.RIGHT),
        )
    }

    @Test
    fun uTurnIsHeavyTurnAround() {
        assertEquals(
            listOf(
                PrimitiveStep(HapticPrimitive.SLOW_RISE, 0.8f),
                PrimitiveStep(HapticPrimitive.THUD, 1.0f),
            ),
            HapticCueMap.composition(HapticPattern.U_TURN),
        )
    }

    @Test
    fun warnIsDoubleThud() {
        assertEquals(
            listOf(
                PrimitiveStep(HapticPrimitive.THUD, 0.7f),
                PrimitiveStep(HapticPrimitive.THUD, 0.7f, delayMs = 120L),
            ),
            HapticCueMap.composition(HapticPattern.WARN),
        )
    }

    @Test
    fun arriveIsBloomSequence() {
        assertEquals(
            listOf(
                PrimitiveStep(HapticPrimitive.QUICK_RISE, 0.5f),
                PrimitiveStep(HapticPrimitive.CLICK, 0.7f),
                PrimitiveStep(HapticPrimitive.SPIN, 0.6f, delayMs = 80L),
            ),
            HapticCueMap.composition(HapticPattern.ARRIVE),
        )
    }

    @Test
    fun supportedPrimitivesYieldComposition() {
        for (pattern in HapticPattern.entries) {
            val effect = HapticCueMap.effectFor(pattern, primitivesSupported = true)
            assertTrue(effect is HapticEffect.Composition, "$pattern should compose")
            assertEquals(HapticCueMap.composition(pattern), effect.steps)
        }
    }

    @Test
    fun unsupportedPrimitivesFallBackToWaveform() {
        val expected = mapOf(
            HapticPattern.TICK to longArrayOf(0, 30),
            HapticPattern.CONFIRM to longArrayOf(0, 40, 80, 40),
            HapticPattern.LEFT to longArrayOf(0, 60, 120, 60),
            HapticPattern.RIGHT to longArrayOf(0, 60, 120, 60, 120, 60),
            HapticPattern.U_TURN to longArrayOf(0, 300, 150, 300),
            HapticPattern.WARN to longArrayOf(0, 400),
            HapticPattern.ARRIVE to longArrayOf(0, 200, 120, 60, 120, 200),
        )
        for (pattern in HapticPattern.entries) {
            val effect = HapticCueMap.effectFor(pattern, primitivesSupported = false)
            assertTrue(effect is HapticEffect.Waveform, "$pattern should fall back")
            assertTrue(
                expected.getValue(pattern).contentEquals(effect.timings),
                "$pattern fallback timings changed",
            )
        }
    }

    @Test
    fun everyCueDeclaresRequiredPrimitives() {
        for (pattern in HapticPattern.entries) {
            assertTrue(HapticCueMap.requiredPrimitives(pattern).isNotEmpty(), "$pattern")
        }
    }

    @Test
    fun eyesFreeAnnouncesDecisionPointsOnly() {
        assertEquals(
            setOf(
                HapticPattern.LEFT,
                HapticPattern.RIGHT,
                HapticPattern.U_TURN,
                HapticPattern.WARN,
                HapticPattern.ARRIVE,
            ),
            HapticPattern.entries.filter { EyesFreeFilter.isDecisionPoint(it) }.toSet(),
        )
    }
}
