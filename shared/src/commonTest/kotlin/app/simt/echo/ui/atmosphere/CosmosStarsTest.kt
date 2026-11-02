package app.simt.echo.ui.atmosphere

import kotlin.math.PI
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
    fun rotationIsCentreRelativeAndIdentityAtZero() {
        val star = CosmosStar(
            fx = 0.9f,
            fy = 0.1f,
            sizeFactor = 1f,
            alpha = 1f,
            kind = 0,
            twinkleOffset = 0f,
            spike = false,
        )
        val scratch = FloatArray(2)
        rotatedStarPosition(star, 400f, 800f, 0f, scratch)
        assertEquals(0.9f * 400f, scratch[0], 0.001f)
        assertEquals(0.1f * 800f, scratch[1], 0.001f)

        // 180 degrees mirrors around the centre.
        rotatedStarPosition(star, 400f, 800f, PI.toFloat(), scratch)
        assertEquals(400f - 0.9f * 400f, scratch[0], 0.01f)
        assertEquals(800f - 0.1f * 800f, scratch[1], 0.01f)
    }
}
