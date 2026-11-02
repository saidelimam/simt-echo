package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.theme.EchoTheme
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W8-04 component-library gate (freeze §4/§7, Cosmos §8.1/§8.9):
 *
 * - the six §8.9 states render title + body + actions;
 * - every command/chip/segment/field-clear target is ≥48×48 dp [P-R C19];
 * - gold focus ring appears on keyboard focus;
 * - loading commands show progress and block clicks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
/**
 * W8-04 shared-surface gate (freeze §4/§7, repo modal policy): canopy
 * dialogs & sheets render through the shared surfaces only, banners carry
 * mark + copy + action, record rows divide without nesting, toast shows
 * transient confirmations, icon commands name their actions.
 */
class CosmosSurfacesTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun dialogConfirmsThroughSharedSurface() {
        var confirmed = false
        var open by mutableStateOf(true)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                if (open) {
                    CosmosDialog(
                        title = "Delete walk?",
                        body = "This removes the saved walk.",
                        primaryLabel = "Delete",
                        onPrimary = {
                            confirmed = true
                            open = false
                        },
                        onDismiss = { open = false },
                        secondaryLabel = "Keep",
                        onSecondary = { open = false },
                        destructive = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Delete walk?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertTrue(confirmed)
        compose.onNodeWithText("Delete walk?").assertDoesNotExist()
    }

    @Test
    fun sheetRendersTitleContentAndPrimary() {
        var saved = false
        var open by mutableStateOf(true)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                if (open) {
                    CosmosSheet(
                        title = "Finish walk",
                        onDismiss = { open = false },
                        primaryLabel = "Continue",
                        onPrimary = {
                            saved = true
                            open = false
                        },
                    ) {
                        androidx.compose.material3.Text("Name this walk")
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Finish walk").assertIsDisplayed()
        compose.onNodeWithText("Name this walk").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertTrue(saved)
    }

    @Test
    fun bannerShowsMarkMessageAndAction() {
        var acted = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosBanner(
                    message = "You are offline.",
                    kind = CosmosBannerKind.Warning,
                    actionLabel = "Retry",
                    onAction = { acted = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("You are offline.").assertIsDisplayed()
        compose.onNodeWithText("!").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(acted)
    }

    @Test
    fun recordRowsDivideWithoutNesting() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosRecordRows(
                    rows = listOf(
                        { CosmosRecordRow(title = "Market", subtitle = "42 m", badges = listOf("Own")) },
                        { CosmosRecordRow(title = "Park", subtitle = "80 m", badges = listOf("Received")) },
                        { CosmosRecordRow(title = "Lake", subtitle = "120 m") },
                    ),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Market").assertIsDisplayed()
        compose.onNodeWithText("Own").assertIsDisplayed()
        compose.onAllNodesWithTag(CosmosRecordDividerTag).assertCountEquals(2)
    }

    @Test
    fun toastHostShowsTransientConfirmation() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                val host = rememberCosmosToastState()
                LaunchedEffect(Unit) { launch { host.showSnackbar("Saved") } }
                CosmosToastHost(hostState = host)
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithText("Saved").assertIsDisplayed()
    }

    @Test
    fun iconButtonNamesItsAction() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosIconButton(onClick = {}, contentDescription = "Close") {
                    androidx.compose.material3.Text("×")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }
}
