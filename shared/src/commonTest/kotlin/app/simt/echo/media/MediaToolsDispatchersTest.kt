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
import kotlinx.coroutines.withContext
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
 * [ConfinedFake] mirrors `AndroidMediaTools`' confinement (decode and file
 * IO on IO, bitmap scaling on Default — both injected); the test drives it
 * from Main exactly like `ProcessingViewModel` does (`ensureKeyframes` plus
 * direct crop/clip/duration calls) and asserts every recorded execution
 * landed elsewhere. It also pins the batch discipline: keyframe extraction
 * for a route goes out as a single [MediaTools.extractFrames] call, which is
 * what lets the Android actual open one retriever per batch.
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

    private class ConfinedFake(
        private val io: CoroutineDispatcher,
        private val cpu: CoroutineDispatcher,
        private val fileSystem: FileSystem,
    ) : MediaTools {
        val ops = mutableListOf<Op>()
        var batchCalls = 0
        var framesWritten = 0

        private fun note(name: String, context: kotlin.coroutines.CoroutineContext) {
            ops += Op(name, context[ContinuationInterceptor] as? CoroutineDispatcher)
        }

        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
            extractFrames(video, listOf(FrameRequest(atMs, maxSide, out)))
        }

        override suspend fun extractFrames(video: Path, frames: List<FrameRequest>) {
            batchCalls++
            withContext(io) {
                for (frame in frames) {
                    note("extractFrame", coroutineContext)
                    frame.out.parent?.let { fileSystem.createDirectories(it) }
                    fileSystem.write(frame.out) { write("frame${frame.atMs}".encodeToByteArray()) }
                    framesWritten++
                }
            }
        }

        override suspend fun videoDurationMs(video: Path): Long? = withContext(io) {
            note("videoDurationMs", coroutineContext)
            60_000L
        }

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) {
            withContext(io) { note("cropJpeg-decode", coroutineContext) }
            withContext(cpu) { note("cropJpeg-scale", coroutineContext) }
            withContext(io) {
                out.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(out) { write("crop".encodeToByteArray()) }
            }
        }

        override suspend fun cutClip(
            video: Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: Path,
        ): ClipInfo = withContext(io) {
            note("cutClip", coroutineContext)
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write("clip".encodeToByteArray()) }
            ClipInfo(out, frameCount = 12, durationMs = endMs - startMs)
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
    fun mediaWorkNeverRunsOnMain() = runTest(main) {
        val fileSystem = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
        drafts.saveDraftRoute(route())
        val draftDir = "/data/drafts/d1".toPath()
        fileSystem.createDirectories(draftDir)
        fileSystem.write(draftDir / "video.mp4") { write("video".encodeToByteArray()) }

        val io = StandardTestDispatcher(testScheduler, "echo-io")
        val cpu = StandardTestDispatcher(testScheduler, "echo-cpu")
        val media = ConfinedFake(io, cpu, fileSystem)
        val video = drafts.draftFilePath("d1", CaptureSession.VIDEO_FILE)

        // Called from Main, exactly like ProcessingViewModel does.
        ensureKeyframes(drafts, media, "d1", route())
        media.cropJpeg(video, CropBox(0.1f, 0.1f, 0.5f, 0.5f), 512, drafts.draftFilePath("d1", "crop.jpg"))
        media.cutClip(video, 36_000L, 41_000L, 10, drafts.draftFilePath("d1", "kf/clip_n1.mp4"))
        media.videoDurationMs(video)
        advanceUntilIdle()

        assertTrue(media.ops.isNotEmpty(), "expected media work to run")
        val onMain = media.ops.filter { it.dispatcher === main }
        assertTrue(onMain.isEmpty(), "media work ran on Main: $onMain")

        // TURN (3 frames) + DESTINATION (2 frames) in one batch.
        assertEquals(1, media.batchCalls, "keyframes must go out as a single batch")
        assertEquals(5, media.framesWritten)

        // Decode on IO, scale on Default — never Main.
        assertTrue(media.ops.any { it.name == "extractFrame" && it.dispatcher === io })
        assertTrue(media.ops.any { it.name == "cropJpeg-scale" && it.dispatcher === cpu })
        assertTrue(media.ops.any { it.name == "cutClip" && it.dispatcher === io })
    }

    @Test
    fun ensureKeyframesSkipsPresentFilesWithoutTouchingMedia() = runTest(main) {
        val fileSystem = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
        drafts.saveDraftRoute(route())
        val draftDir = "/data/drafts/d1".toPath()
        fileSystem.createDirectories(draftDir / "kf")
        fileSystem.write(draftDir / "video.mp4") { write("video".encodeToByteArray()) }
        // All five keyframes already on disk: the batch must stay empty.
        for (name in listOf("kf/n1_0.jpg", "kf/n1_1.jpg", "kf/n1_2.jpg", "kf/n2_0.jpg", "kf/n2_1.jpg")) {
            fileSystem.write(draftDir / name) { write("cached".encodeToByteArray()) }
        }

        val io = StandardTestDispatcher(testScheduler, "echo-io")
        val cpu = StandardTestDispatcher(testScheduler, "echo-cpu")
        val media = ConfinedFake(io, cpu, fileSystem)

        ensureKeyframes(drafts, media, "d1", route())
        advanceUntilIdle()

        assertEquals(0, media.batchCalls, "no missing keyframes, no batch")
        assertTrue(media.ops.isEmpty())
    }
}
