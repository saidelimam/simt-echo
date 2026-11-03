package app.simt.echo.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.screens.align.AlignLivePreviewTag
import app.simt.echo.ui.screens.align.AlignReferenceFrameTag
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
import java.io.ByteArrayOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        compose.onNodeWithText("Navigate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Simulate walk").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Navigate").performScrollTo().performClick()
        assertEquals(Fixtures.L_ROUTE.routeId, navigated)
        compose.onNodeWithText("Simulate walk").performScrollTo().performClick()
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

    @Test
    fun align_showsTheStartPhotoAndPinsThePreviewToTheBottomRight() {
        val routes = routesWithFixture()
        val photoId = checkNotNull(Fixtures.L_ROUTE.start.photoAssetId)
        routes.saveWithAssetBytes(Fixtures.L_ROUTE, mapOf(photoId to jpegBytes()))
        compose.setContent {
            AlignScreen(
                routeId = Fixtures.L_ROUTE.routeId,
                routes = routes,
                onAligned = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Stand here. Match this view.").assertIsDisplayed()
        compose.onNodeWithContentDescription("View to match").assertIsDisplayed()
        compose.onNodeWithText("No start photo for this route").assertDoesNotExist()
        compose.onNodeWithText("Live preview").assertIsDisplayed()
        assertPreviewSitsInTheBottomRight()
    }

    @Test
    fun align_saysWhenTheRouteHasNoStartPhoto() {
        val routes = routesWithFixture()
        compose.setContent {
            AlignScreen(
                routeId = Fixtures.L_ROUTE.routeId,
                routes = routes,
                onAligned = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("No start photo for this route").assertIsDisplayed()
        compose.onNodeWithContentDescription("View to match").assertDoesNotExist()
        assertPreviewSitsInTheBottomRight()
    }

    private fun assertPreviewSitsInTheBottomRight() {
        val frame = compose.onNodeWithTag(AlignReferenceFrameTag).getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithTag(AlignLivePreviewTag).getUnclippedBoundsInRoot()
        val frameWidth = frame.right - frame.left
        val frameHeight = frame.bottom - frame.top
        val previewWidth = preview.right - preview.left
        val previewHeight = preview.bottom - preview.top
        val rightGap = frame.right - preview.right
        val bottomGap = frame.bottom - preview.bottom
        assertTrue(rightGap >= 0.dp && rightGap <= 20.dp, "preview right gap was $rightGap inside $frame")
        assertTrue(bottomGap >= 0.dp && bottomGap <= 20.dp, "preview bottom gap was $bottomGap inside $frame")
        assertTrue(previewWidth * 2 < frameWidth, "frame width $frameWidth collapsed around the preview $previewWidth")
        assertTrue(previewHeight < frameHeight, "frame height $frameHeight is only the preview")
        val previewCenterX = preview.left + previewWidth / 2
        val previewCenterY = preview.top + previewHeight / 2
        assertTrue(previewCenterX > frame.left + frameWidth / 2, "preview is not in the right half")
        assertTrue(previewCenterY > frame.top + frameHeight / 2, "preview is not in the bottom half")
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
        return out.toByteArray()
    }
}
