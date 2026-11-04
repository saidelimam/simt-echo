package app.simt.echo.ui.screens.record

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.click
import app.simt.echo.capture.CaptureHud
import app.simt.echo.capture.CaptureState
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.platform.CameraPermissionStatus
import app.simt.echo.platform.cameraGateCopy
import app.simt.echo.ui.screens.record.EchoWalkSnapshot
import app.simt.echo.ui.screens.record.TrailNodeKind
import app.simt.echo.ui.screens.record.TrailPoint
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 0013 F5 (R13-05): the live path belongs to the HUD, a tap on
 * Finish explains the hold instead of doing nothing, the control can take
 * keyboard focus, and "not asked yet" is never worded as a denial.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class RecordHudA11yTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private val walked = EchoWalkSnapshot(
        trail = listOf(
            TrailPoint(id = 1L, distanceM = 0.0, yawRad = 0.0, node = TrailNodeKind.START),
            TrailPoint(id = 2L, distanceM = 1.0, yawRad = 0.0),
        ),
    )

    private fun instrument(
        state: CaptureState,
        walk: EchoWalkSnapshot = walked,
        onFinish: () -> Unit = {},
    ) {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                RecordInstrument(
                    state = state,
                    hud = CaptureHud(distanceM = 23.4, steps = 31, elapsedMs = 65_000L, marks = 1),
                    headingRad = 0.0,
                    error = null,
                    onStart = {},
                    onMark = {},
                    onBeginFinish = onFinish,
                    onSave = { _, _ -> },
                    walk = walk,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun hudAtFontScaleTwoKeepsChipsTrailAndFinishApart() {
        val big = Density(compose.density.density, fontScale = 2.0f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides big) {
                EchoTheme(darkTheme = true) {
                    RecordInstrument(
                        state = CaptureState.RECORDING,
                        hud = CaptureHud(distanceM = 23.4, steps = 31, elapsedMs = 65_000L, marks = 1),
                        headingRad = 0.0,
                        error = null,
                        onStart = {},
                        onMark = {},
                        onBeginFinish = {},
                        onSave = { _, _ -> },
                        walk = walked,
                    )
                }
            }
        }
        compose.waitForIdle()
        listOf("1:05", "31 steps", "1 spots").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithTag(TrailRibbonTag).assertIsDisplayed()
        val finish = compose.onNodeWithContentDescription("Hold to finish")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val trail = compose.onNodeWithTag(TrailRibbonTag).fetchSemanticsNode().boundsInRoot
        assertFalse(
            trail.overlaps(finish),
            "the trail panel $trail collides with Finish $finish at fontScale 2.0",
        )
    }

    @Test
    fun readyHasNoTrailPanelInTheHud() {
        instrument(CaptureState.READY)
        compose.onNodeWithTag(TrailRibbonTag).assertDoesNotExist()
        compose.onNodeWithTag(RecordPrestartTag).assertIsDisplayed()
    }

    @Test
    fun recordingShowsTheTrailPanelInTheHud() {
        instrument(CaptureState.RECORDING)
        compose.onNodeWithTag(TrailRibbonTag).assertIsDisplayed()
    }

    @Test
    fun tapExplainsTheHoldAndDoesNotFinish() {
        var finished = false
        instrument(CaptureState.RECORDING, onFinish = { finished = true })
        compose.onNodeWithText("Hold to finish").performTouchInput { click() }
        compose.waitForIdle()
        assertFalse(finished, "a tap must not finish the walk")
        compose.onNodeWithText("Hold for a second to finish").assertIsDisplayed()
    }

    @Test
    fun finishControlCanTakeKeyboardFocus() {
        instrument(CaptureState.RECORDING)
        compose.onNodeWithContentDescription("Hold to finish").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hold to finish").assertIsFocused()
    }

    @Test
    fun notDeterminedCopyIsNeutralAndPermanentDenialOffersBothPaths() {
        val neutral = assertNotNull(cameraGateCopy(CameraPermissionStatus.NOT_DETERMINED))
        assertTrue(neutral.body.contains("has not asked"))
        assertFalse(neutral.body.contains("denied", ignoreCase = true))
        assertEquals("Allow camera", neutral.actionLabel)

        val permanent = assertNotNull(
            cameraGateCopy(CameraPermissionStatus.PERMANENTLY_DENIED),
        )
        assertEquals("Open settings", permanent.actionLabel)
        assertEquals("Try again", permanent.secondaryLabel)
        assertTrue(permanent.opensSettings)

        assertNull(cameraGateCopy(CameraPermissionStatus.GRANTED))
    }
}
