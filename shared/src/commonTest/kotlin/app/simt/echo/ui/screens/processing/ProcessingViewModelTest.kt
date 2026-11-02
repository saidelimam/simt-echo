package app.simt.echo.ui.screens.processing

import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.media.CLIP_FORGE_FPS
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.CropBox
import app.simt.echo.media.MediaTools
import app.simt.echo.media.clipSidecarFileName
import app.simt.echo.media.clipSidecarId
import app.simt.echo.media.decodeFrameMap
import app.simt.echo.media.sha256Hex
import app.simt.echo.media.Manifest
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import app.simt.echo.transfer.EchoApiError
import app.simt.echo.transfer.JobEvent
import app.simt.echo.transfer.JobPoll
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.UploadFile
import app.simt.echo.transfer.UploadFileRef
import app.simt.echo.transfer.CreatedUpload
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.transfer.uploadTimeoutMs
import app.simt.echo.ui.copy.aiUnavailableCopy
import app.simt.echo.ui.copy.processingErrorCopy
import app.simt.echo.ui.copy.processingJobErrorCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
        /** Names the server already holds (resume-skips-present scripts). */
        var staged: MutableSet<String> = mutableSetOf(),
        /** One flow per [watchEvents] call; empty when exhausted. */
        var flows: ArrayDeque<Flow<JobEvent>> = ArrayDeque(),
    ) : V11CloudApi {
        val submits = mutableListOf<Pair<String, List<UploadFile>>>()
        val deletes = mutableListOf<String>()
        val puts = mutableListOf<UploadFile>()
        val jobCreates = mutableListOf<Triple<String, String, String>>()
        val uploadCreates = mutableListOf<List<UploadFileRef>>()
        val watchIds = mutableListOf<String?>()
        var samCalls = 0
        var getCalls = 0
        private var lastRefs: List<UploadFileRef> = emptyList()

        override suspend fun submit(manifestJson: String, files: List<UploadFile>): String {
            submitError?.let { throw it }
            submits += manifestJson to files
            return "job-1"
        }

        override suspend fun createUpload(files: List<UploadFileRef>): CreatedUpload {
            lastRefs = files
            uploadCreates += files
            val missing = files.map { it.name } - staged
            return CreatedUpload("upload-1", missing)
        }

        override suspend fun uploadMissing(uploadId: String): List<String> {
            val held = staged + puts.map { it.id }
            return lastRefs.map { it.name } - held
        }

        override suspend fun putUploadFile(uploadId: String, file: UploadFile) {
            puts += file
        }

        override suspend fun createJob(manifestJson: String, uploadId: String, idempotencyKey: String): String {
            submitError?.let { throw it }
            jobCreates += Triple(manifestJson, uploadId, idempotencyKey)
            // Legacy assertions read submits: the created job carries the
            // bytes actually PUT, exactly what the multipart path attached.
            submits += manifestJson to puts.toList()
            return "job-1"
        }

        override suspend fun deleteUpload(uploadId: String) {
            deletes += uploadId
        }

        override suspend fun createJobFromStaged(
            manifestJson: String,
            uploadIds: List<String>,
            idempotencyKey: String,
        ): String {
            jobCreates += Triple(manifestJson, uploadIds.joinToString("+"), idempotencyKey)
            submits += manifestJson to puts.toList()
            return "job-1"
        }

        override fun watchEvents(jobId: String, lastEventId: String?): Flow<JobEvent> {
            watchIds += lastEventId
            return flows.removeFirstOrNull() ?: emptyFlow()
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
            faceDetector: FaceDetector? = null,
            media: MediaTools = this.media,
        ) = ProcessingViewModel(
            draftId = draftId,
            drafts = drafts,
            media = media,
            api = api,
            nowMs = { 1_762_000_100_000L },
            pollIntervalMs = pollIntervalMs,
            maxPolls = maxPolls,
            aiConfigured = aiConfigured,
            faceDetector = faceDetector,
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
            override suspend fun transcodeStripClip(
                video: Path,
                startMs: Long,
                endMs: Long,
                out: Path,
            ): ClipInfo {
                windows += startMs to endMs
                return h.media.cutClip(video, startMs, endMs, CLIP_FORGE_FPS, out)
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
        // opens the 3 s fallback window [37_750, 40_750] → [37_750, 37_750],
        // n2 at t=60 s opens [57_750, 60_750] → [57_750, 57_750] (the end
        // pulls back to the duration, then up to the start — the
        // stripFallbackWindow never-invert rule, W11-03). Both ends sit below
        // their raw t+750.
        assertEquals(
            listOf(37_750L to 37_750L, 57_750L to 57_750L),
            windows,
            "clip windows not clamped to duration: $windows",
        )
        // The cached clips still ride the resubmit's manifest.
        val ids = api.submits[1].second.map { it.id }.toSet()
        assertTrue(ids.contains("clip_n1"), ids.toString())
        assertTrue(ids.contains("clip_n2"), ids.toString())
    }

    @Test
    fun sendWithLedgerStripsForgesClipsAndUploadsSidecars() = runTest(dispatcher) {
        val h = harness()
        // Ledger strips inside each node's [d − 8, d + 1] window (W11-02).
        h.drafts.saveGlimpseEntries(
            "draft-1",
            listOf(
                GlimpseEntry(0, 10_000_000_000L, 18.0, 0.9, 0.9, 1L, "glimpse/a.jpg"),
                GlimpseEntry(1, 11_000_000_000L, 19.0, 0.8, 0.8, 2L, "glimpse/b.jpg"),
                GlimpseEntry(2, 20_000_000_000L, 44.0, 0.9, 0.9, 3L, "glimpse/c.jpg"),
                GlimpseEntry(3, 21_000_000_000L, 44.6, 0.7, 0.7, 4L, "glimpse/d.jpg"),
            ),
        )
        val draftDir = "/data/drafts/draft-1".toPath()
        h.fileSystem.createDirectories(draftDir / "glimpse")
        for (name in listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg")) {
            h.fileSystem.write(draftDir / "glimpse/$name") { write("jpeg".encodeToByteArray()) }
        }
        val forged = mutableListOf<String>()
        val media: MediaTools = object : MediaTools by h.media {
            override val supportsStripForge: Boolean = true

            override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo {
                forged += "${out.name}:${frames.size}"
                out.parent?.let { h.fileSystem.createDirectories(it) }
                h.fileSystem.write(out) { write("forged".encodeToByteArray()) }
                return ClipInfo(out, frameCount = frames.size, durationMs = frames.size * 250L)
            }
        }
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = ProcessingViewModel(
            draftId = "draft-1",
            drafts = h.drafts,
            media = media,
            api = api,
            nowMs = { 1_762_000_100_000L },
            pollIntervalMs = 1L,
            maxPolls = 10,
        )
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // Both nodes forged from their strips — never the video transcode.
        assertEquals(listOf("clip_n1.mp4:2", "clip_n2.mp4:2"), forged.sorted())
        assertTrue(h.media.cut.isEmpty())
        val ids = api.submits.single().second.map { it.id }.toSet()
        assertTrue(ids.contains("clip_n1"), ids.toString())
        assertTrue(ids.contains("clip_n2"), ids.toString())
        assertTrue(ids.contains(clipSidecarId("n1")), ids.toString())
        assertTrue(ids.contains(clipSidecarId("n2")), ids.toString())
        // The sidecar maps output frame index → (tNs, odometer m) exactly.
        val raw = checkNotNull(h.drafts.readDraftFile("draft-1", clipSidecarFileName("n1")))
        val sidecar = decodeFrameMap(raw.decodeToString())
        assertEquals("image-strip", sidecar.source)
        assertEquals(2, sidecar.frames.size)
        assertEquals(10_000_000_000L, sidecar.frames[0].tNs)
        assertEquals(18.0, sidecar.frames[0].odometerM, 1e-9)
        assertEquals(sha256Hex("forged".encodeToByteArray()), sidecar.clipSha256)
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

    // ── W11-08: hash-first upload, event stream, partials ──────────────

    private fun candidateJson(candidateId: String, title: String, rank: Double = 0.9): String =
        """{"candidateId":"$candidateId","title":"$title","description":"A landmark","cue":"Look for it","samPhrase":"$title","keyframeId":"kf_n1_0","box":[412,230,590,900],"frameW":720,"frameH":1280,"rank":$rank,"source":"AI_GROUNDED","models":"muse-spark-1.3 + sam-3.1"}"""

    @Test
    fun resumeSkipsAlreadyStagedFiles() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(
            polls = ArrayDeque(listOf(complete())),
            staged = mutableSetOf("kf_n1_0", "clip_n1"),
        )
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // The server's missing[] drives the PUTs: staged bytes never
        // leave the phone again.
        val putIds = api.puts.map { it.id }.toSet()
        assertFalse(putIds.contains("kf_n1_0"), putIds.toString())
        assertFalse(putIds.contains("clip_n1"), putIds.toString())
        assertTrue(putIds.contains("clip_n2"), putIds.toString())
        val declared = api.uploadCreates.single().map { it.name }.toSet()
        assertEquals(declared - api.staged, putIds)
        // The job was created once against the resumed session.
        assertEquals("upload-1", api.jobCreates.single().second)
        assertEquals("complete", h.drafts.loadJobRecord("draft-1")?.status)
    }

    @Test
    fun idempotentRetryReusesKey() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(
            polls = ArrayDeque(listOf(errored("scout down"), complete())),
        )
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()
        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)

        vm.retry()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(2, api.jobCreates.size)
        // Same manifest bytes → same idempotency key: the retry replays
        // the create instead of grounding twice.
        assertEquals(api.jobCreates[0].first, api.jobCreates[1].first)
        assertEquals(api.jobCreates[0].third, api.jobCreates[1].third)
        val (manifest, _, key) = api.jobCreates[0]
        assertEquals(sha256Hex(manifest.encodeToByteArray()), key)
    }

    @Test
    fun streamReconnectResumesFromLastEventId() = runTest(dispatcher) {
        val h = harness()
        val checkpoint = JobEvent.Checkpoint(
            id = "evt-1",
            nodeId = "n1",
            status = "grounded",
            candidatesRaw = "[${candidateJson("n1_0", "Red pillar")}]",
        )
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flow {
                        emit(checkpoint)
                        throw EchoApiError.Network(RuntimeException("drop"))
                    },
                    flowOf(JobEvent.Complete("evt-2", null)),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // The reconnect carried Last-Event-ID past the delivered event.
        assertEquals(listOf(null, "evt-1"), api.watchIds)
        assertTrue(api.getCalls == 0, "stream covered the job; no fallback poll")
        // The partial landed before the drop and survived to Ready.
        assertEquals(listOf("n1"), vm.partials.value.keys.toList())
        assertEquals("Red pillar", vm.partials.value.getValue("n1").single().title)
    }

    @Test
    fun fallbackPollingProgressesWhenStreamDown() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(
            polls = ArrayDeque(
                listOf(
                    Result.success(JobPoll("queued", JobStage.QUEUED)),
                    running(JobStage.GROUNDING),
                    complete(),
                ),
            ),
        ).apply {
            flows = ArrayDeque(listOf(flow { throw EchoApiError.Network(RuntimeException("down")) }))
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(3, api.getCalls)
        assertEquals("complete", h.drafts.loadJobRecord("draft-1")?.status)
    }

    @Test
    fun partialCheckpointsMergeWithoutTouchingGeometry() = runTest(dispatcher) {        val h = harness()
        val routeBefore = h.drafts.loadDraftRoute("draft-1")
        assertNotNull(routeBefore)
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flowOf(
                        JobEvent.Checkpoint(
                            "evt-1", "n1", "grounded",
                            "[${candidateJson("n1_0", "Red pillar")}]",
                        ),
                        JobEvent.Checkpoint(
                            "evt-2", "n2", "grounded",
                            "[${candidateJson("n2_0", "Blue door")}]",
                        ),
                    ),
                    flowOf(JobEvent.Complete("evt-3", null)),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val partials = vm.partials.value
        assertEquals(setOf("n1", "n2"), partials.keys)
        assertEquals("Red pillar", partials.getValue("n1").single().title)
        assertEquals("Blue door", partials.getValue("n2").single().title)
        // AI candidates never mutate geometry: the draft route is identical.
        assertEquals(routeBefore, h.drafts.loadDraftRoute("draft-1"))
        // Partials persisted for Review while the job ran.
        assertNotNull(h.drafts.loadPartialResult("draft-1"))
    }

    // ── W13-06: constellation from live events ────────────────────

    @Test
    fun checkpointStatusesMapToStarStatesAndStageNeverMovesAStar() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flowOf(
                        JobEvent.Stage("evt-0", JobStage.SCOUTING),
                        JobEvent.Checkpoint(
                            "evt-1", "n1", "scouted",
                            "[${candidateJson("n1_0", "Red pillar")}]",
                        ),
                        JobEvent.Checkpoint(
                            "evt-2", "n1", "grounded",
                            "[${candidateJson("n1_0", "Red pillar")}]",
                        ),
                        JobEvent.Checkpoint("evt-3", "n2", "skipped", "[]"),
                    ),
                    flowOf(JobEvent.Complete("evt-4", null)),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val stars = vm.constellation.value.stars.associateBy { it.nodeId }
        // START is not a checkpoint; checkpoints stay in node order.
        assertEquals(listOf("n1", "n2"), vm.constellation.value.stars.map { it.nodeId })
        // Only checkpoint events moved stars: scouted → grounded for n1,
        // skipped for n2. The stage event left them alone.
        assertEquals(StarState.GROUNDED, stars.getValue("n1").state)
        assertEquals(StarState.SKIPPED, stars.getValue("n2").state)
        assertEquals("pillar", stars.getValue("n1").label)
        assertEquals("Elevator", stars.getValue("n2").label)
    }

    @Test
    fun errorLeavesStarsAtLastSeenCheckpointEvent() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flowOf(
                        JobEvent.Checkpoint(
                            "evt-1", "n1", "scouted",
                            "[${candidateJson("n1_0", "Red pillar")}]",
                        ),
                        JobEvent.Error("evt-2", "scout exploded"),
                    ),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        val stars = vm.constellation.value.stars.associateBy { it.nodeId }
        assertEquals(StarState.SCOUTING, stars.getValue("n1").state)
        assertEquals(StarState.QUEUED, stars.getValue("n2").state)
    }

    @Test
    fun usageEventsDriveBudgetMeterAndNeverMoveStars() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flowOf(
                        JobEvent.Usage("u-1", samFrames = 12),
                        JobEvent.Stage("evt-0", JobStage.GROUNDING),
                        JobEvent.Checkpoint(
                            "evt-1", "n1", "grounded",
                            "[${candidateJson("n1_0", "Red pillar")}]",
                        ),
                        JobEvent.Usage("u-2", samFramesBilled = 38),
                        // No payload: the meter holds, it never resets.
                        JobEvent.Usage("u-3"),
                        JobEvent.Unknown("u-4"),
                    ),
                    flowOf(JobEvent.Complete("evt-2", null)),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val constellation = vm.constellation.value
        // Billed wins over estimated; the high-water mark never drops.
        assertEquals(38, constellation.framesUsed)
        assertEquals("38 / 120 frames", constellationBudgetText(constellation.framesUsed))
        assertFalse(constellation.budgetExhausted)
        // Usage, stage, unknown and complete events never moved a star.
        val stars = constellation.stars.associateBy { it.nodeId }
        assertEquals(StarState.GROUNDED, stars.getValue("n1").state)
        assertEquals(StarState.QUEUED, stars.getValue("n2").state)
    }

    @Test
    fun budgetExhaustedFlagIsStickyOnTheMeter() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flowOf(JobEvent.Usage("u-1", budgetExhausted = true)),
                    flowOf(JobEvent.Complete("evt-2", null)),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val constellation = vm.constellation.value
        assertEquals(0, constellation.framesUsed)
        assertTrue(constellation.budgetExhausted)
        assertEquals(
            "0 / 120 frames · frame limit reached",
            constellationBudgetText(constellation.framesUsed, constellation.budgetExhausted),
        )
    }

    @Test
    fun retryResetsStarsAndMeterBeforeTheNextSend() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flowOf(
                        JobEvent.Checkpoint(
                            "evt-1", "n1", "grounded",
                            "[${candidateJson("n1_0", "Red pillar")}]",
                        ),
                        JobEvent.Usage("u-1", samFramesBilled = 38),
                        JobEvent.Error("evt-2", "scout exploded"),
                    ),
                    flowOf(JobEvent.Complete("evt-3", null)),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertEquals(StarState.GROUNDED, vm.constellation.value.stars.first { it.nodeId == "n1" }.state)
        assertEquals(38, vm.constellation.value.framesUsed)

        vm.retry()
        advanceUntilIdle()

        // Back at consent: the dead job's stars and meter are gone, not
        // carried into the next send as fake progress.
        assertIs<ProcessingPhase.Consent>(vm.ui.value.phase)
        assertTrue(vm.constellation.value.stars.all { it.state == StarState.QUEUED })
        assertEquals(0, vm.constellation.value.framesUsed)
        assertFalse(vm.constellation.value.budgetExhausted)

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
    }

    @Test
    fun checkpointStatusMapsToStarState() {
        assertEquals(StarState.SCOUTING, starStateForStatus("scouted"))
        assertEquals(StarState.GROUNDED, starStateForStatus("grounded"))
        assertEquals(StarState.SKIPPED, starStateForStatus("skipped"))
        assertNull(starStateForStatus(""))
        assertNull(starStateForStatus("scouting"))
        assertNull(starStateForStatus("complete"))
    }

    @Test
    fun budgetTextFormatsUsedOverFrozenCap() {
        assertEquals("38 / 120 frames", constellationBudgetText(38))
        assertEquals("0 / 120 frames", constellationBudgetText(0))
        assertEquals(
            "38 / 120 frames · frame limit reached",
            constellationBudgetText(38, budgetExhausted = true),
        )
    }

    @Test
    fun constellationSkeletonUsesCheckpointsInNodeOrder() {
        val stars = constellationStars(draftRoute(), mapOf("n1" to StarState.GROUNDED))
        // START is not a checkpoint; the rest keep node order.
        assertEquals(listOf("n1", "n2"), stars.map { it.nodeId })
        assertEquals(StarState.GROUNDED, stars[0].state)
        assertEquals(StarState.QUEUED, stars[1].state)
        assertEquals("pillar", stars[0].label)
        assertTrue(stars.all { it.x01 in 0f..1f && it.y01 in 0f..1f }, stars.toString())
    }

    // ── W11-04: face pre-filter fails closed ──────────────────────

    /** Verdicts by odometer metres; unmapped frames are UNKNOWN (exclude-safe). */
    private class FakeFaceDetector(private val verdicts: Map<Double, FaceVerdict>) : FaceDetector {
        override fun verdictFor(entry: GlimpseEntry): FaceVerdict =
            verdicts[entry.odometerM] ?: FaceVerdict.UNKNOWN
    }

    private val allClear = FakeFaceDetector(
        listOf(16.0, 19.0, 21.0, 42.0, 45.0).associateWith { FaceVerdict.FACE_FREE },
    )

    /** Ledger frames inside n1's [12, 21] and n2's [37, 46] approach windows. */
    private fun seedLedger(h: Harness) {
        val ds = listOf(16, 19, 21, 42, 45)
        h.drafts.saveGlimpseEntries(
            "draft-1",
            ds.map { d ->
                GlimpseEntry(d.toLong(), d * 1_000_000_000L, d.toDouble(), 0.9, 0.9, d.toLong(), "glimpse/b$d.jpg")
            },
        )
        val dir = "/data/drafts/draft-1/glimpse".toPath()
        h.fileSystem.createDirectories(dir)
        for (d in ds) h.fileSystem.write(dir / "b$d.jpg") { write("jpeg-$d".encodeToByteArray()) }
    }

    private fun stripForgeMedia(h: Harness, forged: MutableList<String>): MediaTools = object : MediaTools by h.media {
        override val supportsStripForge: Boolean = true

        override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo {
            forged += "${out.name}:" + frames.joinToString(",") { it.name }
            h.fileSystem.write(out) { write("forged".encodeToByteArray()) }
            return ClipInfo(out, frameCount = frames.size, durationMs = frames.size * 250L)
        }
    }

    @Test
    fun faceFlaggedKeyframeIsWithheldFromBytesManifestAndClip() = runTest(dispatcher) {
        val h = harness()
        seedLedger(h)
        val forged = mutableListOf<String>()
        val detector = FakeFaceDetector(
            listOf(16.0, 21.0, 42.0, 45.0).associateWith { FaceVerdict.FACE_FREE } + (19.0 to FaceVerdict.HAS_FACE),
        )
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, media = stripForgeMedia(h, forged), faceDetector = detector)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val (manifestJson, files) = api.submits.single()
        val ids = files.map { it.id }.toSet()
        // The face frame's bytes never left the phone ...
        assertFalse(ids.contains("kf_n1_1"), ids.toString())
        assertTrue(files.none { it.bytes.decodeToString() == "jpeg-19" })
        assertTrue(ids.containsAll(setOf("kf_n1_0", "kf_n1_2", "kf_n2_0", "kf_n2_1")), ids.toString())
        // ... and the manifest does not promise it (worker: 400 upload_incomplete).
        val manifest = EchoJson.decodeFromString<Manifest>(manifestJson)
        val n1 = manifest.checkpoints.single { it.nodeId == "n1" }
        assertEquals(listOf("kf_n1_0", "kf_n1_2"), n1.keyframes.map { it.id })
        assertEquals("kf_n1_0", n1.heroKeyframeId)
        val listed = manifest.checkpoints.flatMap { cp -> cp.keyframes.map { it.id } + listOfNotNull(cp.clip?.id) }
        assertTrue(ids.containsAll(listed), "manifest ids without bytes: ${listed - ids}")
        // The forged clip holds cleared frames only: the face frame is not in it.
        assertEquals("clip_n1.mp4:b16.jpg,b21.jpg", forged.sorted().first())
        assertTrue(forged.none { it.contains("b19") }, forged.toString())
    }

    @Test
    fun noStripForgeWithDetectorMeansNoClipNeverTheUnscannedVideoFallback() = runTest(dispatcher) {
        val h = harness()
        seedLedger(h)
        // n2's frames are unscanned (UNKNOWN); the platform has no strip forge.
        val detector = FakeFaceDetector(
            mapOf(16.0 to FaceVerdict.FACE_FREE, 19.0 to FaceVerdict.FACE_FREE, 21.0 to FaceVerdict.FACE_FREE),
        )
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, faceDetector = detector)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // The video transcode cannot be scanned: it never ran, no clip uploaded.
        assertTrue(h.media.cut.isEmpty(), h.media.cut.toString())
        val ids = api.submits.single().second.map { it.id }.toSet()
        assertTrue(ids.none { it.startsWith("clip_") }, ids.toString())
        // n2's keyframes were unscanned: withheld whole.
        assertTrue(ids.none { it.startsWith("kf_n2_") }, ids.toString())
        assertTrue(ids.containsAll(setOf("kf_n1_0", "kf_n1_1", "kf_n1_2")), ids.toString())
    }

    @Test
    fun faceStripKeepsOnlyClearedFramesInThatNodesClip() = runTest(dispatcher) {
        val h = harness()
        seedLedger(h)
        val forged = mutableListOf<String>()
        val detector = FakeFaceDetector(
            listOf(16.0, 19.0, 21.0, 42.0).associateWith { FaceVerdict.FACE_FREE } + (45.0 to FaceVerdict.HAS_FACE),
        )
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, media = stripForgeMedia(h, forged), faceDetector = detector)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // n2's single cleared frame still forges; the face frame (45 m) is not in it.
        assertEquals(listOf("clip_n1.mp4:b16.jpg,b19.jpg,b21.jpg", "clip_n2.mp4:b42.jpg"), forged.sorted())
        assertTrue(h.media.cut.isEmpty())
    }

    @Test
    fun detectorWithAbsentLedgerWithholdsEverythingAndSendsNothing() = runTest(dispatcher) {
        val h = harness() // video only: slots fill from video, nothing is scanned
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, faceDetector = allClear)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertTrue(api.uploadCreates.isEmpty())
        assertTrue(api.puts.isEmpty())
        assertTrue(api.jobCreates.isEmpty())
    }

    @Test
    fun detectorWithCorruptLedgerWithholdsEverythingAndSendsNothing() = runTest(dispatcher) {
        val h = harness()
        h.drafts.writeDraftFile("draft-1", "glimpse/entries.json", "{not json".encodeToByteArray())
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, faceDetector = allClear)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertTrue(api.puts.isEmpty())
        assertTrue(api.jobCreates.isEmpty())
    }

    @Test
    fun detectorWithholdsASlotRecapturedAfterTheScan() = runTest(dispatcher) {
        val h = harness()
        seedLedger(h)
        // A creator recapture replaced the ledger copy: its bytes were never scanned.
        h.drafts.writeDraftFile("draft-1", "kf/n1_0.jpg", "recaptured".encodeToByteArray())
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, faceDetector = allClear)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val ids = api.submits.single().second.map { it.id }.toSet()
        assertFalse(ids.contains("kf_n1_0"), ids.toString())
        assertTrue(ids.containsAll(setOf("kf_n1_1", "kf_n1_2")), ids.toString())
    }
}
