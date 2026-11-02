package app.simt.echo.ui.screens

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import app.simt.echo.capture.CaptureSession
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.reviewAfterRecord
import app.simt.echo.ui.screens.record.RecordViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertEquals

/**
 * W7-05 Record → Review handoff through the real finish path: saving the
 * finish sheet navigates directly to Review for the just-saved draft —
 * never a pop-to-Home. [CaptureSession] itself is untouched (its own
 * [app.simt.echo.capture.CaptureSessionTest] stays green); this suite
 * pins the [RecordViewModel.finish] → [reviewAfterRecord] wiring that
 * [app.simt.echo.ui.EchoNavHost] navigates with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class HostRecordReviewHandoffTest {

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

        override suspend fun takePhoto(out: Path): Path = out

        override suspend fun startVideo(out: Path): Long {
            videoPath = out
            return 0L
        }

        override suspend fun stopVideo(): Path = videoPath ?: error("no video")
    }

    private class FakeHaptics : Haptics {
        override fun play(pattern: HapticPattern) = Unit
    }

    @Test
    fun reviewAfterRecord_carriesSavedDraftId() {
        assertEquals(
            EchoRoute.Review("draft-7"),
            reviewAfterRecord("draft-7"),
        )
    }

    @Test
    fun finish_forwardsSavedDraftIdToReview() {
        val dir = Files.createTempDirectory("w7-05-handoff").toString().toPath()
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
        runBlocking {
            session.ready()
            session.start()
            session.beginFinish()
        }
        val viewModel = RecordViewModel(session)

        var navigated: EchoRoute.Review? = null
        viewModel.finish("Evening stroll", "Park loop") { draftId ->
            navigated = reviewAfterRecord(draftId)
        }

        compose.waitUntil(timeoutMillis = 5_000) { navigated != null }
        compose.runOnIdle {
            assertEquals(EchoRoute.Review("draft-7"), navigated)
        }
    }
}
