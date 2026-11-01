package app.simt.echo.ui.screens.review

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.CropBox
import app.simt.echo.media.MediaTools
import app.simt.echo.model.Confirmation
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.LandmarkSource
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.JobPoll
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.UploadFile
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W2-01 gate: manual landmarks round-trip to disk; save exports geometry untouched. */
class ReviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeMediaTools(private val fileSystem: FileSystem) : MediaTools {
        val extracted = mutableListOf<Pair<Path, Long>>()
        val cropped = mutableListOf<Pair<Path, CropBox>>()
        val frameBytes = "frame".encodeToByteArray()
        val cropBytes = "crop".encodeToByteArray()

        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
            extracted += out to atMs
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write(frameBytes) }
        }

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) {
            check(fileSystem.exists(input)) { "missing input $input" }
            cropped += input to box
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write(cropBytes) }
        }

        override suspend fun cutClip(
            video: Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: Path,
        ): ClipInfo {
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write(cropBytes) }
            return ClipInfo(out, frameCount = 0, durationMs = endMs - startMs)
        }
    }

    private data class Harness(
        val store: FileStore,
        val drafts: DraftRepository,
        val routes: RouteRepository,
        val media: FakeMediaTools,
        val api: FakeCloudApi = FakeCloudApi(),
        val draftId: String = "draft-1",
        var ids: Int = 0,
    ) {
        fun vm() = ReviewViewModel(
            draftId = draftId,
            drafts = drafts,
            routes = routes,
            media = media,
            nowMs = { 1_762_000_100_000L },
            newId = { "lm-test-${ids++}" },
            api = api,
        )
    }

    /** Scripted cloud API: the completed job returns the canned result. */
    private class FakeCloudApi(var resultRaw: String? = null) : CloudApi {
        var gotten: String? = null

        override suspend fun submit(manifestJson: String, files: List<UploadFile>): String = "job-1"

        override suspend fun get(jobId: String): JobPoll {
            gotten = jobId
            return JobPoll(status = "complete", stage = JobStage.COMPLETE, resultRaw = resultRaw)
        }

        override suspend fun delete(jobId: String) = Unit

        override suspend fun samEnabled(): Boolean = false
    }

    private fun candidateJson(rank: Double, title: String = "Red pillar") = """
        {"candidateId":"n1_0","title":"$title","description":"A red pillar.",
        "cue":"Turn right at the red pillar","samPhrase":"red pillar","signText":null,
        "privacyRisk":false,"keyframeId":"kf_n1_1","box":[412,230,590,900],
        "frameW":720,"frameH":1280,"maskSvgPath":"M 10 20 L 30 20 L 30 40 Z",
        "maskW":4,"maskH":3,"samConfidence":0.86,"trackability":0.78,
        "visibleFromM":9.4,"distinctiveness":0.9,"permanence":0.95,
        "rank":$rank,"source":"AI_GROUNDED","models":"muse-spark-1.3 + sam-3.1",
        "evidenceKey":"jobs/job-1/sam/n1_0.txt"}""".replace("\n", "")

    private fun resultJson() = """
        {"jobId":"job-1","models":{"scout":"muse-spark-1.3","ground":"sam-3.1"},
        "checkpoints":[
        {"nodeId":"n1","candidates":[${candidateJson(0.2)},${candidateJson(0.9)}]},
        {"nodeId":"n99","candidates":[${candidateJson(0.9, "Ghost")}]}],
        "usage":{"samFrames":50,"scoutMs":1,"samMs":2}}""".replace("\n", "")

    private fun draftRoute(): EchoRoute = EchoRoute(
        routeId = "draft-1",
        name = "Test walk",
        createdAtEpochMs = 1_762_000_000_000L,
        creatorLabel = "creator",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 45.0,
        start = StartAnchor(
            description = "Entrance",
            photoAssetId = "start.jpg",
            alignmentHint = "Face north",
        ),
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
        legs = listOf(
            RouteLeg(fromNode = "n0", toNode = "n1", lengthM = 20.0, headingRelDeg = 0.0),
            RouteLeg(fromNode = "n1", toNode = "n2", lengthM = 25.0, headingRelDeg = 90.0),
        ),
        path = listOf(PathPoint(d = 0.0, x = 0.0, y = 0.0), PathPoint(d = 45.0, x = 15.0, y = 20.0)),
    )

    private fun harness(): Harness {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val drafts = DraftRepository(store)
        val routes = RouteRepository(store)
        val media = FakeMediaTools(fileSystem)
        drafts.saveDraftRoute(draftRoute())
        val draftDir = "/data/drafts/draft-1".toPath()
        fileSystem.createDirectories(draftDir)
        fileSystem.write(draftDir / "start.jpg") { write("photo".encodeToByteArray()) }
        fileSystem.write(draftDir / "video.mp4") { write("video".encodeToByteArray()) }
        return Harness(store, drafts, routes, media)
    }

    @Test
    fun loadExtractsMissingKeyframesAndShowsHeroes() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm()
        advanceUntilIdle()

        val ui = vm.ui.value
        assertEquals(false, ui.loading)
        assertEquals(3, ui.nodes.size)
        // TURN t=40000 → kf/n1_{0,1,2}; DESTINATION t=60000 → kf/n2_{0,1}.
        assertEquals(5, h.media.extracted.size)
        assertEquals("frame".encodeToByteArray().toList(), ui.nodes[1].heroBytes!!.toList())
        assertEquals("frame".encodeToByteArray().toList(), ui.nodes[2].heroBytes!!.toList())
        assertEquals("photo".encodeToByteArray().toList(), ui.nodes[0].heroBytes!!.toList())
    }

    @Test
    fun skipsExtractionWhenKeyframeFilesExist() = runTest(dispatcher) {
        val h = harness()
        h.store.writeBytes("drafts/draft-1/kf/n1_0.jpg", "cached".encodeToByteArray())
        val vm = h.vm()
        advanceUntilIdle()

        assertEquals(4, h.media.extracted.size)
        assertTrue(h.media.extracted.none { it.first.name == "n1_0.jpg" })
        // Hero is kf/n1_1.jpg (extracted fresh); the cached kf/n1_0.jpg
        // was left untouched.
        assertEquals("frame".encodeToByteArray().toList(), vm.ui.value.nodes[1].heroBytes!!.toList())
        assertEquals(
            "cached".encodeToByteArray().toList(),
            h.drafts.readDraftFile("draft-1", "kf/n1_0.jpg")!!.toList(),
        )
    }

    @Test
    fun renameRefreshesAutoCueButKeepsCustomCue() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm()
        advanceUntilIdle()

        vm.renameNode("n1", "arch")
        advanceUntilIdle()
        assertEquals("Turn right at the arch", vm.ui.value.nodes[1].node.cue)

        vm.editCue("n1", "Custom cue")
        vm.renameNode("n1", "gate")
        advanceUntilIdle()
        assertEquals("Custom cue", vm.ui.value.nodes[1].node.cue)
    }

    @Test
    fun confirmDrawMintsManualVerifiedLandmark() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm()
        advanceUntilIdle()

        vm.openDraw("n1")
        vm.editDrawTitle("Red pillar")
        vm.confirmDraw("n1", CropBox(0.2f, 0.2f, 0.6f, 0.6f))
        advanceUntilIdle()

        val node = vm.ui.value.nodes[1]
        assertEquals(1, node.landmarks.size)
        val landmark = node.landmarks.single()
        assertEquals(LandmarkSource.MANUAL, landmark.source)
        assertEquals(Confirmation.CREATOR_VERIFIED, landmark.confirmation)
        assertEquals("Red pillar", landmark.title)
        assertEquals(listOf(landmark.id), node.node.landmarkIds)
        assertNotNull(h.drafts.readDraftFile("draft-1", "kf/manual_${landmark.id}.jpg"))
    }

    @Test
    fun editAndDeleteLandmarkRoundTrip() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm()
        advanceUntilIdle()

        vm.openDraw("n2")
        vm.editDrawTitle("Doors")
        vm.confirmDraw("n2", CropBox(0.1f, 0.1f, 0.5f, 0.5f))
        advanceUntilIdle()
        val id = vm.ui.value.nodes[2].landmarks.single().id

        vm.editLandmark(id, "Lift doors", "Ride up")
        advanceUntilIdle()
        assertEquals("Lift doors", vm.ui.value.nodes[2].landmarks.single().title)

        vm.deleteLandmark(id)
        advanceUntilIdle()
        assertTrue(vm.ui.value.nodes[2].landmarks.isEmpty())
        assertTrue(vm.ui.value.nodes[2].node.landmarkIds.isEmpty())
    }

    @Test
    fun editLandmarkPrivacyRiskFlagsAndDefaultsCropOff() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm()
        advanceUntilIdle()
        vm.openDraw("n1")
        vm.editDrawTitle("Poster with face")
        vm.confirmDraw("n1", CropBox(0.2f, 0.2f, 0.6f, 0.6f))
        advanceUntilIdle()
        val id = vm.ui.value.nodes[1].landmarks.single().id
        assertFalse(vm.ui.value.nodes[1].landmarks.single().privacyRisk)

        vm.editLandmark(id, "Poster with face", "Turn", privacyRisk = true)
        advanceUntilIdle()
        val flagged = vm.ui.value.nodes[1].landmarks.single()
        assertTrue(flagged.privacyRisk)

        // ExportSelection level: flagged crop defaults excluded.
        val savedDraft = h.drafts.loadDraftRoute("draft-1")
        assertNotNull(savedDraft)
        val selection = app.simt.echo.transfer.defaultSelection(savedDraft)
        assertTrue(flagged.cropAssetId in selection.excludedCropIds)
    }

    @Test
    fun saveExportsGeometryUntouchedAndDeletesDraft() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm()
        advanceUntilIdle()

        vm.openDraw("n1")
        vm.editDrawTitle("Red pillar")
        vm.confirmDraw("n1", CropBox(0.2f, 0.2f, 0.6f, 0.6f))
        advanceUntilIdle()

        val reviewedNodes = vm.ui.value.nodes.map { it.node }
        var saved: String? = null
        vm.save { saved = it }
        advanceUntilIdle()

        assertEquals("draft-1", saved)
        val exported = h.routes.get("draft-1")
        assertNotNull(exported)
        // Plan rule §0.4: nodes/legs/path copied untouched…
        assertEquals(reviewedNodes.map { it.copy(captureTMs = null) }, exported.nodes)
        assertEquals(draftRoute().legs, exported.legs)
        assertEquals(draftRoute().path, exported.path)
        // …except export copies null their capture times.
        assertTrue(exported.nodes.all { it.captureTMs == null })
        // Landmark + assets round-tripped to disk.
        assertEquals(1, exported.landmarks.size)
        assertEquals(Confirmation.CREATOR_VERIFIED, exported.landmarks.single().confirmation)
        assertNotNull(h.routes.readAsset("draft-1", "start.jpg"))
        assertNotNull(h.routes.readAsset("draft-1", exported.landmarks.single().cropAssetId!!))
        // The consumed draft is gone.
        assertNull(h.drafts.loadDraftRoute("draft-1"))
    }

    @Test
    fun saveCapturesJobIdForPostSaveDeleteOffer() = runTest(dispatcher) {
        val h = harness()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-1", "complete", 1_762_000_000_000L))
        val vm = h.vm()
        advanceUntilIdle()

        var saved: String? = null
        vm.save { saved = it }
        advanceUntilIdle()

        assertEquals("draft-1", saved)
        assertEquals("job-1", vm.ui.value.savedJobId)
        // The draft (with its job record) is consumed; the route is saved.
        assertNull(h.drafts.loadJobRecord("draft-1"))
        assertNotNull(h.routes.get("draft-1"))
        assertEquals("job-1", h.routes.routeJobId("draft-1"))
    }

    @Test
    fun saveWithoutJobLeavesNoDeleteOffer() = runTest(dispatcher) {
        val h = harness()
        val vm = h.vm()
        advanceUntilIdle()

        vm.save { }
        advanceUntilIdle()

        assertNull(vm.ui.value.savedJobId)
    }

    @Test
    fun missingDraftShowsMissing() = runTest(dispatcher) {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val vm = ReviewViewModel(
            draftId = "nope",
            drafts = DraftRepository(store),
            routes = RouteRepository(store),
            media = FakeMediaTools(fileSystem),
            nowMs = { 0L },
            newId = { "x" },
        )
        advanceUntilIdle()
        assertEquals(true, vm.ui.value.missing)
    }

    // ── W3-07: AI suggestions ────────────────────────────────────────────

    @Test
    fun suggestionsPreselectTopCandidateFromCompletedJob() = runTest(dispatcher) {
        val h = harness()
        h.api.resultRaw = resultJson()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-1", "complete", 1L))
        val vm = h.vm()
        advanceUntilIdle()

        assertEquals("job-1", h.api.gotten)
        val ui = vm.ui.value
        // Unknown node n99 dropped; n1 keeps both, rank-descending, top first.
        assertEquals(listOf("n1"), ui.suggestions.keys.toList())
        assertEquals(listOf(0.9, 0.2), ui.suggestions.getValue("n1").map { it.rank })
        assertEquals(0, ui.suggestionIndex.getValue("n1"))
        assertEquals("muse-spark-1.3 + sam-3.1", ui.aiModels)
    }

    // ── W5-01: local model ─────────────────────────────────────────────

    private class FakeLocalVision : app.simt.echo.ai.LocalVision {
        override val modelId: String = "glimmer-test"
        var calls: Int = 0

        override suspend fun propose(
            checkpoint: app.simt.echo.ai.LocalCheckpoint,
        ): List<app.simt.echo.ai.AiCandidate> {
            calls++
            if (checkpoint.nodeId != "n1") return emptyList()
            return app.simt.echo.ai.cleanLocalCandidates(
                checkpoint,
                modelId,
                listOf(
                    app.simt.echo.ai.LocalCandidateDto(
                        title = "Red pillar",
                        samPhrase = "red pillar",
                        description = "A red pillar.",
                        box = listOf(100, 100, 400, 500),
                        distinctiveness = 0.9,
                        permanence = 1.0,
                        cue = "Turn right at the red pillar",
                    ),
                ),
            )
        }
    }

    @Test
    fun localSuggestionsAppearWhenNoCloudJobExists() = runTest(dispatcher) {
        val h = harness()
        val vision = FakeLocalVision()
        val vm = ReviewViewModel(
            draftId = h.draftId,
            drafts = h.drafts,
            routes = h.routes,
            media = h.media,
            nowMs = { 1_762_000_100_000L },
            newId = { "lm-test-x" },
            api = h.api,
            router = app.simt.echo.ai.AiRouter(vision),
        )
        advanceUntilIdle()

        val ui = vm.ui.value
        assertTrue(vision.calls > 0, "local provider was consulted")
        assertEquals(listOf("n1"), ui.suggestions.keys.toList())
        assertEquals("Red pillar", ui.suggestions.getValue("n1").single().title)
        assertEquals("glimmer-test (local)", ui.aiModels)
        // Cloud was never asked for a job (no record exists).
        assertNull(h.api.gotten)
    }

    @Test
    fun noSuggestionsWithoutCompletedJob() = runTest(dispatcher) {
        val h = harness()
        h.api.resultRaw = resultJson()
        val vm = h.vm()
        advanceUntilIdle()

        assertTrue(vm.ui.value.suggestions.isEmpty())
        assertNull(vm.ui.value.aiModels)
        assertNull(h.api.gotten)
    }

    @Test
    fun useSuggestionMintsVerifiedLandmarkWithoutMovingGeometry() = runTest(dispatcher) {
        val h = harness()
        h.api.resultRaw = resultJson()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-1", "complete", 1L))
        val vm = h.vm()
        advanceUntilIdle()

        val before = vm.ui.value
        val nodesBefore = before.nodes.map { it.node }
        val legsBefore = draftRoute().legs
        val pathBefore = draftRoute().path

        vm.useSuggestion("n1")
        advanceUntilIdle()

        val ui = vm.ui.value
        val node = ui.nodes[1]
        assertEquals(1, node.landmarks.size)
        val landmark = node.landmarks.single()
        assertEquals(Confirmation.CREATOR_VERIFIED, landmark.confirmation)
        assertEquals(LandmarkSource.AI_GROUNDED, landmark.source)
        assertEquals("Red pillar", landmark.title)
        assertEquals("muse-spark-1.3 + sam-3.1", landmark.models)
        assertEquals(0.86, landmark.samConfidence!!, 0.001)
        assertEquals(listOf(landmark.id), node.node.landmarkIds)
        assertNotNull(h.drafts.readDraftFile("draft-1", landmark.cropAssetId!!))
        // The used option is consumed; the next-best preselects.
        assertEquals(1, ui.suggestions.getValue("n1").size)
        // Provenance filled; geometry untouched.
        val saved = h.drafts.loadDraftRoute("draft-1")!!
        assertEquals("job-1", saved.ai!!.jobId)
        assertEquals("muse-spark-1.3", saved.ai!!.scoutModel)
        val nodesAfter = ui.nodes.map { it.node }
        assertEquals(nodesBefore.map { it.id }, nodesAfter.map { it.id })
        assertEquals(nodesBefore.filter { it.id != "n1" }, nodesAfter.filter { it.id != "n1" })
        assertEquals(
            nodesBefore.first { it.id == "n1" }.copy(landmarkIds = listOf(landmark.id)),
            nodesAfter.first { it.id == "n1" },
        )
        assertEquals(legsBefore, saved.legs)
        assertEquals(pathBefore, saved.path)
    }

    @Test
    fun nextAndSkipCycleSuggestions() = runTest(dispatcher) {
        val h = harness()
        h.api.resultRaw = resultJson()
        h.drafts.saveJobRecord("draft-1", JobRecord("job-1", "complete", 1L))
        val vm = h.vm()
        advanceUntilIdle()

        vm.nextSuggestion("n1")
        advanceUntilIdle()
        assertEquals(1, vm.ui.value.suggestionIndex.getValue("n1"))
        vm.nextSuggestion("n1")
        advanceUntilIdle()
        assertEquals(0, vm.ui.value.suggestionIndex.getValue("n1"))

        vm.skipSuggestions("n1")
        advanceUntilIdle()
        assertTrue(vm.ui.value.suggestions.isEmpty())
        // Skipping touches nothing on disk: no landmarks, no crops.
        assertTrue(h.drafts.loadDraftRoute("draft-1")!!.landmarks.isEmpty())
        assertTrue(h.media.cropped.isEmpty())
    }

    @Test
    fun unparseableResultKeepsManualFlow() = runTest(dispatcher) {
        val h = harness()
        h.api.resultRaw = "garbage {{{"
        h.drafts.saveJobRecord("draft-1", JobRecord("job-1", "complete", 1L))
        val vm = h.vm()
        advanceUntilIdle()

        assertTrue(vm.ui.value.suggestions.isEmpty())
        assertNotNull(vm.ui.value.error)
        // Manual editing still works underneath the error line.
        vm.renameNode("n1", "arch")
        advanceUntilIdle()
        assertEquals("arch", vm.ui.value.nodes[1].node.label)
    }
}
