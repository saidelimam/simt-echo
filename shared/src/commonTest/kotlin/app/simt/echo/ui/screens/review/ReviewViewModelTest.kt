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
        )
    }

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
}
