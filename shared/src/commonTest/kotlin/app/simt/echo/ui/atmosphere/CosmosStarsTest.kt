package app.simt.echo.ui.atmosphere

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-model gates for the frozen edge-band starfield (W8-03, freeze §9.3
 * [P-T starfield] [P-R C11]). No device needed: generation is deterministic
 * and the draw phase only reads what is asserted here.
 */
class CosmosStarsTest {

    @Test
    fun starsLiveOnlyInTheEdgeBand() {
        val stars = generateCosmosStars(400f, 800f, CosmosVariant.Panel, lowPower = false)
        assertTrue(stars.isNotEmpty())
        for (star in stars) {
            val inClearX = star.fx in COSMOS_EDGE_CLEAR_MIN..COSMOS_EDGE_CLEAR_MAX
            val inClearY = star.fy in COSMOS_EDGE_CLEAR_MIN..COSMOS_EDGE_CLEAR_MAX
            assertTrue(
                !(inClearX && inClearY),
                "star at (${star.fx}, ${star.fy}) sits inside the centre clear zone",
            )
        }
    }

    @Test
    fun screenCountsFollowAreaFormulaWithClamps() {
        assertEquals(24, starCountForArea(100f, dialog = false))
        assertEquals(160, starCountForArea(100_000_000f, dialog = false))
        // area/7500: 750 000 px² -> 100.
        assertEquals(100, starCountForArea(750_000f, dialog = false))
        assertEquals(24, starCountForArea(750_000f / 100f, dialog = false))
    }

    @Test
    fun dialogCountsFollowAreaFormulaWithClamps() {
        assertEquals(12, starCountForArea(100f, dialog = true))
        assertEquals(72, starCountForArea(100_000_000f, dialog = true))
        // area/10000: 500 000 px² -> 50.
        assertEquals(50, starCountForArea(500_000f, dialog = true))
    }

    @Test
    fun accentFractionsRespectTheFrozenBudget() {
        // Large canvas so percentages are meaningful.
        val stars = generateCosmosStars(2000f, 2000f, CosmosVariant.Home, lowPower = false)
        assertEquals(COSMOS_SCREEN_BUCKET, stars.size)
        val accentA = stars.count { it.kind == 2 }
        val accentB = stars.count { it.kind == 3 }
        val ink = stars.count { it.kind == 0 || it.kind == 1 }
        assertTrue(accentA <= stars.size / 10, "accent A $accentA of ${stars.size}")
        assertTrue(accentB <= stars.size / 10, "accent B $accentB of ${stars.size}")
        assertTrue(ink * 10 >= stars.size * 7, "ink $ink of ${stars.size}")
        assertTrue(stars.count { it.spike } <= 3, "at most 3 spiked stars")
    }

    @Test
    fun lowPowerDrawsFortyPercent() {
        val full = generateCosmosStars(1000f, 2000f, CosmosVariant.Panel, lowPower = false)
        val low = generateCosmosStars(1000f, 2000f, CosmosVariant.Panel, lowPower = true)
        assertEquals((full.size * 0.4f).toInt(), low.size)
    }

    @Test
    fun generationIsDeterministicPerVariant() {
        val first = generateCosmosStars(400f, 800f, CosmosVariant.Gate, lowPower = false)
        val second = generateCosmosStars(400f, 800f, CosmosVariant.Gate, lowPower = false)
        assertEquals(first, second)
        val other = generateCosmosStars(400f, 800f, CosmosVariant.Home, lowPower = false)
        assertTrue(first != other, "variant seed must change the field")
    }

    @Test
    fun twinkleStepsStayInRangeAndCoverTheSweep() {
        assertEquals(0, twinkleStep(0f))
        assertEquals(COSMOS_TWINKLE_STEPS, twinkleStep(1f))
        assertEquals(0, twinkleStep(-0.5f))
        assertEquals(COSMOS_TWINKLE_STEPS, twinkleStep(2f))
    }

    @Test
    fun twinkleHasNoPopAcrossThePhaseWrap() {
        // Offset 0.31 makes the phase cross 1.0 mid-sweep: the old sawtooth
        // dropped from ~1.0 to 0.55 alpha there, a visible once-per-sweep pop.
        val star = CosmosStar(0.05f, 0.05f, 1.6f, 0.95f, 2, twinkleOffset = 0.31f, spike = false)
        var previous = twinkleAlpha(star, 0)
        for (step in 1..COSMOS_TWINKLE_STEPS * 4) {
            val next = twinkleAlpha(star, step)
            assertTrue(
                kotlin.math.abs(next - previous) < 0.45f * star.alpha * 0.2f,
                "step $step jumps from $previous to $next",
            )
            previous = next
        }
        assertEquals(twinkleAlpha(star, 0), twinkleAlpha(star, COSMOS_TWINKLE_STEPS), 1e-4f)
    }

    @Test
    fun onlyAccentStarsTwinkle() {
        val stars = generateCosmosStars(400f, 800f, CosmosVariant.Home, lowPower = false)
        val accents = stars.filter { it.twinkles() }
        assertTrue(accents.isNotEmpty() && accents.size <= stars.size / 5, "accents are a small minority")
        for (star in stars) {
            val alphas = (0..COSMOS_TWINKLE_STEPS).map { twinkleAlpha(star, it) }.toSet()
            if (star.twinkles()) {
                assertTrue(alphas.size > 1, "accent star must change brightness")
                assertTrue(alphas.all { it in 0.5f * star.alpha..star.alpha })
            } else {
                assertEquals(setOf(star.alpha), alphas)
            }
        }
    }
}
