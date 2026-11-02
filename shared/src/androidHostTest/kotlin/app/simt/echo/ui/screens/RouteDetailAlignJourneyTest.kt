package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.screens.align.AlignScreen
import app.simt.echo.ui.screens.detail.RouteDetailScreen
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

/**
 * W7-04 journey legs through the real screens: RouteDetail offers
 * Navigate + Share + Simulate for a saved route, and Align hands off
 * on confirmation. The flag flow between them (Align → Navigate in
 * simulate mode) is pinned headlessly in `SimulateJourneyTest`; W7-09
 * may join both into one compose journey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RouteDetailAlignJourneyTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun routesWithFixture(): RouteRepository {
        val dir = Files.createTempDirectory("w7-04-routes").toString().toPath()
        val routes = RouteRepository(FileStore(FileSystem.SYSTEM, dir))
        routes.save(Fixtures.L_ROUTE)
        return routes
    }

    @Test
    fun routeDetail_offersNavigateShareAndSimulate() {
        val routes = routesWithFixture()
        var navigated: String? = null
        var simulated: String? = null
        compose.setContent {
            RouteDetailScreen(
                routeId = Fixtures.L_ROUTE.routeId,
                routes = routes,
                onShare = {},
                onBack = {},
                onNavigate = { navigated = it },
                onSimulate = { simulated = it },
            )
        }
        compose.onNodeWithText("Navigate").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Simulate walk").assertIsDisplayed()

        compose.onNodeWithText("Navigate").performClick()
        assertEquals(Fixtures.L_ROUTE.routeId, navigated)
        compose.onNodeWithText("Simulate walk").performClick()
        assertEquals(Fixtures.L_ROUTE.routeId, simulated)
    }

    @Test
    fun align_confirmsHandoff() {
        val routes = routesWithFixture()
        var aligned = false
        compose.setContent {
            AlignScreen(
                routeId = Fixtures.L_ROUTE.routeId,
                routes = routes,
                onAligned = { aligned = true },
                onBack = {},
            )
        }
        compose.onNodeWithText("I'm aligned").assertIsDisplayed()
        compose.onNodeWithText("I'm aligned").performClick()
        assertEquals(true, aligned)
    }
}
