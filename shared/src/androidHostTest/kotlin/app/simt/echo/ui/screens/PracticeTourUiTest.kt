package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.simt.echo.AppGraph
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.ui.EchoNavHost
import app.simt.echo.ui.EchoRoute as NavRoute
import app.simt.echo.ui.screens.navigate.PRACTICE_OFFER_LABEL
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import app.simt.echo.platform.Platform
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertTrue

/**
 * W13-13 UI gate: first run offers the guided practice tour (labelled
 * Practice, never a saved-route row), and the Practice destination runs
 * the real Navigate instrument without writing `routes/` or `trials/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class PracticeTourUiTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun firstRun_offersPracticeAndFiresCallback() {
        var practiced = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                HomeScreen(onOpen = {}, onPractice = { practiced = true })
            }
        }
        // First-run Ignition keeps its one primary command; Practice is
        // the secondary offer, labelled Practice.
        compose.onNodeWithText("Record a walk").assertIsDisplayed()
        compose.onNodeWithText(PRACTICE_OFFER_LABEL).assertIsDisplayed()
        compose.onNodeWithText(PRACTICE_OFFER_LABEL).performClick()
        assertTrue(practiced, "Practice offer fires onPractice")
    }

    @Test
    fun populatedHome_neverListsPracticeAsARoute() {
        val route = SavedRoute(
            routeId = "route-uuid-1",
            name = "Morning walk",
            createdAtEpochMs = 0L,
            creatorLabel = "creator",
            motionSource = "replay",
            totalLengthM = 60.0,
            start = StartAnchor(description = "Door", photoAssetId = null, alignmentHint = ""),
            nodes = emptyList(),
            legs = emptyList(),
            path = emptyList(),
        )
        compose.setContent {
            HomeScreen(onOpen = {}, savedRoutes = listOf(route))
        }
        compose.onNodeWithText("Morning walk", substring = true).assertIsDisplayed()
        // The tour is first-run only: no Practice offer and no Practice
        // walk among the saved records.
        compose.onNodeWithText(PRACTICE_OFFER_LABEL).assertDoesNotExist()
        compose.onNodeWithText("Practice walk").assertDoesNotExist()
    }

    @Test
    fun practiceDestination_entersNavigateWithoutWritingStores() {
        val dir = Files.createTempDirectory("w13-13-practice").toString()
        val platform = object : Platform {
            override val name: String = "host-test"
            override val dataDir: String = dir
        }
        // Empty graph: first run, no saved routes, no trials.
        val graph = AppGraph(platform = platform)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        // W8-05: the shell chrome + persistent deck take vertical space,
        // so the secondary offer scrolls into view like RouteDetail's
        // below-fold actions (HostSimulateJourneyTest pattern).
        compose.onNodeWithText(PRACTICE_OFFER_LABEL).performScrollTo()
        compose.onNodeWithText(PRACTICE_OFFER_LABEL).assertIsDisplayed()
        compose.onNodeWithText(PRACTICE_OFFER_LABEL).performClick()

        compose.runOnIdle {
            nav.currentBackStackEntry?.toRoute<NavRoute.Practice>()
                ?: error("expected Practice on top")
        }
        // The no-write guarantee through the real host: the bundled walk
        // is ephemeral and the trial sink is null.
        compose.runOnIdle {
            assertTrue(graph.routeRepository.list().isEmpty(), "tour wrote routes/")
            assertTrue(graph.trialRepository.list().isEmpty(), "tour wrote trials/")
        }
        // NOTE: no render assertion on Navigate itself — the replay emits
        // every 50 ms, so the compose clock never idles while it runs
        // (same reason as HostSimulateJourneyTest).
    }
}
