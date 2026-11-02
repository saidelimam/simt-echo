package app.simt.echo.ui.atmosphere

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertTrue

/**
 * Atmosphere acceptance gates (W8-03):
 *
 * - Full/Reduced/Static compose the same hierarchy: one tagged background
 *   node, rendered statically below FULL (no infinite animation started).
 * - Stars are absent from the accessibility tree: the whole unmerged
 *   semantics tree carries no content description and no text.
 * - Daylight Nebula renders the same node flat (no starfield per freeze §3).
 * - Canopy variants render identical content with/without the top-light.
 * - Allocation discipline is structural (see [CosmosBackground] contract):
 *   brushes, palette and stars are remembered per size; the draw phase
 *   reads floats and one remembered scratch buffer. This test pins the
 *   observable half — frozen FULL advances the clock without changing the
 *   hierarchy — while the code audit pins the draw-phase half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class CosmosAtmosphereTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun setBackground(
        tier: CosmosMotionTier,
        variant: CosmosVariant = CosmosVariant.Home,
        darkTheme: Boolean = true,
        frozen: Boolean = false,
    ) {
        compose.setContent {
            EchoTheme(darkTheme = darkTheme) {
                ProvideCosmosMotionTier(tier = tier) {
                    CosmosBackground(variant = variant, frozen = frozen)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fetchRoot(): SemanticsNode =
        compose.onNode(isRoot(), useUnmergedTree = true).fetchSemanticsNode()

    private fun assertNoAccessibleContent(node: SemanticsNode) {
        val sequence = generateSequence(listOf(node)) { level ->
            level.flatMap { it.children }.takeIf { it.isNotEmpty() }
        }.flatten()
        var count = 0
        for (entry in sequence) {
            count++
            assertTrue(
                !entry.config.contains(SemanticsProperties.ContentDescription),
                "unexpected content description in the atmosphere tree",
            )
            assertTrue(
                !entry.config.contains(SemanticsProperties.Text),
                "unexpected text in the atmosphere tree",
            )
        }
        assertTrue(count > 0, "semantics tree must be non-empty")
    }

    @Test
    fun fullReducedAndStaticRenderTheSameHierarchy() {
        // One setContent per test (rule limit): the tier loop runs through
        // state so every tier renders the identical hierarchy in place.
        var tier by mutableStateOf(CosmosMotionTier.FULL)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = tier) {
                    CosmosBackground(variant = CosmosVariant.Home)
                }
            }
        }
        for (next in CosmosMotionTier.entries) {
            tier = next
            compose.waitForIdle()
            compose.onNodeWithTag(CosmosBackgroundTestTag).assertIsDisplayed()
            compose.onAllNodesWithTag(CosmosBackgroundTestTag, useUnmergedTree = true)
                .assertCountEquals(1)
            assertNoAccessibleContent(fetchRoot())
        }
    }

    @Test
    fun starsAreAbsentFromTheAccessibilityTree() {
        setBackground(tier = CosmosMotionTier.FULL, variant = CosmosVariant.Home)
        compose.onNodeWithTag(CosmosBackgroundTestTag).assertIsDisplayed()
        assertNoAccessibleContent(fetchRoot())
    }

    @Test
    fun daylightRendersTheSameNodeFlat() {
        setBackground(tier = CosmosMotionTier.FULL, darkTheme = false)
        compose.onNodeWithTag(CosmosBackgroundTestTag).assertIsDisplayed()
        compose.onAllNodesWithTag(CosmosBackgroundTestTag, useUnmergedTree = true)
            .assertCountEquals(1)
        assertNoAccessibleContent(fetchRoot())
    }

    @Test
    fun frozenFullAdvancesWithoutChangingTheHierarchy() {
        setBackground(tier = CosmosMotionTier.FULL, frozen = true)
        compose.onNodeWithTag(CosmosBackgroundTestTag).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(120_000L)
        compose.waitForIdle()
        compose.onNodeWithTag(CosmosBackgroundTestTag).assertIsDisplayed()
        compose.onAllNodesWithTag(CosmosBackgroundTestTag, useUnmergedTree = true)
            .assertCountEquals(1)
        assertNoAccessibleContent(fetchRoot())
    }

    @Test
    fun canopyVariantsRenderIdenticalContent() {
        var variant by mutableStateOf(CanopyVariant.Dialog)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CanopyPanel(variant = variant) {
                    Text("Canopy content")
                }
            }
        }
        for (next in CanopyVariant.entries) {
            variant = next
            compose.waitForIdle()
            compose.onNodeWithText("Canopy content").assertIsDisplayed()
        }
    }

    @Test
    fun canopyModifierVariantsKeepContentDisplayed() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CanopyPanel(variant = CanopyVariant.Sheet) {
                    Text("Sheet content")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Sheet content").assertIsDisplayed()
    }
}
