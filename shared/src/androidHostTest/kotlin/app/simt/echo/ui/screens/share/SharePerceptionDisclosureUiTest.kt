package app.simt.echo.ui.screens.share

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.EchoRoute
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.PeerTransport
import app.simt.echo.transfer.FILE_PERCEPTION_OPT_IN_LABEL
import app.simt.echo.transfer.PERCEPTION_DISCLOSURE
import app.simt.echo.transfer.TransferService
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

/**
 * W14-04 review fix: the perception disclosure is rendered in the Share
 * export preview exactly when the route carries perception, and the plain
 * file's perception opt-in is off by default and reachable (switch role).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class SharePerceptionDisclosureUiTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private class QuietHaptics : Haptics {
        override fun play(pattern: HapticPattern) = Unit
    }

    private class QuietSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }

    private class QuietTransport : PeerTransport {
        override suspend fun offer(payload: ByteArray, token: String): Endpoint = Endpoint("192.168.1.20", 51234)
        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray = error("Share never fetches")
        override fun stopOffer() = Unit
    }

    private fun showShare(route: EchoRoute) {
        val dir = Files.createTempDirectory("w14-04-share").toString().toPath()
        val store = FileStore(FileSystem.SYSTEM, dir)
        val routes = RouteRepository(store)
        routes.save(route)
        val service = TransferService(
            transport = QuietTransport(),
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ShareScreen(
                    routeId = route.routeId,
                    service = service,
                    haptics = QuietHaptics(),
                    sharing = QuietSharing(),
                    onClose = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ShareQrTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun routeWithPerceptionShowsTheDisclosureInThePreview() {
        showShare(Fixtures.V2_ROUTE)
        compose.onNodeWithTag(ShareExportToggleTag).performClick()
        compose.onNodeWithTag(SharePerceptionDisclosureTag).performScrollTo()
        compose.onNodeWithTag(SharePerceptionDisclosureTag).assertIsDisplayed()
        compose.onNodeWithText(PERCEPTION_DISCLOSURE).assertIsDisplayed()
    }

    @Test
    fun routeWithoutPerceptionShowsNoDisclosure() {
        showShare(Fixtures.L_ROUTE)
        compose.onNodeWithTag(ShareExportToggleTag).performClick()
        compose.onAllNodesWithTag(SharePerceptionDisclosureTag).assertCountEquals(0)
    }

    @Test
    fun filePerceptionOptInIsOffByDefaultAndTogglesFromTheWarning() {
        showShare(Fixtures.V2_ROUTE)
        compose.onNodeWithText("Share as file").performScrollTo().performClick()

        compose.onNodeWithTag(ShareFilePerceptionTag).performScrollTo()
        compose.onNodeWithText(FILE_PERCEPTION_OPT_IN_LABEL).assertIsDisplayed()
        compose.onNodeWithTag(ShareFilePerceptionTag).assertIsOff()
        compose.onNodeWithTag(ShareFilePerceptionTag).performClick()
        compose.onNodeWithTag(ShareFilePerceptionTag).assertIsOn()
    }

    @Test
    fun filePerceptionOptInIsAbsentWhenThereIsNothingToOptInto() {
        showShare(Fixtures.L_ROUTE)
        compose.onNodeWithText("Share as file").performScrollTo().performClick()
        compose.onNodeWithText("Share anyway").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(ShareFilePerceptionTag).assertCountEquals(0)
    }
}
