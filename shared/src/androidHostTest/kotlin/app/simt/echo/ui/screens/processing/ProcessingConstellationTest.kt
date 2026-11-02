package app.simt.echo.ui.screens.processing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.CropBox
import app.simt.echo.media.MediaTools
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import app.simt.echo.transfer.CreatedUpload
import app.simt.echo.transfer.JobEvent
import app.simt.echo.transfer.JobPoll
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.UploadFile
import app.simt.echo.transfer.UploadFileRef
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.ProvideCosmosMotionTier
import app.simt.echo.ui.theme.EchoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W13-06 Processing "Constellation" gate:
 *
 * - the Working job renders one star per checkpoint with shape + label
 *   encoding (never hue alone), an honest stage header, the frame-budget
 *   meter fed by `usage` events, Cancel and Continue without AI;
 * - Cancel / Continue without AI both leave through `onDone` without
 *   touching the persisted job (re-entering resumes);
 * - STATIC tier shows the same stars, labels and meter with no animation
 *   (parity: no information lives in the twinkle).
 *
 * The streams hang open (`awaitCancellation`) so the job under test never
 * reaches a terminal phase mid-assertion; no flow here leaves a SCOUTING
 * star on FULL (the infinite twinkle would hold the compose clock).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class ProcessingConstellationTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private class FakeMedia : MediaTools {
        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
            out.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(out) { write("frame$atMs".encodeToByteArray()) }
        }

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path): Nothing =
            error("not exercised here")

        override suspend fun cutClip(
            video: Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: Path,
        ): ClipInfo {
            out.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(out) { write("clip".encodeToByteArray()) }
            return ClipInfo(out, frameCount = 12, durationMs = endMs - startMs)
        }
    }

    /** Scripted v1.1 cloud: one hanging stream per test, never terminal. */
    private class FakeV11(val events: List<JobEvent>) : V11CloudApi {
        override suspend fun submit(manifestJson: String, files: List<UploadFile>): String = "job-1"
        override suspend fun createUpload(files: List<UploadFileRef>): CreatedUpload =
            CreatedUpload("upload-1", files.map { it.name })

        override suspend fun uploadMissing(uploadId: String): List<String> = emptyList()

        override suspend fun putUploadFile(uploadId: String, file: UploadFile) = Unit

        override suspend fun createJob(manifestJson: String, uploadId: String, idempotencyKey: String): String =
            "job-1"

        override suspend fun deleteUpload(uploadId: String) = Unit

        override suspend fun createJobFromStaged(
            manifestJson: String,
            uploadIds: List<String>,
            idempotencyKey: String,
        ): String = "job-1"

        override fun watchEvents(jobId: String, lastEventId: String?): Flow<JobEvent> = flow {
            events.forEach { emit(it) }
            awaitCancellation()
        }

        override suspend fun get(jobId: String): JobPoll = JobPoll("running", JobStage.SCOUTING)
        override suspend fun delete(jobId: String) = Unit
        override suspend fun samEnabled(): Boolean = true
    }

    private fun draftRoute(): EchoRoute = EchoRoute(
        routeId = "draft-1",
        name = "Test walk",
        createdAtEpochMs = 1_762_000_000_000L,
        creatorLabel = "creator",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 45.0,
        start = StartAnchor("Entrance", "start.jpg", "Face north"),
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Entrance", captureTMs = 0L),
            RouteNode(
                id = "n1", type = NodeType.TURN, atDistanceM = 20.0, label = "pillar",
                turn = TurnDir.RIGHT, cue = "Turn right at the pillar", captureTMs = 40_000L,
            ),
            RouteNode(
                id = "n2", type = NodeType.DESTINATION, atDistanceM = 45.0, label = "Elevator",
                cue = "Arrive at Elevator", captureTMs = 60_000L,
            ),
        ),
        legs = listOf(RouteLeg("n0", "n1", 20.0, 0.0), RouteLeg("n1", "n2", 25.0, 90.0)),
        path = listOf(PathPoint(0.0, 0.0, 0.0), PathPoint(45.0, 15.0, 20.0)),
    )

    private fun liveVm(events: List<JobEvent>): Triple<ProcessingViewModel, FakeV11, DraftRepository> {
        val dir = Files.createTempDirectory("w13-06-constellation").toString().toPath()
        val drafts = DraftRepository(FileStore(FileSystem.SYSTEM, dir))
        drafts.saveDraftRoute(draftRoute())
        drafts.saveCapture(
            CaptureLog(
                draftId = "draft-1",
                startedAtEpochMs = 1_762_000_000_000L,
                motionSource = "echo_builtin_pdr",
                videoStartNs = 0L,
                samples = listOf(
                    CaptureSample(tNs = 0L, d = 0.0, yaw = 0.0),
                    CaptureSample(tNs = 40_000_000_000L, d = 20.0, yaw = 0.0),
                    CaptureSample(tNs = 60_000_000_000L, d = 45.0, yaw = 1.5),
                ),
                marks = emptyList(),
                startPhoto = "start.jpg",
                routeName = "Test walk",
                destinationLabel = "Elevator",
            ),
        )
        val draftDir = dir.resolve("drafts/draft-1")
        FileSystem.SYSTEM.createDirectories(draftDir)
        FileSystem.SYSTEM.write(draftDir.resolve("start.jpg")) { write("photo".encodeToByteArray()) }
        FileSystem.SYSTEM.write(draftDir.resolve("video.mp4")) { write("video".encodeToByteArray()) }
        // A persisted live job: refresh resumes the stream, never resubmits.
        drafts.saveJobRecord("draft-1", JobRecord("job-9", "running", 0L))
        val api = FakeV11(events)
        val vm = ProcessingViewModel(
            draftId = "draft-1",
            drafts = drafts,
            media = FakeMedia(),
            api = api,
            nowMs = { 1_762_000_100_000L },
            pollIntervalMs = 1L,
            maxPolls = 10,
            ioDispatcher = Dispatchers.Unconfined,
        )
        return Triple(vm, api, drafts)
    }

    private fun groundedFlow(): List<JobEvent> = listOf(
        JobEvent.Stage("evt-0", JobStage.GROUNDING),
        JobEvent.Checkpoint("evt-1", "n1", "grounded", "[]"),
        JobEvent.Checkpoint("evt-2", "n2", "skipped", "[]"),
        JobEvent.Usage("u-1", samFramesBilled = 38),
    )

    @Test
    fun workingRendersConstellationWithHonestHeader() {
        val (vm, _, _) = liveVm(groundedFlow())
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.ui.value.phase is ProcessingPhase.Working &&
                vm.constellation.value.stars.any { it.state == StarState.GROUNDED }
        }

        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProcessingScreen(viewModel = vm, onDone = {})
            }
        }

        // Honest header: the live stage, the metered budget, both exits.
        compose.onNodeWithText("Grounding objects…").assertIsDisplayed()
        compose.onNodeWithTag(ProcessingBudgetMeterTag).assertIsDisplayed()
        compose.onNodeWithText("38 / 120 frames").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Continue without AI").assertIsDisplayed()
        // One star per checkpoint, shape + label each (never hue alone).
        compose.onNodeWithTag(ProcessingConstellationTag).assertIsDisplayed()
        compose.onNodeWithTag(ProcessingStarTagPrefix + "n1").assertIsDisplayed()
        compose.onNodeWithTag(ProcessingStarTagPrefix + "n2").assertIsDisplayed()
        compose.onNodeWithContentDescription("pillar — Grounded").assertIsDisplayed()
        compose.onNodeWithContentDescription("Elevator — Skipped").assertIsDisplayed()
        compose.onNodeWithText("Grounded").assertIsDisplayed()
        // The lower star's label sits below the headless fold: scroll first.
        compose.onNodeWithText("Skipped").performScrollTo()
        compose.onNodeWithText("Skipped").assertIsDisplayed()
    }

    @Test
    fun cancelLeavesThroughDoneWithJobPersisted() {
        val (vm, _, drafts) = liveVm(groundedFlow())
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.ui.value.phase is ProcessingPhase.Working &&
                vm.constellation.value.stars.any { it.state == StarState.GROUNDED }
        }
        var done = false

        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProcessingScreen(viewModel = vm, onDone = { done = true })
            }
        }

        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertTrue(done, "Cancel must leave through onDone")
            // The job stays persisted so re-entering resumes the stream.
            assertEquals("job-9", assertNotNull(drafts.loadJobRecord("draft-1")).jobId)
        }
    }

    @Test
    fun continueWithoutAiLeavesThroughDoneWithJobPersisted() {
        val (vm, _, drafts) = liveVm(groundedFlow())
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.ui.value.phase is ProcessingPhase.Working &&
                vm.constellation.value.stars.any { it.state == StarState.GROUNDED }
        }
        var done = false

        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProcessingScreen(viewModel = vm, onDone = { done = true })
            }
        }

        compose.onNodeWithText("Continue without AI").performClick()
        compose.runOnIdle {
            assertTrue(done, "Continue without AI must leave through onDone")
            assertEquals("job-9", assertNotNull(drafts.loadJobRecord("draft-1")).jobId)
        }
    }

    @Test
    fun staticTierShowsSameStarsLabelsAndMeter() {
        val events = listOf(
            JobEvent.Stage("evt-0", JobStage.SCOUTING),
            JobEvent.Checkpoint("evt-1", "n1", "scouted", "[]"),
            JobEvent.Usage("u-1", samFrames = 12),
        )
        val (vm, _, _) = liveVm(events)
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.ui.value.phase is ProcessingPhase.Working &&
                vm.constellation.value.stars.any { it.state == StarState.SCOUTING }
        }

        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(CosmosMotionTier.STATIC) {
                    ProcessingScreen(viewModel = vm, onDone = {})
                }
            }
        }

        // Parity: identical information, no twinkle — the scouting star is
        // static here, so the compose clock stays idle and this asserts.
        compose.onNodeWithText("Reading scenes…").assertIsDisplayed()
        compose.onNodeWithText("12 / 120 frames").assertIsDisplayed()
        compose.onNodeWithTag(ProcessingConstellationTag).assertIsDisplayed()
        compose.onNodeWithTag(ProcessingStarTagPrefix + "n1").assertIsDisplayed()
        compose.onNodeWithTag(ProcessingStarTagPrefix + "n2").assertIsDisplayed()
        compose.onNodeWithContentDescription("pillar — Scouting").assertIsDisplayed()
        compose.onNodeWithContentDescription("Elevator — Queued").assertIsDisplayed()
        compose.onNodeWithText("Scouting").assertIsDisplayed()
        // The lower star's label sits below the headless fold: scroll first.
        compose.onNodeWithText("Queued").performScrollTo()
        compose.onNodeWithText("Queued").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Continue without AI").assertIsDisplayed()
    }
}
