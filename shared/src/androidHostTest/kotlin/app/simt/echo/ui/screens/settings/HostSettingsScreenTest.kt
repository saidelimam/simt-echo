package app.simt.echo.ui.screens.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.CaptureLog
import app.simt.echo.platform.FileSharing
import app.simt.echo.ui.components.CosmosButtonTag
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

/**
 * Privacy & data gate (W8-11): "Delete all" is a promise to erase something,
 * so it is disabled while the store is empty and enabled once one route,
 * draft, trial or exported file exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class HostSettingsScreenTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private val store = FileStore(FakeFileSystem(), "/data".toPath())
    private val drafts = DraftRepository(store)

    private fun viewModel() = SettingsViewModel(
        ConfigRepository(store),
        TrialRepository(store),
        QuietSharing,
        routes = RouteRepository(store),
        drafts = drafts,
    )

    private fun draft() {
        drafts.saveCapture(
            CaptureLog(
                draftId = "d1",
                startedAtEpochMs = 1L,
                motionSource = "echo_builtin_pdr",
                videoStartNs = 2L,
                samples = emptyList(),
                marks = emptyList(),
                startPhoto = "start.jpg",
            ),
        )
    }

    @Test
    fun deleteAllIsDisabledWhileTheStoreIsEmpty() {
        compose.setContent {
            EchoTheme(darkTheme = true) { SettingsScreen(viewModel = viewModel(), onBack = {}) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("$CosmosButtonTag:Delete all").assertIsNotEnabled()
    }

    @Test
    fun deleteAllIsEnabledOnceThereIsSomethingToErase() {
        draft()
        compose.setContent {
            EchoTheme(darkTheme = true) { SettingsScreen(viewModel = viewModel(), onBack = {}) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("$CosmosButtonTag:Delete all").assertIsEnabled()
    }

    private object QuietSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }
}
