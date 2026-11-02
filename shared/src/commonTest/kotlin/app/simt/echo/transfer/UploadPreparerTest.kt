package app.simt.echo.transfer

import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.media.clipId
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W11-09: [UploadPreparer] serves both the Finish send and the walk-time
 * stager, so its two inputs the walk changes (a ledger snapshot that was never
 * persisted, and a video that is still being written) are pinned here.
 */
class UploadPreparerTest {

    private val fileSystem = FakeFileSystem()
    private val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
    private var strips = FakeStripMedia(fileSystem)
    private val forged get() = strips.forged
    private val videoCuts get() = strips.videoCuts

    private val node = RouteNode(
        id = "n1", type = NodeType.TURN, atDistanceM = 20.0, label = "pillar",
        turn = TurnDir.RIGHT, cue = "Turn right", captureTMs = 40_000L,
    )
    private val route = EchoRoute(
        routeId = "draft-1", name = "Walk", createdAtEpochMs = 1L, creatorLabel = "c", motionSource = "echo_builtin_pdr",
        totalLengthM = 30.0, start = StartAnchor("Start", "start.jpg", "North"), nodes = listOf(node), legs = emptyList(), path = emptyList(),
    )
    private val log = CaptureLog(
        draftId = "draft-1", startedAtEpochMs = 1L, motionSource = "echo_builtin_pdr", videoStartNs = 0L,
        samples = listOf(CaptureSample(tNs = 0L, d = 0.0, yaw = 0.0), CaptureSample(tNs = 50_000_000_000L, d = 26.0, yaw = 0.0)),
        marks = emptyList(), startPhoto = "start.jpg",
    )

    /** Two kept frames inside the node's [d − 8, d + 1] window, written to disk but never persisted as `entries.json`. */
    private fun seedLiveLedger(): List<GlimpseEntry> {
        val entries = listOf(
            GlimpseEntry(0, 30_000_000_000L, 14.0, 0.9, 0.9, 1L, "glimpse/a.jpg"),
            GlimpseEntry(1, 38_000_000_000L, 19.0, 0.8, 0.8, 2L, "glimpse/b.jpg"),
        )
        drafts.writeDraftFile("draft-1", "glimpse/a.jpg", "frame-a".encodeToByteArray())
        drafts.writeDraftFile("draft-1", "glimpse/b.jpg", "frame-b".encodeToByteArray())
        // The slots the ledger picks fill, as the ledger-only fill leaves them.
        val spec = keyframeSpecs(listOf(node)).single()
        drafts.writeDraftFile("draft-1", spec.fileName(0), "frame-a".encodeToByteArray())
        drafts.writeDraftFile("draft-1", spec.fileName(1), "frame-b".encodeToByteArray())
        return entries
    }

    private fun preparer(stripForge: Boolean, detector: FaceDetector? = null) =
        UploadPreparer(drafts, FakeStripMedia(fileSystem, stripForge).also { strips = it }, "draft-1", detector, Dispatchers.Unconfined) { 1_762_000_100_000L }

    private val clearDetector = object : FaceDetector {
        override fun verdictFor(entry: GlimpseEntry): FaceVerdict = FaceVerdict.FACE_FREE
    }

    @Test
    fun aLiveLedgerSnapshotCutsTheClipAndShipsTheKeyframesWithoutTouchingTheVideo() = runTest {
        val ledger = seedLiveLedger()
        drafts.writeDraftFile("draft-1", "video.mp4", "half-written".encodeToByteArray())

        val prepared = preparer(stripForge = true, detector = clearDetector)
            .prepare(route, log, samEnabled = true, ledger = ledger, useVideo = false)

        assertEquals(listOf("clip_n1.mp4:2"), forged)
        assertEquals(0, videoCuts)
        val spec = keyframeSpecs(listOf(node)).single()
        val ids = prepared.files.map { it.id }.toSet()
        assertEquals(setOf(spec.fileName(0), spec.fileName(1)).map { it.removeSuffix(".jpg").replace('/', '_') }.toSet() + clipId("n1"), ids)
        assertEquals("video/mp4", prepared.files.single { it.id == clipId("n1") }.mime)
    }

    @Test
    fun withoutAStripForgeAnUnfinishedVideoYieldsNoClipAndOnlyKeyframes() = runTest {
        val ledger = seedLiveLedger()
        drafts.writeDraftFile("draft-1", "video.mp4", "half-written".encodeToByteArray())

        val prepared = preparer(stripForge = false)
            .prepare(route, log, samEnabled = true, ledger = ledger, useVideo = false)

        assertTrue(forged.isEmpty())
        assertEquals(0, videoCuts)
        assertTrue(prepared.files.none { it.id == clipId("n1") })
        assertTrue(prepared.files.isNotEmpty())
    }

    @Test
    fun samOffShipsNoClipAtAll() = runTest {
        val ledger = seedLiveLedger()
        val prepared = preparer(stripForge = true).prepare(route, log, samEnabled = false, ledger = ledger, useVideo = false)
        assertTrue(forged.isEmpty())
        assertTrue(prepared.files.none { it.id == clipId("n1") })
    }

    @Test
    fun theDefaultLedgerIsTheDraftsPersistedOne() = runTest {
        seedLiveLedger()
        // Nothing persisted: the default reads entries.json, finds none, and cuts no strip.
        val prepared = preparer(stripForge = true).prepare(route, log, samEnabled = true, useVideo = false)
        assertTrue(forged.isEmpty())
        assertTrue(prepared.files.none { it.id == clipId("n1") })
    }
}
