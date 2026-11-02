package app.simt.echo.ui.screens.review

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.simt.echo.ai.AiCandidate
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
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertTrue

/**
 * W13-05 review fix: the 600 ms mask trace redraws, it never recomposes.
 * A `composed` modifier's factory runs each time the composable applying
 * it recomposes, so counting it over the whole trace counts recompositions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class SuggestionArtworkRecompositionTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun traceFramesDoNotRecomposeTheArtwork() {
        var composed = 0
        val candidate = AiCandidate(
            candidateId = "c1",
            title = "Red pillar",
            box = listOf(100, 100, 400, 400),
            maskSvgPath = "M 0 0 L 10 0 L 10 10 L 0 10 Z",
            maskW = 10,
            maskH = 10,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = CosmosMotionTier.FULL) {
                    SuggestionArtwork(
                        candidate = candidate,
                        heroBytes = null,
                        modifier = Modifier.composed {
                            composed++
                            Modifier
                        },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("AI suggestion: Red pillar").assertExists()
        val settled = composed

        // Walk the whole trace frame by frame (≈ 40 frames at 16 ms).
        repeat(45) { compose.mainClock.advanceTimeByFrame() }

        assertTrue(
            composed - settled <= 1,
            "the trace recomposed the artwork ${composed - settled} times; it must only redraw",
        )
    }
}
