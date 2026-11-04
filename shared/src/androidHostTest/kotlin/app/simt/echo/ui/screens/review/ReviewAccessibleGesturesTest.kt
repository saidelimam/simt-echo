package app.simt.echo.ui.screens.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.media.CropBox
import app.simt.echo.ui.components.CosmosFieldTag
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review 0013 F3 (R13-03): every Review gesture has a non-pointer path.
 * Crop presets replace the mandatory drag, zoom has buttons beside pinch,
 * the hero names its button action, and the map states its selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    shadows = [HostTestResourcesShadow::class],
    qualifiers = "w411dp-h891dp",
)
class ReviewAccessibleGesturesTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @OptIn(ExperimentalEncodingApi::class)
    private fun heroBytes(): ByteArray = Base64.decode(TEST_PNG_BASE64)

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun presetBoxFramesALandmarkWithoutADrag() {
        var captured: CropBox? = null
        compose.setContent {
            EchoTheme(darkTheme = true) {
                DrawOverlay(
                    nodeId = "n1",
                    nodeLabel = "Turn right",
                    heroBytes = heroBytes(),
                    title = "Pillar",
                    onTitle = {},
                    onConfirm = { _, box -> captured = box },
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        // No box yet: the confirm is honestly disabled until a preset lands.
        compose.onNodeWithText("Use box").assertIsNotEnabled()
        compose.onNodeWithText("Frame centre").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Use box").assertIsEnabled().performClick()

        val box = assertNotNull(captured, "a preset box must be confirmable")
        assertEquals(0.2f, box.left, 0.02f)
        assertEquals(0.2f, box.top, 0.02f)
        assertEquals(0.8f, box.right, 0.02f)
        assertEquals(0.8f, box.bottom, 0.02f)
    }

    @Test
    fun drawOverlayUsesTheCosmosField() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                DrawOverlay(
                    nodeId = "n1",
                    nodeLabel = "Turn right",
                    heroBytes = heroBytes(),
                    title = "Pillar",
                    onTitle = {},
                    onConfirm = { _, _ -> },
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("$CosmosFieldTag:Landmark name").assertIsDisplayed()
    }

    @Test
    fun inspectorZoomHasButtonsBesidePinch() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                KeyframeInspector(
                    nodeId = "n1",
                    title = "Turn right",
                    heroBytes = heroBytes(),
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Zoom 1.0×").assertIsDisplayed()
        compose.onNodeWithText("Zoom in").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Zoom 2.0×").assertIsDisplayed()
        compose.onNodeWithText("Zoom in").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Zoom 3.0×").assertIsDisplayed()
        compose.onNodeWithText("Reset zoom").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Zoom 1.0×").assertIsDisplayed()
    }

    @Test
    fun heroImageIsAZoomButtonNotAPill() {
        var inspected = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                HeroImage(
                    bytes = heroBytes(),
                    contentDescription = "Start photo",
                    onInspect = { inspected = true },
                )
            }
        }
        compose.waitForIdle()
        val hero = compose.onNodeWithTag(HeroImageTag)
        val role = hero.fetchSemanticsNode().config
            .getOrElseNullable(SemanticsProperties.Role) { null }
        assertEquals(Role.Button, role)
        hero.performClick()
        assertTrue(inspected)
        compose.onAllNodesWithText("Tap to zoom", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun reviewMapNamesTheSelectedSpot() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ReviewInstrumentMap(
                    points = listOf(Offset(0f, 0f), Offset(10f, 4f)),
                    highlightIndex = 1,
                    selectedPositionLabel = "Turn 2 of 4",
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Route map, selected Turn 2 of 4").assertIsDisplayed()
    }

    companion object {
        /** Real 16x16 RGB PNG (decodes via BitmapFactory under Robolectric). */
        private const val TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAABlklEQVR4nBXRURVEIQhFUSMYgQhGMAIRiGCEE8EIRiACEYhABCLMG7/ZrMt1jMEcyGAN9kAHNjgDBnfwBj6IQQ5q0IMxJnMikzXZE53Y5EyY3Mmb+CQmOalJzw8IUxBhCVtQwYQjIFzhCS6EkEIJLR9YzIUs1mIvdGGLs2BxF2/hi1jkoha9PrCZG9mszd7oxjZnw+Zu3sY3sclNbXp/QJmKKEvZiiqmHAXlKk9xJZRUSmn9gDENMZaxDTXMOAbGNZ7hRhhplNH2gcM8yGEd9kEPdjgHDvfwDn6IQx7q0OcD/wK/Sr4jv9hfkG/1N/x/Fx44BCQU9Pc94zIvclmXfdGLXc79j9/Lu/glLnmpS98PPOZDHuuxH/qwx3n/5ffxHv6IRz7q0e8DznTEWc521DHn+D/KdZ7jTjjplNP+gWAGEqxgBxpYcOIf/AYv8CCCDCro+EAyE0lWshNNLDn5P/MmL/Ekkkwq6fxAMQspVrELLaw49S/lFq/wIoosquj6QDMbaVazG22sOf2v8Dav8SaabKrp5geIAnAQEbP2+wAAAABJRU5ErkJggg=="
    }
}
