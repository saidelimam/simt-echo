package app.simt.echo.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.NfcCapabilities
import app.simt.echo.platform.NfcEvent
import app.simt.echo.platform.NfcPairingController
import app.simt.echo.platform.NfcPublication
import app.simt.echo.platform.PeerTransport
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.components.NfcTapPanelTag
import app.simt.echo.ui.components.PairingMethodNfcTag
import app.simt.echo.ui.components.PairingMethodQrTag
import app.simt.echo.ui.components.PairingRecoveryTag
import app.simt.echo.ui.screens.receive.ReceiveScannerTag
import app.simt.echo.ui.screens.receive.ReceiveScreen
import app.simt.echo.ui.screens.share.ShareQrTag
import app.simt.echo.ui.screens.share.ShareScreen
import app.simt.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * W21-03 gate: the two pairing surfaces render the method the visit
 * actually started in, switch in one action, and never mount a surface the
 * selected method cannot use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class PairingModesUiTest {

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
        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray = error("no fetch here")
        override fun stopOffer() = Unit
    }

    /** Publication is confirmed as soon as it is requested, so "Ready" is honest. */
    private class ReadyNfc(capabilities: NfcCapabilities) : NfcPairingController {
        private val caps = MutableStateFlow(capabilities)
        private val pub = MutableStateFlow(NfcPublication())
        override val capabilities: StateFlow<NfcCapabilities> = caps
        override val publication: StateFlow<NfcPublication> = pub
        override val events: Flow<NfcEvent> = MutableSharedFlow()
        override fun publish(invitation: String, offerGeneration: Long) {
            pub.value = NfcPublication(generation = offerGeneration, ready = true)
        }

        override fun startReading(attemptId: Long) = Unit
        override fun stopReading(attemptId: Long) = Unit
        override fun clearPublishedOffer(offerGeneration: Long) {
            pub.value = NfcPublication.IDLE
        }

        override fun close() = Unit
    }

    private fun shareService(): TransferService {
        val dir = Files.createTempDirectory("w21-03-share").toString().toPath()
        val store = FileStore(FileSystem.SYSTEM, dir)
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        return TransferService(
            transport = QuietTransport(),
            routes = routes,
            store = store,
            nowMs = { 1_762_000_000_000L },
        )
    }

    private fun receiveService(): TransferService {
        val dir = Files.createTempDirectory("w21-03-receive").toString().toPath()
        val store = FileStore(FileSystem.SYSTEM, dir)
        return TransferService(
            transport = QuietTransport(),
            routes = RouteRepository(store),
            store = store,
            nowMs = { 1_762_000_000_000L },
        )
    }

    private fun showShare(nfc: NfcPairingController) {
        val service = shareService()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ShareScreen(
                    routeId = Fixtures.L_ROUTE.routeId,
                    service = service,
                    haptics = QuietHaptics(),
                    sharing = QuietSharing(),
                    onClose = {},
                    pairing = nfc,
                )
            }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun shareOpensInNfcWithTheTapPanel() {
        showShare(ReadyNfc(NfcCapabilities(canRead = true, canPresent = true, enabled = true)))
        awaitTag(NfcTapPanelTag)

        compose.onNodeWithTag(PairingMethodNfcTag).assertIsSelected()
        compose.onNodeWithTag(PairingMethodQrTag).assertIsNotSelected()
        compose.onNodeWithTag(NfcTapPanelTag).assertIsDisplayed()
        // NFC owns the surface: no QR behind it, no second invitation.
        compose.onNodeWithTag(ShareQrTag).assertDoesNotExist()
        compose.onNodeWithText("Ready to tap").assertIsDisplayed()
    }

    @Test
    fun shareSwitchesToQrInOneAction() {
        showShare(ReadyNfc(NfcCapabilities(canRead = true, canPresent = true, enabled = true)))
        awaitTag(NfcTapPanelTag)

        compose.onNodeWithTag(PairingMethodQrTag).performScrollTo().performClick()
        awaitTag(ShareQrTag)

        compose.onNodeWithTag(PairingMethodQrTag).assertIsSelected()
        compose.onNodeWithTag(PairingMethodNfcTag).assertIsNotSelected()
        compose.onNodeWithTag(ShareQrTag).assertIsDisplayed()
        compose.onNodeWithTag(NfcTapPanelTag).assertDoesNotExist()
    }

    @Test
    fun shareWithoutHceSaysWhyAndKeepsQrReachable() {
        showShare(ReadyNfc(NfcCapabilities.NONE))
        awaitTag(ShareQrTag)

        // NFC stays selected but explains itself, and the code it *can*
        // serve is already there rather than hidden behind another tap.
        compose.onNodeWithTag(PairingMethodNfcTag).assertIsSelected()
        compose.onNodeWithText("This phone can share by QR").assertIsDisplayed()
        compose.onNodeWithTag(PairingRecoveryTag).assertIsDisplayed()
        compose.onNodeWithTag(ShareQrTag).assertIsDisplayed()

        compose.onNodeWithText("Use QR instead").performScrollTo().performClick()
        compose.onNodeWithTag(PairingMethodQrTag).assertIsSelected()
        compose.onNodeWithTag(NfcTapPanelTag).assertDoesNotExist()
    }

    @Test
    fun relayActionIsHiddenWithoutAPeerQualifiedBackend() {
        showShare(ReadyNfc(NfcCapabilities.NONE))
        awaitTag(ShareQrTag)
        // plan 0005 §1: the configured relay cannot carry another install's
        // route, so no upload is ever offered.
        compose.onNodeWithText("Use internet relay").assertDoesNotExist()
    }

    @Test
    fun receiveOpensInQrAndChoosingNfcDisposesTheScanner() {
        val service = receiveService()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ReceiveScreen(
                    service = service,
                    sharing = QuietSharing(),
                    onImported = {},
                    pairing = ReadyNfc(NfcCapabilities(canRead = true, canPresent = true, enabled = true)),
                )
            }
        }

        // QR is the default, and the selector is above the camera gate.
        compose.onNodeWithTag(PairingMethodQrTag).assertIsSelected()
        compose.onNodeWithTag(PairingMethodQrTag).assertIsDisplayed()
        compose.onNodeWithTag(NfcTapPanelTag).assertDoesNotExist()

        compose.onNodeWithTag(PairingMethodNfcTag).performScrollTo().performClick()
        awaitTag(NfcTapPanelTag)

        compose.onNodeWithTag(PairingMethodNfcTag).assertIsSelected()
        compose.onNodeWithTag(NfcTapPanelTag).assertIsDisplayed()
        // The camera is disposed before the reader is armed: no scanner
        // node survives the switch at all.
        compose.onNodeWithTag(ReceiveScannerTag).assertDoesNotExist()
        compose.onNodeWithText("Ready to receive a tap").assertIsDisplayed()
    }
}
