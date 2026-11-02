package app.simt.echo.ui.screens.processing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.ProvideCosmosMotionTier
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertTrue

/**
 * W13-06 review fix: at fontScale 2.0 the constellation grows with its
 * content instead of clipping a fixed 44 dp label row in a fixed 220 dp
 * panel. Native graphics: legacy Robolectric text has stub metrics that do
 * not move with the font scale, so only real text layout can show this.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class ProcessingConstellationFontScaleTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private val stars = listOf(
        ConstellationStar("a", "Start of the long corridor", 0.1f, 0f, StarState.GROUNDED),
        ConstellationStar("b", "Elevator lobby by the east stairs", 0.9f, 1f, StarState.QUEUED),
    )

    private var fontScale by mutableStateOf(1f)

    private fun show(scale: Float) {
        fontScale = scale
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    ProvideCosmosMotionTier(CosmosMotionTier.STATIC) {
                        ConstellationView(stars = stars)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun Rect.holds(inner: Rect, slack: Float = 1f): Boolean =
        inner.left >= left - slack && inner.right <= right + slack &&
            inner.top >= top - slack && inner.bottom <= bottom + slack

    @Test
    fun starsKeepTheirLabelsInsideTheirItemAndThePanelAtDoubleFontScale() {
        show(scale = 2f)

        val panel = bounds(ProcessingConstellationTag)
        for (star in stars) {
            val tag = ProcessingStarTagPrefix + star.nodeId
            val item = bounds(tag)
            assertTrue(panel.holds(item), "${star.nodeId} $item must sit inside the panel $panel")
            val parts = compose.onNodeWithTag(tag, useUnmergedTree = true).onChildren()
            parts.assertCountEquals(2)
            for (i in 0 until 2) {
                val part = parts[i].fetchSemanticsNode().boundsInRoot
                assertTrue(item.holds(part), "${star.nodeId} text $i $part leaks out of its item $item")
            }
        }
    }

    @Test
    fun panelAndItemsGrowWithTheFontScale() {
        show(scale = 1f)
        val normalPanel = bounds(ProcessingConstellationTag).height
        val normalItem = bounds(ProcessingStarTagPrefix + "a").height

        compose.runOnIdle { fontScale = 2f }
        compose.waitForIdle()

        assertTrue(
            bounds(ProcessingStarTagPrefix + "a").height > normalItem,
            "a star item must wrap its larger text",
        )
        assertTrue(
            bounds(ProcessingConstellationTag).height >= normalPanel,
            "the panel never shrinks below its 1x height",
        )
        val item = bounds(ProcessingStarTagPrefix + "a")
        val panel = bounds(ProcessingConstellationTag)
        assertTrue(panel.holds(item), "item $item must stay inside the panel $panel at 2.0")
    }
}
