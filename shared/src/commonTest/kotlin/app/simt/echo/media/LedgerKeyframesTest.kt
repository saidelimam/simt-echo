package app.simt.echo.media

import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Plan W11-02 wiring gate: `ensureKeyframes` fills `kf/` slots from the
 * persisted ledger first and falls back to video per slot. The
 * timestamp-seek fallback path itself is pinned by [MediaToolsTest] and
 * [MediaToolsDispatchersTest], which stay untouched.
 */
class LedgerKeyframesTest {

    private class FakeMedia(private val fileSystem: FileSystem) : MediaTools {
        var batchCalls = 0
        val requested = mutableListOf<FrameRequest>()

        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
            extractFrames(video, listOf(FrameRequest(atMs, maxSide, out)))
        }

        override suspend fun extractFrames(video: Path, frames: List<FrameRequest>) {
            batchCalls++
            requested += frames
            for (frame in frames) {
                frame.out.parent?.let { fileSystem.createDirectories(it) }
                fileSystem.write(frame.out) { write("video${frame.atMs}".encodeToByteArray()) }
            }
        }

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) {
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write("crop".encodeToByteArray()) }
        }

        override suspend fun cutClip(video: Path, startMs: Long, endMs: Long, maxFps: Int, out: Path): ClipInfo {
            out.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(out) { write("clip".encodeToByteArray()) }
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
        path = emptyList(),
    )

    private fun entry(d: Double, score: Double = 1.0, jpeg: String? = "glimpse/b${(d * 2).toLong()}.jpg"): GlimpseEntry =
        GlimpseEntry(
            bucketIndex = (d * 2).toLong(),
            tNs = (d * 1_000_000_000L).toLong(),
            odometerM = d,
            score = score,
            sharp = score,
            hash = d.toLong(),
            jpegPath = jpeg,
        )

    /** Ledger covering both nodes' windows plus the start, with bytes on disk. */
    private fun seedLedger(drafts: DraftRepository, entries: List<GlimpseEntry>) {
        for (e in entries) {
            e.jpegPath?.let { drafts.writeDraftFile("d1", it, "ledger${e.odometerM}".encodeToByteArray()) }
        }
        drafts.saveGlimpseEntries("d1", entries)
    }

    private fun fullLedger(): List<GlimpseEntry> = buildList {
        add(entry(0.5, score = 7.0))
        for (d in 12..21) add(entry(d.toDouble()))
        for (d in 37..46) add(entry(d.toDouble()))
    }

    @Test
    fun ledgerFillsKeyframesWithoutVideo() = runTest {
        val fs = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fs, "/data".toPath()))
        drafts.saveDraftRoute(route())
        seedLedger(drafts, fullLedger())
        val media = FakeMedia(fs)
        ensureKeyframes(drafts, media, "d1", route())
        assertEquals(0, media.batchCalls, "ledger covers every slot: video must stay untouched")
        // TURN triple: nearest to 16, 19, 20.75 → 16, 19, 21.
        assertEquals("ledger16.0", drafts.readDraftFile("d1", "kf/n1_0.jpg")?.decodeToString())
        assertEquals("ledger19.0", drafts.readDraftFile("d1", "kf/n1_1.jpg")?.decodeToString())
        assertEquals("ledger21.0", drafts.readDraftFile("d1", "kf/n1_2.jpg")?.decodeToString())
        // DESTINATION double: nearest to 42, 45.
        assertEquals("ledger42.0", drafts.readDraftFile("d1", "kf/n2_0.jpg")?.decodeToString())
        assertEquals("ledger45.0", drafts.readDraftFile("d1", "kf/n2_1.jpg")?.decodeToString())
        // Start photo filled from the best frame at d ≤ 1 m.
        assertEquals("ledger0.5", drafts.readDraftFile("d1", "start.jpg")?.decodeToString())
    }

    @Test
    fun absentLedgerFallsBackToVideo() = runTest {
        val fs = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fs, "/data".toPath()))
        drafts.saveDraftRoute(route())
        fs.createDirectories("/data/drafts/d1".toPath())
        fs.write("/data/drafts/d1/video.mp4".toPath()) { write("video".encodeToByteArray()) }
        val media = FakeMedia(fs)

        ensureKeyframes(drafts, media, "d1", route())

        assertEquals(1, media.batchCalls, "no ledger: one video batch as before")
        assertEquals(6, media.requested.size, "start photo + TURN triple + DESTINATION double")
        assertTrue((drafts.readDraftFile("d1", "kf/n1_1.jpg")?.decodeToString() ?: "").startsWith("video"))
    }

    @Test
    fun missingStartPhotoWithoutLedgerStartEntryIsExtractedFromVideo() = runTest {
        val fs = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fs, "/data".toPath()))
        drafts.saveDraftRoute(route())
        fs.createDirectories("/data/drafts/d1".toPath())
        fs.write("/data/drafts/d1/video.mp4".toPath()) { write("video".encodeToByteArray()) }
        val media = FakeMedia(fs)

        ensureKeyframes(drafts, media, "d1", route())

        val start = media.requested.single { it.out.name == "start.jpg" }
        assertEquals(START_FRAME_FALLBACK_MS, start.atMs)
        assertEquals("video$START_FRAME_FALLBACK_MS", drafts.readDraftFile("d1", "start.jpg")?.decodeToString())

        // The creator's own start photo is never re-extracted over.
        drafts.writeDraftFile("d1", "start.jpg", "photo".encodeToByteArray())
        media.requested.clear()
        ensureKeyframes(drafts, media, "d1", route())
        assertTrue(media.requested.none { it.out.name == "start.jpg" })
        assertEquals("photo", drafts.readDraftFile("d1", "start.jpg")?.decodeToString())
    }

    @Test
    fun ledgerAndVideoWorkRunOnTheInjectedIoDispatcherNotTheCallers() = runTest {
        val fs = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fs, "/data".toPath()))
        drafts.saveDraftRoute(route())
        fs.createDirectories("/data/drafts/d1".toPath())
        fs.write("/data/drafts/d1/video.mp4".toPath()) { write("video".encodeToByteArray()) }
        val io = StandardTestDispatcher(testScheduler, "echo-io")
        var seenBy: Any? = null
        val media = object : MediaTools by FakeMedia(fs) {
            override suspend fun extractFrames(video: Path, frames: List<FrameRequest>) {
                seenBy = currentCoroutineContext()[ContinuationInterceptor]
            }
        }

        ensureKeyframes(drafts, media, "d1", route(), ioDispatcher = io)

        assertTrue(seenBy === io, "ensureKeyframes ran on $seenBy, not the injected IO dispatcher")
    }

    @Test
    fun corruptLedgerFallsBackToVideo() = runTest {
        val fs = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fs, "/data".toPath()))
        drafts.saveDraftRoute(route())
        fs.createDirectories("/data/drafts/d1".toPath())
        fs.write("/data/drafts/d1/video.mp4".toPath()) { write("video".encodeToByteArray()) }
        drafts.writeDraftFile("d1", "glimpse/entries.json", "not json{".encodeToByteArray())
        val media = FakeMedia(fs)

        ensureKeyframes(drafts, media, "d1", route())

        assertEquals(1, media.batchCalls, "corrupt ledger degrades to no ledger")
        assertEquals(6, media.requested.size)
    }

    @Test
    fun missingLedgerJpegFallsBackPerSlot() = runTest {
        val fs = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fs, "/data".toPath()))
        drafts.saveDraftRoute(route())
        fs.createDirectories("/data/drafts/d1".toPath())
        fs.write("/data/drafts/d1/video.mp4".toPath()) { write("video".encodeToByteArray()) }
        // Drop the bytes for the TURN middle pick (19.0 m): that slot alone
        // goes to video, everything else copies from the ledger.
        val entries = fullLedger().filterNot { it.odometerM == 19.0 } + entry(19.0)
        seedLedger(drafts, entries)
        // The 19.0 entry metadata persists but its JPEG never landed on disk.
        fs.delete("/data/drafts/d1/glimpse/b38.jpg".toPath())
        val media = FakeMedia(fs)

        ensureKeyframes(drafts, media, "d1", route())

        assertEquals(1, media.batchCalls)
        assertEquals(1, media.requested.size, "only the unreadable slot seeks video")
        assertEquals("ledger16.0", drafts.readDraftFile("d1", "kf/n1_0.jpg")?.decodeToString())
        assertTrue((drafts.readDraftFile("d1", "kf/n1_1.jpg")?.decodeToString() ?: "").startsWith("video"))
    }

    @Test
    fun existingFilesAreNeverOverwritten() = runTest {
        val fs = FakeFileSystem()
        val drafts = DraftRepository(FileStore(fs, "/data".toPath()))
        drafts.saveDraftRoute(route())
        seedLedger(drafts, fullLedger())
        drafts.writeDraftFile("d1", "kf/n1_1.jpg", "cached".encodeToByteArray())
        drafts.writeDraftFile("d1", "start.jpg", "photo".encodeToByteArray())
        val media = FakeMedia(fs)

        ensureKeyframes(drafts, media, "d1", route())

        assertEquals("cached", drafts.readDraftFile("d1", "kf/n1_1.jpg")?.decodeToString())
        assertEquals("photo", drafts.readDraftFile("d1", "start.jpg")?.decodeToString())
        assertEquals(0, media.batchCalls)
    }

    @Test
    fun glimpseEntriesRoundTrip() {
        val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        assertNull(drafts.loadGlimpseEntries("d1"), "no ledger recorded yet")
        val entries = listOf(entry(0.5, score = 7.0), entry(16.0, jpeg = null))
        drafts.saveGlimpseEntries("d1", entries)
        assertEquals(entries, drafts.loadGlimpseEntries("d1"))
    }
}
