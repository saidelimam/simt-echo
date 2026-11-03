package app.simt.echo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.transfer.NfcReadiness
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingMethod
import app.simt.echo.transfer.PairingRole
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.components.NfcTapPanel
import app.simt.echo.ui.components.NfcTapPanelStatusTag
import app.simt.echo.ui.components.PairingMethodNfcTag
import app.simt.echo.ui.components.PairingMethodQrTag
import app.simt.echo.ui.components.PairingMethodSelector
import app.simt.echo.ui.components.PairingRecoveryTag
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertTrue

/**
 * W21-03 gate: the selector is one selectable group with real targets, it
 * survives 200 % text and RTL without truncating, and the tap panel
 * announces its status once while every recovery action stays reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class PairingAccessibilityTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun showSelector(
        role: PairingRole,
        selected: PairingMethod,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        enabled: Boolean = true,
    ) {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    // 320 dp: the narrowest phone this ships on.
                    Box(modifier = Modifier.width(320.dp)) {
                        PairingMethodSelector(
                            role = role,
                            selected = selected,
                            enabled = enabled,
                            onSelect = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showPanel(
        readiness: NfcReadiness,
        failure: PairingFailure? = null,
        tier: CosmosMotionTier = CosmosMotionTier.FULL,
    ) {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                NfcTapPanel(
                    role = PairingRole.SHARE,
                    readiness = readiness,
                    listening = readiness == NfcReadiness.ARMED,
                    tapRead = false,
                    failure = failure,
                    onOpenNfcSettings = {},
                    onUseQrInstead = {},
                    tier = tier,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun selectorExposesSelectionAndMinimumTargets() {
        showSelector(PairingRole.SHARE, PairingMethod.NFC)

        compose.onNodeWithTag(PairingMethodNfcTag)
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
        compose.onNodeWithTag(PairingMethodQrTag)
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
    }

    @Test
    fun selectorStacksInsteadOfTruncatingAtDoubleText() {
        showSelector(PairingRole.RECEIVE, PairingMethod.QR, fontScale = 2f)

        compose.onNodeWithText("QR · Scan code").assertIsDisplayed()
        compose.onNodeWithText("NFC · Tap phones").assertIsDisplayed()
        val qr = compose.onNodeWithTag(PairingMethodQrTag).fetchSemanticsNode().boundsInRoot
        val nfc = compose.onNodeWithTag(PairingMethodNfcTag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            nfc.bottom <= qr.top + 1f || qr.bottom <= nfc.top + 1f,
            "stacked options must not overlap: qr=$qr nfc=$nfc",
        )
    }

    @Test
    fun selectorKeepsBothOptionsInRtl() {
        showSelector(PairingRole.SHARE, PairingMethod.NFC, rtl = true)
        compose.onNodeWithTag(PairingMethodNfcTag).assertIsDisplayed()
        compose.onNodeWithTag(PairingMethodQrTag).assertIsDisplayed()
    }

    @Test
    fun tapPanelAnnouncesItsStatusPolitely() {
        showPanel(readiness = NfcReadiness.ARMED)
        compose.onNodeWithText("Ready to tap").assertIsDisplayed()
        val status = compose.onNodeWithTag(NfcTapPanelStatusTag).fetchSemanticsNode()
        val region = status.config.getOrNull(SemanticsProperties.LiveRegion)
        assertTrue(
            region == LiveRegionMode.Polite,
            "Ready must be announced once, not on every recomposition: $region",
        )
    }

    @Test
    fun verificationFailureIsNeverNetworkAdvice() {
        showPanel(readiness = NfcReadiness.ARMED, failure = PairingFailure.Verification)
        compose.onNodeWithText("Couldn’t verify this route.").assertIsDisplayed()
        compose.onNodeWithText("Join the same Wi-Fi", substring = true).assertDoesNotExist()
    }

    @Test
    fun disabledAdapterOffersSettingsAndQr() {
        showPanel(readiness = NfcReadiness.DISABLED)
        compose.onNodeWithText("Turn on NFC to tap").assertIsDisplayed()
        compose.onNodeWithText("Open NFC settings").assertIsDisplayed()
        compose.onNodeWithText("Use QR instead").assertIsDisplayed()
        compose.onNodeWithTag(PairingRecoveryTag).assertIsDisplayed()
    }

    @Test
    fun reducedMotionRendersAStaticPanel() {
        showPanel(readiness = NfcReadiness.ARMED, tier = CosmosMotionTier.REDUCED)
        compose.onNodeWithText("Ready to tap").assertIsDisplayed()
    }

    @Test
    fun motionOffStillExplainsTheState() {
        showPanel(readiness = NfcReadiness.ARMED, tier = CosmosMotionTier.STATIC)
        compose.onNodeWithText("Ready to tap").assertIsDisplayed()
        compose.onNodeWithText("Ask the other person", substring = true).assertIsDisplayed()
    }
}
