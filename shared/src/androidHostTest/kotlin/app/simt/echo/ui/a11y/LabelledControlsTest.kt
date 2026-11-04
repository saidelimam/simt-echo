package app.simt.echo.ui.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.Confirmation
import app.simt.echo.model.Landmark
import app.simt.echo.model.LandmarkSource
import app.simt.echo.platform.FileSharing
import app.simt.echo.ui.components.CosmosRadioRow
import app.simt.echo.ui.components.CosmosRadioRowTag
import app.simt.echo.ui.components.CosmosRecordRow
import app.simt.echo.ui.components.CosmosToggleRow
import app.simt.echo.ui.components.CosmosToggleRowTag
import app.simt.echo.ui.screens.review.LandmarkRow
import app.simt.echo.ui.screens.settings.SettingsScreen
import app.simt.echo.ui.screens.settings.SettingsViewModel
import app.simt.echo.ui.theme.EchoTheme
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review 0013 F1 (R13-01): every on/off and one-of controls names itself.
 * A bare `Switch` beside a `Text` is two TalkBack nodes, one unnamed; the
 * row-level toggle/selectable form is one labelled 48 dp control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class LabelledControlsTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun roleOf(tag: String): Role? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrElseNullable(SemanticsProperties.Role) { null }

    private fun minPx(): Int = with(compose.density) { 48.dp.roundToPx() }

    @Test
    fun toggleRow_isOneLabelledSwitchWithA48dpTarget() {
        var checked = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                var on by remember { mutableStateOf(false) }
                CosmosToggleRow(
                    title = "Visual assist",
                    subtitle = "Analysed on the phone.",
                    checked = on,
                    onCheckedChange = {
                        on = it
                        checked = it
                    },
                )
            }
        }
        compose.waitForIdle()
        val tag = "$CosmosToggleRowTag:Visual assist"
        val node = compose.onNodeWithTag(tag)
        node.assertIsOff()
        assertEquals(Role.Switch, roleOf(tag))
        val size = node.fetchSemanticsNode().size
        assertTrue(size.height >= minPx(), "row height ${size.height}px below 48 dp")
        assertTrue(size.width >= minPx(), "row width ${size.width}px below 48 dp")

        node.performClick()
        compose.waitForIdle()
        assertTrue(checked, "the row itself must toggle")
        compose.onNodeWithTag(tag).assertIsOn()
    }

    @Test
    fun radioRow_isTheLabelledOptionInsideItsGroup() {
        var selected = true
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column(modifier = Modifier.selectableGroup()) {
                    var at by remember { mutableStateOf(true) }
                    CosmosRadioRow(
                        label = "After 24 hours",
                        selected = at,
                        onSelect = {
                            at = true
                            selected = true
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val tag = "$CosmosRadioRowTag:After 24 hours"
        compose.onNodeWithTag(tag).assertIsSelected()
        assertEquals(Role.RadioButton, roleOf(tag))
        compose.onNodeWithTag(tag).performClick()
        assertTrue(selected)
    }

    @Test
    fun recordRowIsNotAHeadingUnlessTheCallerSaysSo() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                Column {
                    CosmosRecordRow(title = "Saved walk")
                    CosmosRecordRow(title = "Navigation", isHeading = true)
                }
            }
        }
        compose.waitForIdle()
        val listTitle = compose.onNodeWithText("Saved walk", useUnmergedTree = true).fetchSemanticsNode()
        val sectionTitle = compose.onNodeWithText("Navigation", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            !listTitle.config.contains(SemanticsProperties.Heading),
            "a record row must not pollute the heading outline",
        )
        assertTrue(
            sectionTitle.config.contains(SemanticsProperties.Heading),
            "an opted-in section row keeps its heading",
        )
    }

    @Test
    fun everySettingsToggleIsALabelledSwitch() {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val viewModel = SettingsViewModel(
            ConfigRepository(store),
            TrialRepository(store),
            QuietSharing,
            routes = RouteRepository(store),
            drafts = DraftRepository(store),
        )
        compose.setContent {
            EchoTheme(darkTheme = true) { SettingsScreen(viewModel = viewModel, onBack = {}) }
        }
        compose.waitForIdle()
        val titles = listOf(
            "Baseline mode",
            "Visual assist",
            "Camera overlay",
            "Turn heartbeat",
            "Sound cues",
            "Mobile data for landmarks",
        )
        titles.forEach { title ->
            val tag = "$CosmosToggleRowTag:$title"
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertEquals(Role.Switch, roleOf(tag), "$title must expose a labelled switch")
            compose.onNodeWithTag(tag).performScrollTo()
        }
    }

    @Test
    fun landmarkSensitivityIsALabelledSwitch() {
        var privacyRisk: Boolean? = null
        val landmark = Landmark(
            id = "lm-1",
            nodeId = "n-1",
            title = "Blue pillar",
            description = "",
            cue = "Pillar by the door",
            source = LandmarkSource.MANUAL,
            confirmation = Confirmation.CREATOR_VERIFIED,
        )
        compose.setContent {
            EchoTheme(darkTheme = true) {
                LandmarkRow(
                    landmark = landmark,
                    onEdit = { _, _, _, risk -> privacyRisk = risk },
                    onDelete = {},
                )
            }
        }
        compose.waitForIdle()
        val tag = "$CosmosToggleRowTag:Sensitive (people, plates, screens)"
        compose.onNodeWithTag(tag).assertIsOff()
        assertEquals(Role.Switch, roleOf(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        assertEquals(true, privacyRisk)
    }

    private object QuietSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }
}
