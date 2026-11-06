package app.simt.echo.ui.screens.processing

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.media.CLIP_FORGE_FPS
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.CropBox
import app.simt.echo.media.FrameRequest
import app.simt.echo.media.Manifest
import app.simt.echo.media.MediaTools
import app.simt.echo.media.clipSidecarFileName
import app.simt.echo.media.clipSidecarId
import app.simt.echo.media.decodeFrameMap
import app.simt.echo.media.sha256Hex
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
import app.simt.echo.platform.LiveUpdateDirector
import app.simt.echo.platform.PROCESSING_LIVE_UPDATE_ID
import app.simt.echo.platform.RecordingLiveUpdateSink
import app.simt.echo.platform.processingCopy
import app.simt.echo.transfer.EchoApiError
import app.simt.echo.transfer.JobEvent
import app.simt.echo.transfer.JobPoll
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.UploadFile
import app.simt.echo.transfer.UploadFileRef
import app.simt.echo.transfer.CreatedUpload
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.ui.copy.processingErrorCopy
import app.simt.echo.ui.copy.processingJobErrorCopy
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

/** W3-02 gate: consent → upload → poll → Ready, with job.json persistence.
 *  W11-04: face pre-filter fails closed. (Split from the 1615-line original,
 *  Nov 2026: staged/walk + W11-08 + W13-06 moved to ProcessingViewModelStagedTest.)
 */
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
        /** Thrown by [createJobFromStaged]: the worker no longer holds the walk's sessions. */
        var stagedError: EchoApiError? = null,
        /** Thrown by [deleteUpload]: a staged session that could not be deleted. */
        var uploadDeleteError: EchoApiError? = null,
        /** Names the server already holds (resume-skips-present scripts). */
        var staged: MutableSet<String> = mutableSetOf(),
        /** One flow per [watchEvents] call; empty when exhausted. */
        var flows: ArrayDeque<Flow<JobEvent>> = ArrayDeque(),
    ) : V11CloudApi {
        val submits = mutableListOf<Pair<String, List<UploadFile>>>()
        val deletes = mutableListOf<String>()
        val puts = mutableListOf<UploadFile>()
        val jobCreates = mutableListOf<Triple<String, String, String>>()
        val resumeOfs = mutableListOf<String?>()
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

        override suspend fun createJob(
            manifestJson: String,
            uploadId: String,
            idempotencyKey: String,
            resumeOf: String?,
        ): String {
            submitError?.let { throw it }
            resumeOfs += resumeOf
            jobCreates += Triple(manifestJson, uploadId, idempotencyKey)
            // Legacy assertions read submits: the created job carries the
            // bytes actually PUT, exactly what the multipart path attached.
            submits += manifestJson to puts.toList()
            return "job-1"
        }

        override suspend fun deleteDevice() = Unit

        override suspend fun deleteUpload(uploadId: String) {
            uploadDeleteError?.let { throw it }
            deletes += uploadId
        }

        override suspend fun createJobFromStaged(
            manifestJson: String,
            uploadIds: List<String>,
            idempotencyKey: String,
        ): String {
            stagedError?.let { throw it }
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
        /** Distinct from Main, same scheduler: disk work hops here (W11-02). */
        val io: CoroutineDispatcher,
        val draftId: String = "draft-1",
    ) {
        fun vm(
            api: FakeCloudApi,
            pollIntervalMs: Long = 1L,
            maxPolls: Int = 10,
            aiConfigured: Boolean = true,
            faceDetector: FaceDetector? = null,
            media: MediaTools = this.media,
            nowMs: () -> Long = { 1_762_000_100_000L },
            liveUpdates: LiveUpdateDirector? = null,
        ) = ProcessingViewModel(
            draftId = draftId,
            drafts = drafts,
            media = media,
            api = api,
            nowMs = nowMs,
            pollIntervalMs = pollIntervalMs,
            maxPolls = maxPolls,
            aiConfigured = aiConfigured,
            faceDetector = faceDetector,
            ioDispatcher = io,
            liveUpdates = liveUpdates,
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
        return Harness(store, fileSystem, drafts, media, StandardTestDispatcher(dispatcher.scheduler, "io"))
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
    fun resendAfterRetryReusesCachedClipsClampsEndsAndSkipsCollapsedWindows() = runTest(dispatcher) {
        val h = harness()
        val windows = mutableListOf<Pair<Long, Long>>()
        val durationAware: MediaTools = object : MediaTools by h.media {
            override suspend fun videoDurationMs(video: Path): Long? = 40_000L
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
            ioDispatcher = h.io,
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
        // Clip ends clamp to the video duration (40 s here): n1 at t=40 s
        // opens the 3 s fallback window [37_750, 40_750] → [37_750, 40_000].
        // n2 at t=60 s opens [57_750, 60_750], which the clamp collapses
        // onto its start (57_750 .. 57_750): an empty window is skipped, not
        // cut. W10-02 G5: the resend reused n1's cached cut, never re-encoded.
        assertEquals(listOf(37_750L to 40_000L), windows, "clip windows: $windows")
        assertEquals(1, h.media.cut.size)
        // The cached clip still rides the resubmit, and its manifest entry
        // describes the window that was actually cut.
        val (manifestJson, files) = api.submits[1]
        val ids = files.map { it.id }.toSet()
        assertTrue(ids.contains("clip_n1"), ids.toString())
        assertFalse(ids.contains("clip_n2"), ids.toString())
        val clip = EchoJson.decodeFromString<Manifest>(manifestJson).checkpoints.single { it.nodeId == "n1" }.clip
        assertEquals(37_750L, clip?.startMs)
        assertEquals(40_000L, clip?.endMs)
    }

    @Test
    fun cancellationDuringAClipCutAbortsTheSendInsteadOfBeingSwallowed() = runTest(dispatcher) {
        val h = harness()
        val media: MediaTools = object : MediaTools by h.media {
            override suspend fun transcodeStripClip(video: Path, startMs: Long, endMs: Long, out: Path): ClipInfo =
                throw kotlinx.coroutines.CancellationException("cancelled mid-cut")
        }
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, media = media)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        // A swallowed cancellation would carry on and upload without clips.
        assertTrue(api.jobCreates.isEmpty())
        assertTrue(api.puts.isEmpty())
        assertIs<ProcessingPhase.Uploading>(vm.ui.value.phase)
    }

    @Test
    fun sendWithLedgerStripsForgesClipsAndKeepsSidecarsLocal() = runTest(dispatcher) {
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
            ioDispatcher = h.io,
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
        // The worker's allow-list drops the sidecar and nothing reads it yet
        // (W11-10): it stays on the phone, not in the upload.
        assertFalse(ids.contains(clipSidecarId("n1")), ids.toString())
        assertFalse(ids.contains(clipSidecarId("n2")), ids.toString())
        // The local sidecar maps output frame index → (tNs, odometer m) exactly.
        val raw = checkNotNull(h.drafts.readDraftFile("draft-1", clipSidecarFileName("n1")))
        val sidecar = decodeFrameMap(raw.decodeToString())
        assertEquals("image-strip", sidecar.source)
        assertEquals(2, sidecar.frames.size)
        assertEquals(10_000_000_000L, sidecar.frames[0].tNs)
        assertEquals(18.0, sidecar.frames[0].odometerM, 1e-9)
        assertEquals(sha256Hex("forged".encodeToByteArray()), sidecar.clipSha256)
        // The manifest describes the strip actually forged: first to last
        // frame, video-relative (videoStartNs is 0 in this fixture), not t-4000..t+1000.
        val manifest = EchoJson.decodeFromString<Manifest>(api.submits.single().first)
        val n1 = manifest.checkpoints.single { it.nodeId == "n1" }.clip
        assertEquals(10_000L, n1?.startMs)
        assertEquals(11_000L, n1?.endMs)
        assertEquals(2, n1?.frameCount)
    }

    @Test
    fun forgeThatDroppedFramesWritesAnHonestEmptyFrameMap() = runTest(dispatcher) {
        val h = harness()
        seedLedger(h)
        val media: MediaTools = object : MediaTools by h.media {
            override val supportsStripForge: Boolean = true

            override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo {
                h.fileSystem.write(out) { write("forged".encodeToByteArray()) }
                // The encoder kept fewer frames than it was given.
                return ClipInfo(out, frameCount = frames.size - 1, durationMs = 250L)
            }
        }
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, media = media)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val sidecar = decodeFrameMap(
            checkNotNull(h.drafts.readDraftFile("draft-1", clipSidecarFileName("n1"))).decodeToString(),
        )
        // Output frame i is no longer strip entry i, so no exact map is claimed.
        assertEquals("video-transcode", sidecar.source)
        assertTrue(sidecar.frames.isEmpty())
    }

    @Test
    fun liveUpdateTracksTheJobAndSettlesWhenItIsReady() = runTest(dispatcher) {
        val h = harness()
        val sink = RecordingLiveUpdateSink()
        val api = FakeCloudApi(polls = ArrayDeque(listOf(running(JobStage.GROUNDING), complete())))
        val vm = h.vm(api, pollIntervalMs = 1_000L, liveUpdates = LiveUpdateDirector(sink, 34))
        advanceUntilIdle()
        // Consent is not work in flight: nothing ongoing yet.
        assertTrue(sink.shown.isEmpty())

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertTrue(sink.shown.all { it.first == PROCESSING_LIVE_UPDATE_ID })
        val texts = sink.shown.map { it.second.content.text }
        assertTrue("Uploading…" in texts, "upload was not announced: $texts")
        assertTrue(processingCopy(JobStage.GROUNDING) in texts, "stage was not announced: $texts")
        // Ready settles the phase: the ongoing notification goes away.
        assertEquals(PROCESSING_LIVE_UPDATE_ID, sink.dismissed.last())
    }

    @Test
    fun liveUpdateIsDismissedWhenTheJobFails() = runTest(dispatcher) {
        val h = harness()
        val sink = RecordingLiveUpdateSink()
        val api = FakeCloudApi(polls = ArrayDeque(listOf(running(), errored("boom"))))
        val vm = h.vm(api, pollIntervalMs = 1_000L, liveUpdates = LiveUpdateDirector(sink, 34))
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertTrue(sink.shown.isNotEmpty())
        assertEquals(PROCESSING_LIVE_UPDATE_ID, sink.dismissed.last())
    }

    @Test
    fun liveUpdateIsDismissedWhenTheScreenGoesMidJob() = runTest(dispatcher) {
        val h = harness()
        val sink = RecordingLiveUpdateSink()
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                addInitializer(ProcessingViewModel::class) {
                    h.vm(FakeCloudApi(), pollIntervalMs = 1_000L, liveUpdates = LiveUpdateDirector(sink, 34))
                }
            },
        )[ProcessingViewModel::class]
        advanceUntilIdle()
        vm.send()
        runCurrent()
        assertIs<ProcessingPhase.Working>(vm.ui.value.phase)
        assertTrue(sink.shown.isNotEmpty())
        val dismissedBefore = sink.dismissed.size

        store.clear()

        assertEquals(dismissedBefore + 1, sink.dismissed.size)
        assertEquals(PROCESSING_LIVE_UPDATE_ID, sink.dismissed.last())
    }

    @Test
    fun withoutLiveUpdatesTheJobStillRuns() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm(FakeCloudApi(polls = ArrayDeque(listOf(complete()))))
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
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
    fun keyframeFillingAndClipCuttingRunOnTheInjectedIoDispatcher() = runTest(dispatcher) {
        val h = harness()
        val seen = mutableMapOf<String, Any?>()
        val media: MediaTools = object : MediaTools by h.media {
            override val supportsStripForge: Boolean = true

            override suspend fun extractFrames(video: Path, frames: List<FrameRequest>) {
                seen["extractFrames"] = currentCoroutineContext()[ContinuationInterceptor]
                h.media.extractFrames(video, frames)
            }

            override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo {
                seen["forgeStripClip"] = currentCoroutineContext()[ContinuationInterceptor]
                h.fileSystem.write(out) { write("forged".encodeToByteArray()) }
                return ClipInfo(out, frameCount = frames.size, durationMs = frames.size * 250L)
            }
        }
        seedLedger(h) // forgeStripClip needs strip frames; kf slots then fill from the ledger
        h.fileSystem.delete("/data/drafts/draft-1/start.jpg".toPath())
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api, media = media)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // The start photo is the only slot the ledger cannot fill: video batch, on IO.
        assertTrue(seen["extractFrames"] === h.io, seen.toString())
        assertTrue(seen["forgeStripClip"] === h.io, seen.toString())
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
