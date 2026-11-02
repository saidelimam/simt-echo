package app.simt.echo.ui.screens.record

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.simt.echo.capture.CaptureHud
import app.simt.echo.capture.CaptureState
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W11-09 gate: the pre-start overlay offers the opt-in (default off)
 * and the consent sheet claims Start before recording ever begins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class HostRecordReadyConsentTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun instrument(
        readyOptIn: ReadyOptInUiState = ReadyOptInUiState(),
        onStart: () -> Unit = {},
        onOptInChange: (Boolean) -> Unit = {},
        onGrantConsent: () -> Unit = {},
        onDismissConsent: () -> Unit = {},
        onDeclineConsent: () -> Unit = {},
    ) {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                RecordInstrument(
                    state = CaptureState.READY,
                    hud = CaptureHud(),
                    headingRad = null,
                    error = null,
                    onStart = onStart,
                    onMark = {},
                    onBeginFinish = {},
                    onSave = { _, _ -> },
                    readyOptIn = readyOptIn,
                    onOptInChange = onOptInChange,
                    onGrantConsent = onGrantConsent,
                    onDismissConsent = onDismissConsent,
                    onDeclineConsent = onDeclineConsent,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun overlay_offersOptInUncheckedByDefault() {
        instrument()
        compose.onNodeWithTag(RecordPrestartTag).assertIsDisplayed()
        compose.onNodeWithText("Find landmarks while I walk · Wi-Fi only").assertIsDisplayed()
        compose.onNodeWithTag(RecordOptInTag).assertIsOff()
        compose.onNodeWithText("Find landmarks while I walk").assertDoesNotExist()
    }

    @Test
    fun togglingOptIn_reportsChange() {
        var changed: Boolean? = null
        instrument(onOptInChange = { changed = it })
        compose.onNodeWithTag(RecordOptInTag).performClick()
        assertEquals(true, changed)
    }

    @Test
    fun consentPending_showsSheetBeforeRecording() {
        var granted = false
        var declined = false
        instrument(
            readyOptIn = ReadyOptInUiState(optIn = true, consentPending = true),
            onGrantConsent = { granted = true },
            onDeclineConsent = { declined = true },
        )
        compose.onNodeWithTag(RecordOptInTag).assertIsOn()
        compose.onNodeWithText("Find landmarks while I walk").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNodeWithText("Allow").performClick()
        assertTrue(granted)
        assertEquals(false, declined)
    }

    @Test
    fun notNow_declinesWithoutGranting() {
        var granted = false
        var declined = false
        instrument(
            readyOptIn = ReadyOptInUiState(optIn = true, consentPending = true),
            onGrantConsent = { granted = true },
            onDeclineConsent = { declined = true },
        )
        compose.onNodeWithText("Not now").performClick()
        assertTrue(declined)
        assertEquals(false, granted)
    }

    @Test
    fun startButton_stillFiresThroughConsentGate() {
        var starts = 0
        instrument(onStart = { starts++ })
        compose.onNodeWithText("Start").performClick()
        assertEquals(1, starts)
    }
}
