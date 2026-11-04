package app.simt.echo.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.ImeAction
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.platform.FileSharing
import app.simt.echo.ui.components.CosmosFieldTag
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

/**
 * Review 0013 F7 follow-up: the decimal keypad is asserted where it is used,
 * on the real Settings screen, not only on a field constructed by the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class SettingsKeypadTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun measuredMetresCarriesTheDoneAction() {
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
        val field = compose.onNodeWithTag("$CosmosFieldTag:Measured metres")
            .performScrollTo()
            .fetchSemanticsNode()
        assertEquals(ImeAction.Done, field.config[SemanticsProperties.ImeAction])
    }

    private object QuietSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }
}
