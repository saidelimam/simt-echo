package app.simt.echo.ui.screens.record

import android.Manifest
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import app.simt.echo.capture.CaptureHud
import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.CaptureState
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.platform.CameraPermissionGate
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.components.CosmosFieldTag
import app.simt.echo.ui.components.CosmosSheetTag
import app.simt.echo.ui.theme.EchoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.PI
import kotlin.test.assertEquals

/**
 * W8-07 Record-in-Cosmos gate: personal-orrery grammar (pre-start overlay,
 * top chips, tabular distance, heading tick, 88 dp Start, Mark spot,
 * long-press Finish, Cosmos Finish sheet), the permission gate, and the
 * full READY → RECORDING → FINISHING → saved journey through the real
 * [RecordViewModel] + [CaptureSession] path. [CaptureSession] behaviour is
 * pinned by its own untouched [app.simt.echo.capture.CaptureSessionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class HostRecordInstrumentTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private class FakeMotion : MotionSource {
        val flow = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 64)
        override val updates: Flow<MotionUpdate> = flow
        override fun start() = Unit
        override fun stop() = Unit
    }

    private class FakeCamera : CameraRecorder {
        private var videoPath: Path? = null

        @androidx.compose.runtime.Composable
        override fun Preview(modifier: androidx.compose.ui.Modifier, onError: (String) -> Unit) = Unit

        override suspend fun startVideo(out: Path): Long {
            videoPath = out
            return 0L
        }

        override suspend fun stopVideo(): Path = videoPath ?: error("no video")
    }

    private class FakeHaptics : Haptics {
        override fun play(pattern: HapticPattern) = Unit
    }

    private fun instrument(
        state: CaptureState,
        headingRad: Double? = null,
        error: String? = null,
        onSave: (String, String) -> Unit = { _, _ -> },
        onStart: () -> Unit = {},
        onMark: () -> Unit = {},
        onBeginFinish: () -> Unit = {},
    ) {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                RecordInstrument(
                    state = state,
                    hud = CaptureHud(distanceM = 23.4, steps = 31, elapsedMs = 65_000L, marks = 2),
                    headingRad = headingRad,
                    error = error,
                    onStart = onStart,
                    onMark = onMark,
                    onBeginFinish = onBeginFinish,
                    onSave = onSave,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun headingTick_formatsCardinalPoints() {
        assertEquals("0° N", formatHeadingTick(0.0))
        assertEquals("90° E", formatHeadingTick(PI / 2))
        assertEquals("180° S", formatHeadingTick(PI))
        assertEquals("270° W", formatHeadingTick(-PI / 2))
        assertEquals("45° NE", formatHeadingTick(PI / 4))
    }

    @Test
    fun ready_showsPrestartChipsDistanceAndHeadingSlot() {
        instrument(CaptureState.READY)
        compose.onNodeWithTag(RecordPrestartTag).assertIsDisplayed()
        compose.onNodeWithText("Stand at the start").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithText("31 steps").assertIsDisplayed()
        compose.onNodeWithText("2 spots").assertIsDisplayed()
        compose.onNodeWithTag(RollingDistanceTag).assertIsDisplayed()
        compose.onNodeWithTag(RollingDistanceTag).assertContentDescriptionEquals("Distance 23.4 m")
        compose.onNodeWithTag(RecordHeadingTag).assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("Mark spot").assertDoesNotExist()
    }

    @Test
    fun idle_showsPrestartToo() {
        instrument(CaptureState.IDLE)
        compose.onNodeWithTag(RecordPrestartTag).assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test
    fun startButton_firesOnStart() {
        var started = false
        instrument(CaptureState.READY, onStart = { started = true })
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        kotlin.test.assertTrue(started)
    }

    @Test
    fun recording_showsMarkSpotAndHoldToFinish() {
        instrument(CaptureState.RECORDING, headingRad = PI / 2)
        compose.onNodeWithText("Mark spot").assertIsDisplayed()
        compose.onNodeWithText("Hold to finish").assertIsDisplayed()
        compose.onNodeWithTag(RecordPrestartTag).assertDoesNotExist()
        compose.onNodeWithText("90° E").assertIsDisplayed()
    }

    @Test
    fun holdToFinish_firesOnBeginFinish() {
        var finished = false
        instrument(CaptureState.RECORDING, onBeginFinish = { finished = true })
        // Long-press is the gesture (a tap must not finish); performClick
        // injects a tap on Android, so the real gesture is asserted here.
        compose.onNodeWithText("Hold to finish").performTouchInput { longClick() }
        compose.waitForIdle()
        kotlin.test.assertTrue(finished)
    }

    @Test
    fun staleSensor_keepsSameGeometry() {
        var heading: Double? by mutableStateOf(null)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                var current by remember { mutableStateOf(heading) }
                current = heading
                RecordInstrument(
                    state = CaptureState.RECORDING,
                    hud = CaptureHud(),
                    headingRad = current,
                    error = null,
                    onStart = {},
                    onMark = {},
                    onBeginFinish = {},
                    onSave = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(RecordHeadingTag).assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.runOnIdle { heading = 0.0 }
        compose.waitForIdle()
        compose.onNodeWithTag(RecordHeadingTag).assertIsDisplayed()
        compose.onNodeWithText("0° N").assertIsDisplayed()
        compose.onNodeWithText("0:00").assertIsDisplayed()
        compose.onNodeWithTag(RollingDistanceTag).assertContentDescriptionEquals("Distance 0.0 m")
    }

    @Test
    fun finishing_showsCosmosSheetWithOneContinue() {
        var saved: Pair<String, String>? = null
        instrument(CaptureState.FINISHING, onSave = { name, destination -> saved = name to destination })
        compose.onNodeWithTag(CosmosSheetTag).assertIsDisplayed()
        compose.onNodeWithText("Name this walk").assertIsDisplayed()
        compose.onNodeWithText("Route name").assertIsDisplayed()
        compose.onNodeWithText("Destination label").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Save ECHO draft").assertDoesNotExist()
        compose.onNodeWithTag("$CosmosFieldTag:Route name").performTextInput("Evening stroll")
        compose.onNodeWithTag("$CosmosFieldTag:Destination label").performTextInput("Park loop")
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals("Evening stroll" to "Park loop", saved)
    }

    /**
     * Regression: the finish form could not be dismissed at all. Its scrim and
     * system back (the same ModalBottomSheet dismiss path this test drives) ran
     * a deliberate no-op, so a stopped walk sat on a form no gesture could
     * close — a stuck screen. Dismiss must leave.
     */
    @Test
    fun finishing_dismissLeavesInsteadOfStrandingTheWalk() {
        var abandoned = 0
        compose.setContent {
            EchoTheme(darkTheme = true) {
                RecordInstrument(
                    state = CaptureState.FINISHING,
                    hud = CaptureHud(distanceM = 23.4, steps = 31, elapsedMs = 65_000L, marks = 1),
                    headingRad = null,
                    error = null,
                    onStart = {},
                    onMark = {},
                    onBeginFinish = {},
                    onSave = { _, _ -> },
                    onAbandon = { abandoned++ },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Name this walk").assertIsDisplayed()

        // Dragging the sheet away is the same dismiss path the system back
        // gesture feeds (ModalBottomSheet.onDismissRequest). Before the fix
        // that dismiss was a no-op, so the form could not be closed at all.
        compose.onNodeWithTag(CosmosSheetTag).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, abandoned, "dismissing the finish form must leave the screen")
    }

    @Test
    fun permissionGate_deniedShowsRationaleNeverTheInstrument() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CameraPermissionGate {
                    RecordInstrument(
                        state = CaptureState.READY,
                        hud = CaptureHud(),
                        headingRad = null,
                        error = null,
                        onStart = {},
                        onMark = {},
                        onBeginFinish = {},
                        onSave = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Camera needed").assertIsDisplayed()
        compose.onNodeWithText("Allow camera").assertIsDisplayed()
        compose.onNodeWithTag(RecordInstrumentTag).assertDoesNotExist()
    }

    @Test
    fun permissionGate_grantedRendersTheInstrument() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.CAMERA)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                CameraPermissionGate {
                    RecordInstrument(
                        state = CaptureState.READY,
                        hud = CaptureHud(),
                        headingRad = null,
                        error = null,
                        onStart = {},
                        onMark = {},
                        onBeginFinish = {},
                        onSave = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(RecordInstrumentTag).assertIsDisplayed()
        compose.onNodeWithText("Stand at the start").assertIsDisplayed()
        compose.onNodeWithText("Camera needed").assertDoesNotExist()
    }

    @Test
    fun journey_readyToSavedDrivesSheetAndHandoff() {
        val dir = Files.createTempDirectory("w8-07-record").toString().toPath()
        val drafts = DraftRepository(FileStore(FileSystem.SYSTEM, dir))
        val session = CaptureSession(
            motion = FakeMotion(),
            camera = FakeCamera(),
            haptics = FakeHaptics(),
            drafts = drafts,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            nowMs = { 1_762_000_000_000L },
            newDraftId = { "draft-7" },
            autosaveEveryMs = Long.MAX_VALUE,
        )
        val viewModel = RecordViewModel(session)
        var navigated: String? = null
        compose.setContent {
            EchoTheme(darkTheme = true) {
                val uiState by viewModel.uiState.collectAsState()
                RecordInstrument(
                    state = uiState.state,
                    hud = uiState.hud,
                    headingRad = null,
                    error = uiState.error,
                    onStart = viewModel::start,
                    onMark = viewModel::mark,
                    onBeginFinish = viewModel::beginFinish,
                    onSave = { name, destination ->
                        viewModel.finish(name, destination) { navigated = it }
                    },
                )
            }
        }
        viewModel.prepare(null)
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Start").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Start").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Mark spot").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Mark spot").performClick()
        compose.onNodeWithText("Hold to finish").performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Continue").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("$CosmosFieldTag:Route name").performTextInput("Evening stroll")
        compose.onNodeWithTag("$CosmosFieldTag:Destination label").performTextInput("Park loop")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { navigated != null }
        compose.runOnIdle {
            assertEquals("draft-7", navigated)
            assertEquals(CaptureState.SAVED, viewModel.uiState.value.state)
        }
    }

    @Test
    fun errorBanner_rendersHumanCopyAboveDistance() {
        instrument(CaptureState.RECORDING, error = "Could not start recording. Nothing was captured. Check the camera and try again.")
        compose.onNodeWithText("Could not start recording. Nothing was captured. Check the camera and try again.").assertIsDisplayed()
        compose.onNodeWithTag(RollingDistanceTag).assertContentDescriptionEquals("Distance 23.4 m")
    }

    @Test
    fun formatElapsed_minutesAndSeconds() {
        assertEquals("0:00", formatElapsed(0L))
        assertEquals("1:05", formatElapsed(65_000L))
    }
}
