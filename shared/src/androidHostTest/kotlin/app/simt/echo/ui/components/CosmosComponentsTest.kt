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
 * W8-04 component-library gate (freeze §4/§7, Cosmos §8.1/§8.9): §8.9
 * states, 48 dp targets [P-R C19], gold focus on keyboard focus, loading
 * commands that show progress and block clicks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class CosmosComponentsTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun minPx(): Int = with(compose.density) { 48.dp.roundToPx() }

    private fun assertMinTarget(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue(
            node.size.height >= minPx(),
            "$tag height ${node.size.height}px below 48dp (${minPx()}px)",
        )
        assertTrue(
            node.size.width >= minPx(),
            "$tag width ${node.size.width}px below 48dp (${minPx()}px)",
        )
    }


    @Test
    fun loadingStateShowsProgress() {
        compose.setContent {
            EchoTheme(darkTheme = true) { CosmosLoadingState(title = "Sending…") }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        compose.onNodeWithTag(CosmosLoadingTag).assertIsDisplayed()
    }

    @Test
    fun emptyStateShowsBodyAndAction() {
        var clicked = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosEmptyState(
                    title = "No walks yet",
                    body = "Walk it once. Share the way.",
                    actionLabel = "Record",
                    onAction = { clicked = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("No walks yet").assertIsDisplayed()
        compose.onNodeWithText("Walk it once. Share the way.").assertIsDisplayed()
        compose.onNodeWithText("Record").performClick()
        assertTrue(clicked)
    }

    @Test
    fun deniedStateOffersTheFix() {
        var clicked = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosDeniedState(
                    title = "Camera is off",
                    body = "Echo needs it to record.",
                    actionLabel = "Open settings",
                    onAction = { clicked = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Camera is off").assertIsDisplayed()
        compose.onNodeWithText("Open settings").performClick()
        assertTrue(clicked)
    }

    @Test
    fun offlineStateKeepsAForwardPath() {
        var clicked = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosOfflineState(
                    title = "You are offline",
                    body = "Saved walks still open.",
                    actionLabel = "Open saved",
                    onAction = { clicked = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("You are offline").assertIsDisplayed()
        compose.onNodeWithText("Open saved").performClick()
        assertTrue(clicked)
    }

    @Test
    fun errorStateOffersRetryAndSecondary() {
        var retried = false
        var skipped = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosErrorState(
                    message = "Offer failed",
                    actionLabel = "Retry",
                    onAction = { retried = true },
                    secondaryLabel = "Back",
                    onSecondary = { skipped = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Offer failed").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Back").performClick()
        assertTrue(retried)
        assertTrue(skipped)
    }

    @Test
    fun successStateRendersWithoutAction() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosSuccessState(title = "Arrived", body = "Market hall")
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Arrived").assertIsDisplayed()
        compose.onNodeWithText("Market hall").assertIsDisplayed()
    }



    @Test
    fun allCommandsMeet48dpTargets() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column {
                    CosmosPrimaryButton(label = "Buy", onClick = {})
                    CosmosSecondaryButton(label = "Back", onClick = {})
                    CosmosTertiaryButton(label = "Later", onClick = {})
                    CosmosDestructiveButton(label = "Delete", onClick = {})
                    CosmosIconButton(onClick = {}, contentDescription = "Close") {
                        androidx.compose.material3.Text("×")
                    }
                    CosmosRecordButton(label = "Go", onClick = {})
                }
            }
        }
        compose.waitForIdle()
        assertMinTarget("$CosmosButtonTag:Buy")
        assertMinTarget("$CosmosButtonTag:Back")
        assertMinTarget("$CosmosButtonTag:Later")
        assertMinTarget("$CosmosButtonTag:Delete")
        assertMinTarget("$CosmosButtonTag:icon")
        assertMinTarget("$CosmosButtonTag:Go")
    }

    @Test
    fun chipsAndSegmentsMeet48dpTargets() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column(modifier = Modifier.width(320.dp)) {
                    CosmosChip(label = "OK", selected = false, onSelect = {})
                    CosmosSegmentedControl(
                        options = listOf("A", "B"),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        assertMinTarget("$CosmosChipTag:OK")
        assertMinTarget("$CosmosSegmentTag:A")
        assertMinTarget("$CosmosSegmentTag:B")
    }



    @Test
    fun focusedPrimaryShowsGoldRing() {
        val fr = FocusRequester()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosPrimaryButton(
                    label = "Save",
                    onClick = {},
                    modifier = Modifier.focusRequester(fr),
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { fr.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("$CosmosButtonTag:Save").assertIsFocused()
        val state = compose.onNodeWithTag("$CosmosButtonTag:Save").fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
        assertEquals(CosmosFocusedState, state)
    }

    @Test
    fun focusedSecondaryShowsGoldRing() {
        val fr = FocusRequester()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosSecondaryButton(
                    label = "Back",
                    onClick = {},
                    modifier = Modifier.focusRequester(fr),
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { fr.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("$CosmosButtonTag:Back").assertIsFocused()
        val state = compose.onNodeWithTag("$CosmosButtonTag:Back").fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
        assertEquals(CosmosFocusedState, state)
    }

    @Test
    fun focusedIconButtonShowsGoldRing() {
        val fr = FocusRequester()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosIconButton(
                    onClick = {},
                    contentDescription = "Close",
                    modifier = Modifier.focusRequester(fr),
                ) {
                    androidx.compose.material3.Text("×")
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { fr.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("$CosmosButtonTag:icon").assertIsFocused()
        val state = compose.onNodeWithTag("$CosmosButtonTag:icon").fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
        assertEquals(CosmosFocusedState, state)
    }



    @Test
    fun loadingPrimaryBlocksClicks() {
        var clicked = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CosmosPrimaryButton(label = "Send", onClick = { clicked = true }, loading = true)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(CosmosLoadingTag).assertIsDisplayed()
        compose.onNodeWithTag("$CosmosButtonTag:Send").performClick()
        assertFalse(clicked)
    }

    @Test
    fun chipTogglesSelectionWithMark() {
        var selected = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                var on by remember { mutableStateOf(false) }
                CosmosChip(
                    label = "Landmarks",
                    selected = on,
                    onSelect = {
                        on = it
                        selected = it
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Landmarks").performClick()
        compose.waitForIdle()
        assertTrue(selected)
        compose.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun segmentedControlSelectsOneOption() {
        var index = 0
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column(modifier = Modifier.width(320.dp)) {
                    var at by remember { mutableStateOf(0) }
                    CosmosSegmentedControl(
                        options = listOf("Walk", "Wheel"),
                        selectedIndex = at,
                        onSelect = {
                            at = it
                            index = it
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Wheel").performClick()
        compose.waitForIdle()
        assertEquals(1, index)
    }

    @Test
    fun fieldShowsLabelAndClears() {
        var value = "Market"
        compose.setContent {
            EchoTheme(darkTheme = true) {
                var at by remember { mutableStateOf(value) }
                CosmosTextField(
                    label = "Route name",
                    value = at,
                    onValueChange = {
                        at = it
                        value = it
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Route name").assertIsDisplayed()
        compose.onNodeWithText("Clear").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.waitForIdle()
        assertEquals("", value)
    }

    @Test
    fun fieldAcceptsInput() {
        var value = ""
        compose.setContent {
            EchoTheme(darkTheme = true) {
                var at by remember { mutableStateOf("") }
                CosmosTextField(
                    label = "Route name",
                    value = at,
                    onValueChange = {
                        at = it
                        value = it
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("$CosmosFieldTag:Route name").performTextInput("Park")
        compose.waitForIdle()
        assertEquals("Park", value)
    }


}
