package app.simt.echo.ui.screens.processing

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
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
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.EchoApiError
import app.simt.echo.transfer.JobPoll
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.UploadFile
import app.simt.echo.ui.copy.aiUnavailableCopy
import app.simt.echo.ui.copy.processingErrorCopy
import app.simt.echo.ui.copy.processingJobErrorCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W3-02 gate: consent → upload → poll → Ready, with job.json persistence. */
class ProcessingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeMedia(private val fileSystem: FileSystem) : MediaTools {
        val extracted = mutableListOf<String>()
        val cut = mutableListOf<String>()

        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
            extracted += out.name
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write("frame$atMs".encodeToByteArray()) }
        }

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path): Nothing =
            error("Review crops are not exercised here")

        override suspend fun cutClip(
            video: Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: Path,
        ): ClipInfo {
            cut += out.name
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write("clip".encodeToByteArray()) }
            return ClipInfo(out, frameCount = 12, durationMs = endMs - startMs)
        }
    }

    /** Scripted cloud: captures submits, replays polls or throws. */
    private class FakeCloudApi(
        var samEnabled: Boolean = true,
        var polls: ArrayDeque<Result<JobPoll>> = ArrayDeque(),
        var submitError: EchoApiError? = null,
        var deleteError: EchoApiError? = null,
    ) : CloudApi {
        val submits = mutableListOf<Pair<String, List<UploadFile>>>()
        val deletes = mutableListOf<String>()
        var samCalls = 0
        var getCalls = 0

        override suspend fun submit(manifestJson: String, files: List<UploadFile>): String {
            submitError?.let { throw it }
            submits += manifestJson to files
            return "job-1"
        }

        override suspend fun get(jobId: String): JobPoll {
            getCalls++
            return polls.removeFirstOrNull()?.getOrThrow() ?: JobPoll("running", JobStage.SCOUTING)
        }

        override suspend fun delete(jobId: String) {
            deletes += jobId
            deleteError?.let { throw it }
        }

        override suspend fun samEnabled(): Boolean {
            samCalls++
            return samEnabled
        }
    }

    private data class Harness(
        val store: FileStore,
        val fileSystem: FakeFileSystem,
        val drafts: DraftRepository,
        val media: FakeMedia,
        val draftId: String = "draft-1",
    ) {
        fun vm(
            api: FakeCloudApi,
            pollIntervalMs: Long = 1L,
            maxPolls: Int = 10,
            aiConfigured: Boolean = true,
        ) = ProcessingViewModel(
            draftId = draftId,
            drafts = drafts,
            media = media,
            api = api,
            nowMs = { 1_762_000_100_000L },
            pollIntervalMs = pollIntervalMs,
            maxPolls = maxPolls,
            aiConfigured = aiConfigured,
        )
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

    private fun testLog(): CaptureLog = CaptureLog(
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
    )

    private fun harness(): Harness {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val drafts = DraftRepository(store)
        val media = FakeMedia(fileSystem)
        drafts.saveDraftRoute(draftRoute())
        drafts.saveCapture(testLog())
        val draftDir = "/data/drafts/draft-1".toPath()
        fileSystem.createDirectories(draftDir)
        fileSystem.write(draftDir / "start.jpg") { write("photo".encodeToByteArray()) }
        fileSystem.write(draftDir / "video.mp4") { write("video".encodeToByteArray()) }
        return Harness(store, fileSystem, drafts, media)
    }

    private fun running(stage: JobStage = JobStage.SCOUTING) =
        Result.success(JobPoll("running", stage))

    private fun complete() =
        Result.success(JobPoll("complete", JobStage.COMPLETE, resultRaw = """{"ok":true}"""))

    private fun errored(message: String) =
        Result.success(JobPoll("errored", JobStage.GROUNDING, error = message))

    @Test
    fun consentShowsCountsThenSendUploadsAndPollsToReady() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(
            polls = ArrayDeque(
                listOf(
                    Result.success(JobPoll("queued", JobStage.QUEUED)),
                    running(),
                    complete(),
                ),
            ),
        )
        val vm = h.vm(api)
        advanceUntilIdle()

        val consent = assertIs<ProcessingPhase.Consent>(vm.ui.value.phase)
        assertEquals(5, consent.info.photos)
        assertEquals(2, consent.info.clips)
        assertTrue(consent.info.samEnabled)

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals("job-1", vm.ui.value.jobId)
        // One submit: consent stored, keyframes + both clips attached.
        assertEquals(1, api.submits.size)
        val (manifest, files) = api.submits.single()
        assertTrue(manifest.contains(""""sendFramesToCloudAi":true"""), manifest.take(400))
        assertTrue(manifest.contains(""""draftId":"draft-1""""), manifest.take(400))
        val ids = files.map { it.id }.toSet()
        assertTrue(ids.contains("kf_n1_0"), ids.toString())
        assertTrue(ids.contains("kf_n2_1"), ids.toString())
        assertTrue(ids.contains("clip_n1"), ids.toString())
        assertTrue(ids.contains("clip_n2"), ids.toString())
        // Job persisted for restart-resume; clips were cut for both checkpoints.
        val record = h.drafts.loadJobRecord("draft-1")
        assertNotNull(record)
        assertEquals("job-1", record.jobId)
        assertEquals("complete", record.status)
        assertEquals(listOf("clip_n1.mp4", "clip_n2.mp4"), h.media.cut.sorted())
    }

    @Test
    fun samDisabledSendsKeyframesOnly() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(samEnabled = false, polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api)
        advanceUntilIdle()

        val consent = assertIs<ProcessingPhase.Consent>(vm.ui.value.phase)
        assertEquals(0, consent.info.clips)

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertTrue(h.media.cut.isEmpty())
        val ids = api.submits.single().second.map { it.id }.toSet()
        assertTrue(ids.none { it.startsWith("clip_") }, ids.toString())
    }

    @Test
    fun resumePollsPersistedJobWithoutResubmitting() = runTest(dispatcher) {
        val h = harness()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-9", "running", 0L))
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api)
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals("job-9", vm.ui.value.jobId)
        assertTrue(api.submits.isEmpty())
    }

    @Test
    fun erroredJobShowsMessageAndRetryReturnsToConsentBeforeResubmitting() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(
            polls = ArrayDeque(listOf(errored("scout down"), complete())),
        )
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        val failed = assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        // W7-06: the server's own error text never reaches the screen; the
        // typed copy names what happened, what is safe, and the next action.
        assertEquals(processingJobErrorCopy(), failed.message)
        assertFalse(failed.message.contains("scout down"))

        // Retry deletes the dead job but must not re-upload: the user has
        // to see the consent copy and press Send again.
        vm.retry()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Consent>(vm.ui.value.phase)
        assertEquals(1, api.submits.size)
        assertEquals(listOf("job-1"), api.deletes)
        assertNull(h.drafts.loadJobRecord("draft-1"))

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(2, api.submits.size)
        assertEquals("complete", h.drafts.loadJobRecord("draft-1")?.status)
    }

    @Test
    fun resendAfterRetryReusesCachedClipsAndClampsEndsToDuration() = runTest(dispatcher) {
        val h = harness()
        val windows = mutableListOf<Pair<Long, Long>>()
        val durationAware: MediaTools = object : MediaTools by h.media {
            override suspend fun videoDurationMs(video: Path): Long? = 30_000L
            override suspend fun cutClip(
                video: Path,
                startMs: Long,
                endMs: Long,
                maxFps: Int,
                out: Path,
            ): ClipInfo {
                windows += startMs to endMs
                return h.media.cutClip(video, startMs, endMs, maxFps, out)
            }
        }
        val api = FakeCloudApi(polls = ArrayDeque(listOf(errored("scout down"), complete())))
        val vm = ProcessingViewModel(
            draftId = "draft-1",
            drafts = h.drafts,
            media = durationAware,
            api = api,
            nowMs = { 1_762_000_100_000L },
            pollIntervalMs = 1L,
            maxPolls = 10,
        )
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)

        vm.retry()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(2, api.submits.size)
        // W10-02 G5: the resend reused the cached cuts — one cut per node
        // across both sends, never re-encoded.
        assertEquals(2, windows.size, "expected one cut per node, cache reused: $windows")
        assertEquals(2, h.media.cut.size)
        // Clip ends clamp to the video duration (30 s here): n1 at t=40 s
        // opens [36_000, 41_000] → [36_000, 36_000], n2 at t=60 s opens
        // [56_000, 61_000] → [56_000, 56_000] (the end pulls back to the
        // duration, then up to the start — the pre-existing clipWindow
        // never-invert rule). Both ends sit below their raw t+1_000.
        assertEquals(
            listOf(36_000L to 36_000L, 56_000L to 56_000L),
            windows,
            "clip windows not clamped to duration: $windows",
        )
        // The cached clips still ride the resubmit's manifest.
        val ids = api.submits[1].second.map { it.id }.toSet()
        assertTrue(ids.contains("clip_n1"), ids.toString())
        assertTrue(ids.contains("clip_n2"), ids.toString())
    }

    @Test
    fun sendBeforeConsentAndDoubleSendNeverSubmit() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api)

        // Still Loading: no consent has been shown, so nothing may upload.
        vm.send()
        advanceUntilIdle()
        assertIs<ProcessingPhase.Consent>(vm.ui.value.phase)
        assertTrue(api.submits.isEmpty())

        // A double-tap claims the phase once: exactly one submit.
        vm.send()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(1, api.submits.size)
    }

    @Test
    fun erroredJobOnEntryOffersDeleteAndFailedDeleteKeepsRecord() = runTest(dispatcher) {
        val h = harness()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-9", "errored", 0L))
        val api = FakeCloudApi(deleteError = EchoApiError.Server(500, "boom"))
        val vm = h.vm(api)
        advanceUntilIdle()

        val consent = assertIs<ProcessingPhase.Consent>(vm.ui.value.phase)
        assertTrue(consent.hasJob)
        assertEquals("job-9", vm.ui.value.jobId)

        vm.deleteNow()
        advanceUntilIdle()

        val failed = assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertTrue(failed.message.contains("Could not delete", ignoreCase = true), failed.message)
        // The record survives so the delete can be retried, not orphaned.
        assertEquals("job-9", h.drafts.loadJobRecord("draft-1")?.jobId)
    }

    @Test
    fun transientPollFailuresRecoverWithoutFailingTheJob() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(
            polls = ArrayDeque(
                listOf(
                    Result.failure(EchoApiError.Network(RuntimeException("blip"))),
                    Result.failure(EchoApiError.Server(503, "busy")),
                    running(),
                    complete(),
                ),
            ),
        )
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(1, api.submits.size)
    }

    @Test
    fun unauthorizedSendShowsFailed() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(submitError = EchoApiError.Unauthorized())
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        val failed = assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        // W7-06: the 401 detail stays in code; the screen gets typed copy.
        assertEquals(processingErrorCopy(), failed.message)
        assertNull(h.drafts.loadJobRecord("draft-1"))
    }

    @Test
    fun deleteNowRemovesServerFilesAndRecord() = runTest(dispatcher) {
        val h = harness()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-9", "running", 0L))
        val api = FakeCloudApi(polls = ArrayDeque(List(3) { running(JobStage.READING) }))
        val vm = h.vm(api, maxPolls = 3)
        advanceUntilIdle()

        // Polls exhausted → timed out, but the job id is retained for delete-now.
        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertEquals("job-9", vm.ui.value.jobId)
        vm.deleteNow()
        advanceUntilIdle()

        assertEquals(listOf("job-9"), api.deletes)
        assertNull(h.drafts.loadJobRecord("draft-1"))
        assertIs<ProcessingPhase.Consent>(vm.ui.value.phase)
    }

    @Test
    fun timeoutShowsFailed() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(polls = ArrayDeque(List(5) { running() }))
        val vm = h.vm(api, maxPolls = 3)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        val failed = assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertTrue(failed.message.contains("Timed out", ignoreCase = true), failed.message)
    }

    @Test
    fun unconfiguredShowsHonestStateWithoutTouchingHttp() = runTest(dispatcher) {
        val h = harness()
        // A persisted live job from another build must not render fake
        // "cloud" progress on this unconfigured one.
        h.drafts.saveJobRecord("draft-1", JobRecord("job-9", "running", 0L))
        val api = FakeCloudApi()
        val vm = h.vm(api, aiConfigured = false)
        advanceUntilIdle()

        // W7-07: the honest state, never a network error.
        assertIs<ProcessingPhase.AiUnavailable>(vm.ui.value.phase)
        assertNull(vm.ui.value.jobId)
        assertEquals(
            "Landmark AI isn't set up on this build. Your draft is safe on this phone. Continue without AI.",
            aiUnavailableCopy(),
        )
        // No HTTP attempt: not even the SAM probe, no poll resume.
        assertEquals(0, api.samCalls, "SAM probe must not run unconfigured")
        assertEquals(0, api.getCalls, "poll resume must not run unconfigured")
        assertTrue(api.submits.isEmpty())

        // Send is a no-op there: still no submit, still honest.
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.AiUnavailable>(vm.ui.value.phase)
        assertTrue(api.submits.isEmpty())
        assertEquals(0, api.samCalls)
        assertEquals(0, api.getCalls)
    }

    @Test
    fun unconfiguredStillReportsAGoneDraft() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi()
        val missing = h.copy(draftId = "draft-gone")
        val vm = missing.vm(api, aiConfigured = false)
        advanceUntilIdle()

        // The draft check is local disk, not HTTP: a gone draft still
        // says so instead of blaming the missing AI setup.
        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertEquals(0, api.samCalls)
        assertTrue(api.submits.isEmpty())
    }
}
