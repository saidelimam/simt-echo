package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        // First-run Ignition: one centred task, one primary command.
        compose.onNodeWithText("Record a walk").assertIsDisplayed()
        // W9-11: first-run permission priming — camera named before any
        // gate asks, with the stays-on-phone promise.
        compose.onNodeWithText("use the camera", substring = true).assertIsDisplayed()
        compose.onNodeWithText("until you share", substring = true).assertIsDisplayed()
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
        // Above-the-fold records first: route + recovery rows, stats line,
        // provenance + offline badges (shape + text, never hue-only), and
        // the recovery section leading before the saved tracks.
        compose.onNodeWithText("Morning walk", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Park loop", substring = true).assertIsDisplayed()
        compose.onNodeWithText("60.0 m", substring = true).assertIsDisplayed()
        compose.onNodeWithText("landmarks", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Own").assertIsDisplayed()
        compose.onNodeWithText("Offline-ready").assertIsDisplayed()
        compose.onNodeWithText("Interrupted drafts").assertIsDisplayed()
        compose.onNodeWithText("Saved walks").assertIsDisplayed()
        // Draft records sit below the fold in the taller record layout;
        // scroll the section into view, then assert it by name.
        compose.onNodeWithText("Evening stroll", substring = true).performScrollTo()
        compose.onNodeWithText("Drafts").assertIsDisplayed()
        compose.onNodeWithText("Evening stroll", substring = true).assertIsDisplayed()
        // One Draft badge on the recovery row, one on the draft row.
        compose.onAllNodesWithText("Draft").assertCountEquals(2)
        compose.onNodeWithText("route-uuid-1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("draft-uuid-1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("draft-uuid-2", substring = true).assertDoesNotExist()
    }

    @Test
    fun recoverySection_leadsAndResumesInterruptedDraft() {
        var resumed: RecoverableDraft? = null
        compose.setContent {
            HomeScreen(
                onOpen = {},
                draftNames = mapOf("draft-uuid-9" to "Park loop"),
                onResumeDraft = { resumed = it },
                recoverableDrafts = listOf(
                    RecoverableDraft(
                        draftId = "draft-uuid-9",
                        sampleCount = 42,
                        markCount = 1,
                        hasRouteDraft = false,
                        jobStatus = null,
                    ),
                ),
            )
        }
        compose.onNodeWithText("Interrupted drafts").assertIsDisplayed()
        compose.onNodeWithText("Park loop", substring = true).assertIsDisplayed()
        compose.onNodeWithText("42 samples", substring = true).assertIsDisplayed()
        // Records are pressable rows: resuming fires the same callback
        // the pre-Cosmos buttons wired (behavior same, presentation new).
        compose.onNodeWithTag("CosmosRecord:Park loop").performClick()
        kotlin.test.assertEquals("draft-uuid-9", resumed?.draftId)
    }
}
