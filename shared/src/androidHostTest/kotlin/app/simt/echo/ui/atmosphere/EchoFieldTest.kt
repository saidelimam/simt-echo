package app.simt.echo.ui.atmosphere

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W13-01 echo field gates (luminous-freeze §5, Cosmos C17):
 *
 * - Every tier and both schemes compose one field node, so Static settles
 *   with the same hierarchy as Full.
 * - The field (aurora, ripples, stars) is absent from the accessibility tree.
 * - A primary-command press emits a ripple at the command, which is how the
 *   field learns about real events (honest light, freeze §1.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class EchoFieldTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }


    @Test
    fun fieldTimeIsBoundedAndLoopsOnTheShaderPeriod() {
        assertEquals(0f, echoFieldLoopSec(0L))
        assertEquals(0f, echoFieldLoopSec(ECHO_FIELD_LOOP_MS))
        assertEquals(ECHO_FIELD_LOOP_SEC / 2f, echoFieldLoopSec(ECHO_FIELD_LOOP_MS / 2), 1e-3f)
        // Days of uptime must not grow the uniform (float precision decay).
        val late = echoFieldLoopSec(ECHO_FIELD_LOOP_MS * 86_400L)
        assertTrue(late >= 0f && late < ECHO_FIELD_LOOP_SEC, "time must stay bounded, was $late")
        // A clock before the epoch (test clocks) still maps into the period.
        assertTrue(echoFieldLoopSec(-1L) >= 0f)
    }

    private fun assertNoAccessibleContent() {
        val root: SemanticsNode = compose.onNode(isRoot(), useUnmergedTree = true).fetchSemanticsNode()
        val all = generateSequence(listOf(root)) { level ->
            level.flatMap { it.children }.takeIf { it.isNotEmpty() }
        }.flatten()
        for (node in all) {
            assertTrue(!node.config.contains(SemanticsProperties.ContentDescription), "field must not describe itself")
            assertTrue(!node.config.contains(SemanticsProperties.Text), "field must not carry text")
        }
    }

    @Test
    fun everyTierAndSchemeComposesOneSilentField() {
        var tierState by mutableStateOf(CosmosMotionTier.FULL)
        var darkState by mutableStateOf(true)
        compose.setContent {
            EchoTheme(darkTheme = darkState) {
                ProvideCosmosMotionTier(tier = tierState) {
                    EchoField(ripples = EchoRippleState())
                }
            }
        }
        for (dark in listOf(true, false)) {
            for (tier in CosmosMotionTier.entries) {
                darkState = dark
                tierState = tier
                compose.waitForIdle()
                compose.onNodeWithTag(EchoFieldTestTag).assertIsDisplayed()
                compose.onAllNodesWithTag(EchoFieldTestTag, useUnmergedTree = true).assertCountEquals(1)
                assertNoAccessibleContent()
            }
        }
    }

    @Test
    fun aPrimaryPressRipplesFromTheCommand() {
        var now = 0L
        val ripples = EchoRippleState(clock = { now })
        compose.setContent {
            EchoTheme(darkTheme = true) {
                androidx.compose.runtime.CompositionLocalProvider(LocalEchoRipples provides ripples) {
                    CosmosPrimaryButton(label = "Go", onClick = {})
                }
            }
        }
        compose.onNodeWithText("Go").performClick()
        compose.waitForIdle()
        assertTrue(ripples.anyAlive(now), "the press must start a ripple")
        val out = FloatArray(ECHO_RIPPLE_SLOTS * 4)
        ripples.writeUniforms(now, shortEdgePx = 1000f, out = out)
        assertTrue(Offset(out[0], out[1]) != Offset.Zero, "the ripple starts at the command's centre, not the origin")
    }
}
