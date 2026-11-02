package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.simt.echo.AppGraph
import app.simt.echo.Fixtures
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.platform.Platform
import app.simt.echo.ui.EchoNavHost
import app.simt.echo.ui.EchoRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W7-04 end-to-end wiring through the real [EchoNavHost]: fixture route
 * Home → RouteDetail → Align → Navigate, with the `simulate` flag read
 * back off the actual back-stack entries at each hop. The replay leg
 * (Navigate → ARRIVED on [app.simt.echo.capture.ReplayMotionSource]) runs
 * headlessly in `SimulateJourneyTest` under virtual time — a 45 m replay
 * takes 45 s of wall clock, so this suite proves the destination is
 * entered with the flag and the screen renders, not the arrival itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class HostSimulateJourneyTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun testGraph(): AppGraph {
        val dir = Files.createTempDirectory("w7-04-host").toString()
        val platform = object : Platform {
            override val name: String = "host-test"
            override val dataDir: String = dir
        }
        return AppGraph(platform = platform).also {
            it.routeRepository.save(Fixtures.L_ROUTE)
        }
    }

    private fun currentAlign(nav: NavHostController): EchoRoute.Align =
        nav.currentBackStackEntry?.toRoute<EchoRoute.Align>()
            ?: error("expected Align on top, has ${nav.currentBackStackEntry?.destination?.route}")

    private fun currentNavigate(nav: NavHostController): EchoRoute.Navigate =
        nav.currentBackStackEntry?.toRoute<EchoRoute.Navigate>()
            ?: error("expected Navigate on top, has ${nav.currentBackStackEntry?.destination?.route}")

    @Test
    fun simulate_flowsHomeToNavigateWithFlagSet() {
        val graph = testGraph()
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        // W8-06: saved walks are Cosmos records — click the record row,
        // same RouteDetail destination as the pre-Cosmos button.
        compose.onNodeWithTag("CosmosRecord:L route (fixture)").performClick()
        // W8-05: the shell chrome + persistent deck take vertical space, so
        // the RouteDetail body scrolls inside the shell (same pattern as
        // "Save ECHO" in HostCoreLoopJourneyTest).
        compose.onNodeWithText("Simulate walk").performScrollTo()
        compose.onNodeWithText("Simulate walk").assertIsDisplayed()
        compose.onNodeWithText("Simulate walk").performClick()

        compose.runOnIdle {
            assertEquals(
                EchoRoute.Align(Fixtures.L_ROUTE.routeId, simulate = true),
                currentAlign(nav),
            )
        }
        compose.onNodeWithText("Stand here. Match this view.").assertIsDisplayed()
        compose.onNodeWithText("I'm aligned").performClick()

        compose.runOnIdle {
            assertEquals(
                EchoRoute.Navigate(Fixtures.L_ROUTE.routeId, simulate = true),
                currentNavigate(nav),
            )
        }
        // NOTE: no render assertion on Navigate itself — the replay emits
        // every 50 ms, so the compose clock never idles while it runs.
        // Arrival on this exact source is proven headlessly in
        // SimulateJourneyTest under virtual time.
    }

    @Test
    fun navigate_flowsHomeToNavigateWithFlagClear() {
        val graph = testGraph()
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        // W8-06: saved walks are Cosmos records — click the record row,
        // same RouteDetail destination as the pre-Cosmos button.
        compose.onNodeWithTag("CosmosRecord:L route (fixture)").performClick()
        compose.onNodeWithText("Navigate").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                EchoRoute.Align(Fixtures.L_ROUTE.routeId, simulate = false),
                currentAlign(nav),
            )
        }
        compose.onNodeWithText("I'm aligned").performClick()

        compose.runOnIdle {
            val entered = currentNavigate(nav)
            assertEquals(Fixtures.L_ROUTE.routeId, entered.routeId)
            assertTrue(!entered.simulate, "live Navigate must not replay")
        }
        // NOTE: no render assertion on Navigate itself — see above.
    }
}
