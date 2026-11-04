package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.components.CosmosDialogTag
import app.simt.echo.ui.screens.detail.RouteDetailScreen
import app.simt.echo.ui.theme.EchoTheme
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertNotNull

/**
 * Review 0013 F8 (R13-08): RouteDetail speaks the design system — the
 * missing-route state is CosmosEmptyState, landmarks are record rows with
 * their crops and distances, and delete is a destructive CosmosDialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    shadows = [HostTestResourcesShadow::class],
    qualifiers = "w411dp-h891dp",
)
class RouteDetailDesignSystemTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun repository(): RouteRepository {
        val dir: Path = Files.createTempDirectory("r13-08-detail").toString().toPath()
        return RouteRepository(FileStore(FileSystem.SYSTEM, dir))
    }

    @Test
    fun missingRouteUsesTheSharedEmptyState() {
        val routes = repository()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                RouteDetailScreen(
                    routeId = "missing",
                    routes = routes,
                    onShare = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("CosmosState:Empty").assertIsDisplayed()
        compose.onNodeWithText("Back home").assertIsDisplayed()
    }

    @Test
    fun landmarksUseRecordRows() {
        val routes = repository()
        routes.save(Fixtures.L_ROUTE)
        val landmark = assertNotNull(Fixtures.L_ROUTE.landmarks.firstOrNull())
        compose.setContent {
            EchoTheme(darkTheme = true) {
                RouteDetailScreen(
                    routeId = Fixtures.L_ROUTE.routeId,
                    routes = routes,
                    onShare = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("CosmosRecord:${landmark.title}").assertIsDisplayed()
        if (landmark.cue.isNotBlank()) {
            compose.onNodeWithText(landmark.cue, substring = true).assertExists()
        }
    }

    @Test
    fun deleteUsesTheDestructiveDialog() {
        val routes = repository()
        routes.save(Fixtures.L_ROUTE)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                RouteDetailScreen(
                    routeId = Fixtures.L_ROUTE.routeId,
                    routes = routes,
                    onShare = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Delete route").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CosmosDialogTag).assertIsDisplayed()
        compose.onNodeWithText("Delete this route?").assertIsDisplayed()
        compose.onNodeWithText("Keep route").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CosmosDialogTag).assertDoesNotExist()
    }
}
