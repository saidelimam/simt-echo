package app.simt.echo.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /** The promo banner sits above the About footer and opens simt.app. */
    @Test
    fun promoBannerOpensSimtSiteFromAboveTheFooter() {
        var opened: String? = null
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CompositionLocalProvider(
                    // The banner has no business launching a browser in a test:
                    // the platform seam is swapped for a recorder.
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            opened = uri
                        }
                    },
                ) {
                    SettingsScreen(viewModel = viewModel(), onBack = {})
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(SimtPromoBannerTag).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("https://simt.app", opened)

        // Order, not just presence: scrolled to the footer, the card must sit
        // above it. Both positions come from the same scroll container.
        compose.onNodeWithText("Said Elimam").performScrollTo()
        compose.waitForIdle()
        val bannerTop = compose.onNodeWithTag(SimtPromoBannerTag).fetchSemanticsNode().boundsInRoot.top
        val aboutTop = compose.onNodeWithText("About").fetchSemanticsNode().boundsInRoot.top
        assertTrue(bannerTop < aboutTop, "banner sits above the footer: banner=$bannerTop about=$aboutTop")
    }

    /** The footer is the app's statutory page: company, parent org, founder. */
    @Test
    fun footerNamesTheCompanyParentOrganisationAndFounder() {
        compose.setContent {
            EchoTheme(darkTheme = true) { SettingsScreen(viewModel = viewModel(), onBack = {}) }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Said Elimam").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Simt Space").assertExists()
        compose.onNodeWithText("Quazar Media").assertExists()
        compose.onNodeWithText("© 2026 Simt Space. All rights reserved.", substring = true).assertExists()
    }

    private object QuietSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }
}
