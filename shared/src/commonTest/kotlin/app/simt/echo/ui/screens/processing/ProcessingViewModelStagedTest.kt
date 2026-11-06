package app.simt.echo.ui.screens.processing

import app.simt.echo.capture.FaceDetector
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.CropBox
import app.simt.echo.media.MediaTools
import app.simt.echo.media.sha256Hex
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import app.simt.echo.platform.LiveUpdateDirector
import app.simt.echo.transfer.EchoApiError
import app.simt.echo.transfer.JobEvent
import app.simt.echo.transfer.JobPoll
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.ReadyStagedRecord
import app.simt.echo.transfer.UploadFile
import app.simt.echo.transfer.UploadFileRef
import app.simt.echo.transfer.UploadRecord
import app.simt.echo.transfer.CreatedUpload
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.ui.copy.aiUnavailableCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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

/** W11-02 ready-staged walk finish, W11-08 hash-first upload + event stream +
 *  partials, W13-06 constellation from live events. (Split from the 1615-line
 *  ProcessingViewModelTest, Nov 2026: core + W11-04 stayed in that file.)
 */
class ProcessingViewModelStagedTest {
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
        /** Invoked on every PUT; lets a test observe the UI mid-loop. */
        var onPut: (UploadFile) -> Unit = {},
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
            onPut(file)
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


    private fun stageWhileWalking(h: Harness, files: Map<String, String> = emptyMap()) =
        h.drafts.saveReadyStagedRecord("draft-1", ReadyStagedRecord(listOf("walk-1", "walk-2"), 0L, files))

    @Test
    fun finishBuildsTheJobFromTheWalkSessionsAndSendsOnlyWhatTheyLack() = runTest(dispatcher) {
        val h = harness()
        // The cut clips are the same bytes the walk staged, so only the keyframes are left to send.
        val clipSha = sha256Hex("clip".encodeToByteArray())
        stageWhileWalking(h, mapOf("clip_n1" to clipSha, "clip_n2" to clipSha))
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api)
        advanceUntilIdle()

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val (manifest, sessions, key) = api.jobCreates.single()
        assertEquals("upload-1+walk-1+walk-2", sessions, "the final session is listed first so changed bytes win")
        assertEquals(sha256Hex(manifest.encodeToByteArray()), key)
        assertEquals(
            setOf("kf_n1_0", "kf_n1_1", "kf_n1_2", "kf_n2_0", "kf_n2_1"),
            api.puts.map { it.id }.toSet(),
            "every keyframe goes in the final session, and neither already-staged clip does",
        )
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"), "the walk record is consumed by the job")
        assertEquals("job-1", h.drafts.loadJobRecord("draft-1")?.jobId)
    }

    @Test
    fun finishFallsBackToAFullUploadWhenTheWorkerForgotTheWalkSessions() = runTest(dispatcher) {
        val h = harness()
        stageWhileWalking(h)
        val api = FakeCloudApi(
            stagedError = EchoApiError.UploadStale("upload_expired"),
            polls = ArrayDeque(listOf(complete())),
        )
        val vm = h.vm(api)
        advanceUntilIdle()

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"), "the dead sessions are forgotten")
        val (_, upload, _) = api.jobCreates.single()
        assertEquals("upload-1", upload, "a normal single-session job")
        assertTrue(api.puts.map { it.id }.containsAll(listOf("kf_n1_0", "clip_n1")), "everything was sent again")
    }

    @Test
    fun uploadProgressCountsEveryFileThroughThePutLoop() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api)
        advanceUntilIdle()
        val progress = mutableListOf<Pair<Int?, Int?>>()
        api.onPut = { progress += vm.ui.value.uploadFilesDone to vm.ui.value.uploadFilesTotal }

        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        val total = api.puts.size
        assertTrue(total > 0, "the harness draft has files to send")
        // Each PUT observed the files finished *before* it — 0/T before the
        // first, (T-1)/T before the last — and the state settles at T/T
        // after the loop. The Uploading phase renders this as a determinate
        // "File X of Y" bar, not all-Todo stages.
        val expected: List<Pair<Int?, Int?>> = (0 until total).map { it to total }
        assertEquals(expected, progress)
        val finalCount: Pair<Int?, Int?> = vm.ui.value.uploadFilesDone to vm.ui.value.uploadFilesTotal
        assertEquals(total to total, finalCount)
    }

    @Test
    fun declineDeletesWhatWasStagedWhileWalkingAndThenLeaves() = runTest(dispatcher) {
        val h = harness()
        stageWhileWalking(h)
        val api = FakeCloudApi()
        val vm = h.vm(api)
        advanceUntilIdle()
        var left = false

        vm.decline { left = true }
        advanceUntilIdle()

        assertEquals(listOf("walk-1", "walk-2"), api.deletes)
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
        assertTrue(left)
    }

    @Test
    fun declineLeavesEvenWhenTheDeleteFailsAndKeepsTheRecordForTheTtl() = runTest(dispatcher) {
        val h = harness()
        stageWhileWalking(h)
        val api = FakeCloudApi(uploadDeleteError = EchoApiError.Server(500, "boom"))
        val vm = h.vm(api)
        advanceUntilIdle()
        var left = false

        vm.decline { left = true }
        advanceUntilIdle()

        assertTrue(left, "a failed delete never traps the user on this screen")
        assertNotNull(h.drafts.loadReadyStagedRecord("draft-1"))
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
    fun resumedSendReusesTheStoredManifestAndKeyDespiteATickingClock() = runTest(dispatcher) {
        val h = harness()
        var clock = 1_762_000_100_000L
        // The job create is lost (a dropped 202): the session record stays.
        val api = FakeCloudApi(
            polls = ArrayDeque(listOf(complete())),
            submitError = EchoApiError.Network(RuntimeException("lost 202")),
        )
        val vm = h.vm(api, nowMs = { clock += 1_000L; clock })
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()
        assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        val stored = checkNotNull(h.drafts.loadUploadRecord("draft-1"))

        api.submitError = null
        vm.retry()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // The session resumed: no second declaration, and the create carries
        // the stored manifest bytes and sha, not a rebuild with a newer clock.
        assertEquals(1, api.uploadCreates.size)
        val (manifest, uploadId, key) = api.jobCreates.single()
        assertEquals(stored.uploadId, uploadId)
        assertEquals(stored.manifestJson, manifest)
        assertEquals(stored.manifestSha256, key)
        assertEquals(sha256Hex(manifest.encodeToByteArray()), key)
    }

    @Test
    fun sendWithAnUndeletedErroredJobNamesItAsResumeOfAndAFreshSendDoesNot() = runTest(dispatcher) {
        val h = harness()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-9", "errored", 0L))
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api)
        advanceUntilIdle()
        assertTrue((vm.ui.value.phase as ProcessingPhase.Consent).hasJob)

        vm.send()
        advanceUntilIdle()
        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(listOf<String?>("job-9"), api.resumeOfs)

        // A fresh draft has no previous job to reuse work from.
        val fresh = harness()
        val freshApi = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val freshVm = fresh.vm(freshApi)
        advanceUntilIdle()
        freshVm.send()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), freshApi.resumeOfs)
    }

    @Test
    fun aTamperedSessionRecordIsNotResumed() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(polls = ArrayDeque(listOf(complete())))
        val vm = h.vm(api)
        advanceUntilIdle()
        // A record whose manifest no longer hashes to its stored sha is invalid.
        h.drafts.saveUploadRecord("draft-1", UploadRecord("upload-old", "{}", "0".repeat(64), 0L))
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(1, api.uploadCreates.size)
        assertEquals("upload-1", api.jobCreates.single().second)
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
    fun reconnectRidesOutSilentWindowsSoLateUsageMovesTheMeter() = runTest(dispatcher) {
        val h = harness()
        // The worker closes each SSE window after ~25 s: the first window
        // delivers an early stage event, the next two are silent (heartbeat
        // comments only), and the `usage` event lands with the seal, minutes
        // in. Giving up after two attempts (the old behavior) dropped to the
        // usage-less poll and left the meter frozen at 0.
        val api = FakeCloudApi(
            flows = ArrayDeque(
                listOf(
                    flowOf(JobEvent.Stage("e1", JobStage.GROUNDING)),
                    flowOf(),
                    flowOf(),
                    flowOf(
                        JobEvent.Usage("u-2", samFramesBilled = 38),
                        JobEvent.Complete("e-3", null),
                    ),
                ),
            ),
        )
        val vm = h.vm(api)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // Every reconnect carried the last-seen event id, and the stream —
        // not the poll — covered the whole job.
        assertEquals(listOf(null, "e1", "e1", "e1"), api.watchIds)
        assertTrue(api.getCalls == 0, "the stream stayed the source of truth")
        // The late usage event moved the meter; the high-water mark holds.
        assertEquals(38, vm.constellation.value.framesUsed)
        assertEquals("38 / 120 frames", constellationBudgetText(vm.constellation.value.framesUsed))
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
    fun fallbackPollingBacksOffThenHoldsAtSixteenSecondsUntilTheDeadline() = runTest(dispatcher) {
        val h = harness()
        // A SAM job that never finishes: the stream is down, polls stay "running".
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(listOf(flow { throw EchoApiError.Network(RuntimeException("down")) }))
        }
        val vm = h.vm(api, maxPolls = 150)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        val failed = assertIs<ProcessingPhase.Failed>(vm.ui.value.phase)
        assertTrue(failed.message.contains("Timed out", ignoreCase = true), failed.message)
        // Polls at 0, 2, 6, 14, 30 s, then every 16 s while the next poll
        // still lands inside the 300 s window (last at 286 s): 21 polls, not 5.
        assertEquals(21, api.getCalls)
        assertEquals(286_000L, currentTime)
    }

    @Test
    fun fallbackPollingStillFindsAJobThatFinishesMinutesIn() = runTest(dispatcher) {
        val h = harness()
        val api = FakeCloudApi(
            polls = ArrayDeque(List(14) { running(JobStage.GROUNDING) } + complete()),
        ).apply {
            flows = ArrayDeque(listOf(flow { throw EchoApiError.Network(RuntimeException("down")) }))
        }
        val vm = h.vm(api, maxPolls = 150)
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()

        // The 15th poll lands at 190 s — far past the old 30 s give-up.
        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        assertEquals(15, api.getCalls)
        assertEquals(190_000L, currentTime)
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
    fun resumedJobReplaysTheStreamSoStarsAndMeterAreNotLeftAtQueuedAndZero() = runTest(dispatcher) {
        val h = harness()
        // The persisted record points past the grounded checkpoint and the
        // usage event: a resume from there would never re-deliver them.
        h.drafts.saveJobRecord("draft-1", JobRecord("job-9", "running", 0L, lastEventId = "evt-2"))
        val api = FakeCloudApi().apply {
            flows = ArrayDeque(
                listOf(
                    flowOf(
                        JobEvent.Checkpoint(
                            "evt-1", "n1", "grounded",
                            "[${candidateJson("n1_0", "Red pillar")}]",
                        ),
                        JobEvent.Usage("evt-2", samFramesBilled = 38),
                        JobEvent.Complete("evt-3", null),
                    ),
                ),
            )
        }
        val vm = h.vm(api)
        advanceUntilIdle()

        assertIs<ProcessingPhase.Ready>(vm.ui.value.phase)
        // The stream was re-read from its head, not from Last-Event-ID evt-2.
        assertEquals(listOf<String?>(null), api.watchIds)
        val stars = vm.constellation.value.stars.associateBy { it.nodeId }
        assertEquals(StarState.GROUNDED, stars.getValue("n1").state)
        assertEquals(38, vm.constellation.value.framesUsed)
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
}
