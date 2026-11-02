package app.simt.echo.ui.atmosphere

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Resolver + token gates for the motion tiers (W8-03, freeze §7 [P-R C17]).
 * Ambient signals only ever lower the tier; durations are the frozen
 * feedback/state/context values.
 */
class CosmosMotionTest {

    @Test
    fun resolverCalmAndVisibleIsFull() {
        assertEquals(
            CosmosMotionTier.FULL,
            resolveCosmosMotionTier(reduceMotion = false, powerSave = false, visible = true),
        )
    }

    @Test
    fun resolverEitherSignalAloneLowersToReduced() {
        assertEquals(
            CosmosMotionTier.REDUCED,
            resolveCosmosMotionTier(reduceMotion = true, powerSave = false, visible = true),
        )
        assertEquals(
            CosmosMotionTier.REDUCED,
            resolveCosmosMotionTier(reduceMotion = false, powerSave = true, visible = true),
        )
    }

    @Test
    fun resolverBothSignalsOrHiddenSettlesToStatic() {
        assertEquals(
            CosmosMotionTier.STATIC,
            resolveCosmosMotionTier(reduceMotion = true, powerSave = true, visible = true),
        )
        assertEquals(
            CosmosMotionTier.STATIC,
            resolveCosmosMotionTier(reduceMotion = false, powerSave = false, visible = false),
        )
        assertEquals(
            CosmosMotionTier.STATIC,
            resolveCosmosMotionTier(reduceMotion = true, powerSave = true, visible = false),
        )
    }

    @Test
    fun tierCapabilitiesMatchTheFreeze() {
        assertTrue(CosmosMotionTier.FULL.allowsInfiniteAnimations)
        assertTrue(CosmosMotionTier.FULL.allowsParallax)
        assertTrue(CosmosMotionTier.FULL.allowsEntranceAnimations)

        assertFalse(CosmosMotionTier.REDUCED.allowsInfiniteAnimations)
        assertTrue(CosmosMotionTier.REDUCED.allowsParallax)
        assertTrue(CosmosMotionTier.REDUCED.allowsEntranceAnimations)

        assertFalse(CosmosMotionTier.STATIC.allowsInfiniteAnimations)
        assertFalse(CosmosMotionTier.STATIC.allowsParallax)
        assertFalse(CosmosMotionTier.STATIC.allowsEntranceAnimations)
    }

    @Test
    fun durationsAreTheFrozenFeedbackStateContextValues() {
        assertEquals(150, CosmosMotionDurations.FEEDBACK_MS)
        assertEquals(300, CosmosMotionDurations.STATE_MS)
        assertEquals(500, CosmosMotionDurations.CONTEXT_MS)
    }
}
