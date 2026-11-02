package app.simt.echo.media

import app.simt.echo.capture.CaptureSession
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.test.assertTrue

/**
 * W10-02 G1 gate: no [MediaTools] work runs on the Main dispatcher.
 *
 * The real [ConfinedMediaTools] wraps a [RecordingMediaTools] delegate that
 * notes which dispatcher each call ran on. The test drives the decorator
 * from Main exactly like `ProcessingViewModel` does (`ensureKeyframes` plus
 * direct crop/duration calls) and asserts the delegate never ran there. It
 * also pins the batch discipline: keyframe extraction for a route goes out
 * as a single [MediaTools.extractFrames] call, which is what lets the
 * Android actual open one retriever per batch, and that the Transformer
 * operations keep the caller's dispatcher (they need its looper, plan W3-01).
 */
class MediaToolsDispatchersTest {

    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class Op(
        val name: String,
        val dispatcher: CoroutineDispatcher?,
    )

    /** Delegate that records the dispatcher of every call and writes stub files. */
    private class RecordingMediaTools(private val fileSystem: FileSystem) : MediaTools {
        val ops = mutableListOf<Op>()
        var batchCalls = 0
        var framesWritten = 0
        override var supportsStripForge = true

        private suspend fun note(name: String) {
            ops += Op(name, coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)
        }

        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
            extractFrames(video, listOf(FrameRequest(atMs, maxSide, out)))
        }

        override suspend fun extractFrames(video: Path, frames: List<FrameRequest>) {
            batchCalls++
            for (frame in frames) {
                note("extractFrame")
                frame.out.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(frame.out) { write("frame${frame.atMs}".encodeToByteArray()) }
                framesWritten++
            }
        }

        override suspend fun videoDurationMs(video: Path): Long? {
            note("videoDurationMs")
            return 60_000L
        }

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) {
            note("cropJpeg")
        }

        override suspend fun cutClip(
            video: Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: Path,
        ): ClipInfo {
            note("cutClip")
            return ClipInfo(out, frameCount = 12, durationMs = endMs - startMs)
        }

        override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo {
            note("forgeStripClip")
            return ClipInfo(out, frameCount = frames.size, durationMs = 1_000L)
        }

        override suspend fun transcodeStripClip(video: Path, startMs: Long, endMs: Long, out: Path): ClipInfo {
            note("transcodeStripClip")
            return ClipInfo(out, frameCount = 12, durationMs = endMs - startMs)
        }
    }

    private fun route(): EchoRoute = EchoRoute(
        routeId = "d1",
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

    @Test
    fun blockingMediaWorkNeverRunsOnMain() = runTest(main) {
        val fileSystem = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
        drafts.saveDraftRoute(route())
        val draftDir = "/data/drafts/d1".toPath()
        fileSystem.createDirectories(draftDir)
        fileSystem.write(draftDir / "video.mp4") { write("video".encodeToByteArray()) }

        val io = StandardTestDispatcher(testScheduler, "echo-io")
        val delegate = RecordingMediaTools(fileSystem)
        val media = ConfinedMediaTools(delegate, io)
        val video = drafts.draftFilePath("d1", CaptureSession.VIDEO_FILE)

        // Called from Main, exactly like ProcessingViewModel does.
        ensureKeyframes(drafts, media, "d1", route(), ioDispatcher = io)
        media.extractFrame(video, 1_000L, 512, drafts.draftFilePath("d1", "one.jpg"))
        media.cropJpeg(video, CropBox(0.1f, 0.1f, 0.5f, 0.5f), 512, drafts.draftFilePath("d1", "crop.jpg"))
        media.videoDurationMs(video)
        advanceUntilIdle()

        assertTrue(delegate.ops.isNotEmpty(), "expected media work to run")
        val onMain = delegate.ops.filter { it.dispatcher === main }
        assertTrue(onMain.isEmpty(), "media work ran on Main: $onMain")
        val offIo = delegate.ops.filter { it.dispatcher !== io }
        assertTrue(offIo.isEmpty(), "media work ran off the IO dispatcher: $offIo")
        assertEquals(
            setOf("extractFrame", "cropJpeg", "videoDurationMs"),
            delegate.ops.map { it.name }.toSet(),
        )

        // Start photo (no ledger) + TURN (3 frames) + DESTINATION (2 frames) in one batch, plus the single extractFrame.
        assertEquals(2, delegate.batchCalls, "ensureKeyframes batches once, plus the single extractFrame call")
        assertEquals(7, delegate.framesWritten)
    }

    @Test
    fun transformerOperationsKeepTheCallersDispatcher() = runTest(main) {
        val io = StandardTestDispatcher(testScheduler, "echo-io")
        val delegate = RecordingMediaTools(FakeFileSystem())
        val media = ConfinedMediaTools(delegate, io)
        val video = "/data/video.mp4".toPath()
        val out = "/data/out.mp4".toPath()

        // Media3's Transformer needs the caller's looper (plan W3-01).
        media.cutClip(video, 0L, 1_000L, 10, out)
        media.forgeStripClip(listOf(out), out)
        media.transcodeStripClip(video, 0L, 3_000L, out)
        advanceUntilIdle()

        assertEquals(
            listOf("cutClip", "forgeStripClip", "transcodeStripClip"),
            delegate.ops.map { it.name },
        )
        assertTrue(delegate.ops.all { it.dispatcher === main }, "ops: ${delegate.ops}")
    }

    @Test
    fun forgeSupportComesFromTheDelegate() = runTest(main) {
        val delegate = RecordingMediaTools(FakeFileSystem())
        val media = ConfinedMediaTools(delegate, StandardTestDispatcher(testScheduler))

        delegate.supportsStripForge = true
        assertTrue(media.supportsStripForge)
        delegate.supportsStripForge = false
        assertEquals(false, media.supportsStripForge)
    }

    @Test
    fun ensureKeyframesSkipsPresentFilesWithoutTouchingMedia() = runTest(main) {
        val fileSystem = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
        drafts.saveDraftRoute(route())
        val draftDir = "/data/drafts/d1".toPath()
        fileSystem.createDirectories(draftDir / "kf")
        fileSystem.write(draftDir / "video.mp4") { write("video".encodeToByteArray()) }
        // Start photo and all five keyframes already on disk: the batch must stay empty.
        for (name in listOf("start.jpg", "kf/n1_0.jpg", "kf/n1_1.jpg", "kf/n1_2.jpg", "kf/n2_0.jpg", "kf/n2_1.jpg")) {
            fileSystem.write(draftDir / name) { write("cached".encodeToByteArray()) }
        }

        val io = StandardTestDispatcher(testScheduler, "echo-io")
        val delegate = RecordingMediaTools(fileSystem)
        val media = ConfinedMediaTools(delegate, io)

        ensureKeyframes(drafts, media, "d1", route(), ioDispatcher = io)
        advanceUntilIdle()

        assertEquals(0, delegate.batchCalls, "no missing keyframes, no batch")
        assertTrue(delegate.ops.isEmpty())
    }
}
