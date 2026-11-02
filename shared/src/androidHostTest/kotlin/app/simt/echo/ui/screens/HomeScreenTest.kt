package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.simt.echo.data.RecoverableDraft
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.StartAnchor
import app.simt.echo.ui.EchoRoute as NavRoute
import app.simt.echo.ui.shell.EchoShell
import app.simt.echo.ui.shell.shellConfigFor
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Demo-free Home verification (plan W7-03).
 *
 * Drives the real [HomeScreen]: an empty repository shows the V2 empty
 * state with only the Record/Receive primaries (no demo buttons, no
 * raw-id rows), and populated state lists routes and drafts by name,
 * never by raw id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class HomeScreenTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun emptyRepository_showsEmptyStateAndPrimaryActionsOnly() {
        compose.setContent {
            // W8-05: Record/Receive are the shell's persistent deck, not
            // Home content — the empty state renders inside the shell.
            EchoTheme(darkTheme = true) {
                EchoShell(
                    config = shellConfigFor(NavRoute.Home),
                    onBack = {},
                    onClose = {},
                    onSettings = {},
                    onRecord = {},
                    onReceive = {},
                ) {
                    HomeScreen(onOpen = {})
                }
            }
        }
        compose.onNodeWithText("Walk it once. Share the way.").assertIsDisplayed()
        compose.onNodeWithText("Record").assertIsDisplayed()
        compose.onNodeWithText("Receive").assertIsDisplayed()
        compose.onNodeWithText("Review demo draft").assertDoesNotExist()
        compose.onNodeWithText("Route detail demo").assertDoesNotExist()
        compose.onNodeWithText("Share demo route").assertDoesNotExist()
        compose.onNodeWithText("Align demo route").assertDoesNotExist()
        compose.onNodeWithText("Navigate demo route").assertDoesNotExist()
    }

    @Test
    fun populatedRepository_listsRoutesAndDraftsByNameNeverRawId() {
        val route = EchoRoute(
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
            HomeScreen(
                onOpen = {},
                draftIds = listOf("draft-uuid-1"),
                draftNames = mapOf(
                    "draft-uuid-1" to "Evening stroll",
                    "draft-uuid-2" to "Park loop",
                ),
                savedRoutes = listOf(route),
                recoverableDrafts = listOf(
                    RecoverableDraft(
                        draftId = "draft-uuid-2",
                        sampleCount = 42,
                        markCount = 1,
                        hasRouteDraft = false,
                        jobStatus = null,
                    ),
                ),
            )
        }
        compose.onNodeWithText("Morning walk", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Evening stroll", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Park loop", substring = true).assertIsDisplayed()
        compose.onNodeWithText("route-uuid-1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("draft-uuid-1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("draft-uuid-2", substring = true).assertDoesNotExist()
    }
}
