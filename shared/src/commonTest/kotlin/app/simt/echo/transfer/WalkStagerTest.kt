package app.simt.echo.transfer

import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.capture.segment
import app.simt.echo.data.FileStore
import app.simt.echo.media.ensureKeyframes
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.Mark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W11-09 gate: the trigger stages a checkpoint once it is final, under the
 * names Finish will use, and never lets a refused network or a server error
 * touch the recording. Real [liveCheckpoints], [UploadPreparer] and
 * [ReadyUploader] over a fake server and a synthetic 54 m L-shaped walk with
 * one mark.
 */
class WalkStagerTest {

    private val fileSystem = FakeFileSystem()
    private val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
    private val api = FakeV11Api()
    private val strips = FakeStripMedia(fileSystem)
    private var network = NetworkKind.UNMETERED
    private var walkerAtM = 0.0
    private var samOn = true
    private var samProbes = 0

    private val clear = object : FaceDetector {
        override fun verdictFor(entry: GlimpseEntry): FaceVerdict = FaceVerdict.FACE_FREE
    }

    /** 24 m heading 0, then 30 m heading 90, one sample per 0.5 m, a mark at 40 m. */
    private val full: CaptureLog = run {
        val samples = mutableListOf<CaptureSample>()
        var d = 0.0
        var t = T0_NS
        samples += CaptureSample(tNs = t, d = 0.0, yaw = 0.0)
        for ((length, headingDeg) in listOf(24.0 to 0.0, 30.0 to 90.0)) {
            var walked = 0.0
            while (walked < length - 1e-9) {
                d += 0.5
                walked += 0.5
                t += TICK_NS
                samples += CaptureSample(tNs = t, d = d, yaw = headingDeg * PI / 180.0)
            }
        }
        CaptureLog(
            draftId = "draft-1", startedAtEpochMs = 0L, motionSource = "echo_builtin_pdr", videoStartNs = T0_NS,
            samples = samples, marks = listOf(Mark(tNs = 0L, d = 40.0, label = "Spot 1")), startPhoto = "start.jpg",
        )
    }

    /** One kept JPEG per 0.5 m bucket, on disk from the first step like the recording ledger writes them. */
    private val ledger: List<GlimpseEntry> = full.samples.mapIndexed { i, s ->
        GlimpseEntry(i.toLong(), s.tNs, s.d, 0.9, 0.9, i.toLong(), "glimpse/b$i.jpg")
    }.also { entries ->
        entries.forEach { drafts.writeDraftFile("draft-1", it.jpegPath!!, "jpeg-${it.bucketIndex}".encodeToByteArray()) }
    }

    private fun logAt(d: Double): CaptureLog = full.copy(
        samples = full.samples.filter { it.d <= d },
        marks = full.marks.filter { it.d <= d },
    )

    private val uploader = ReadyUploader(api, NetworkGate { network }, drafts, "draft-1") { 1_762_000_100_000L }

    private fun stager(): WalkStager = WalkStager(
        cfg = EchoConfig(),
        drafts = drafts,
        draftId = "draft-1",
        preparer = UploadPreparer(drafts, strips, "draft-1", clear, Dispatchers.Unconfined) { 1_762_000_100_000L },
        uploader = uploader,
        samEnabled = { samProbes++; samOn },
        liveLog = { logAt(walkerAtM) },
        liveLedger = { ledger.filter { it.odometerM <= walkerAtM } },
    )

    private fun consent() = uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = false))

    @Test
    fun nothingStagesUntilACheckpointIsFinal() = runTest {
        consent()
        val stager = stager()
        walkerAtM = 24.0 + 3.0
        assertNull(stager.evaluate())
        assertTrue(api.uploadCreates.isEmpty())
        assertNull(drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun aFinalCheckpointStagesItsKeyframesAndClipUnderTheNamesFinishWillUse() = runTest {
        consent()
        walkerAtM = 24.0 + 6.5
        assertEquals(ReadyStageOutcome.Staged, stager().evaluate())

        val declared = api.uploadCreates.single().map { it.name }
        assertTrue(declared.contains("clip_n1"), declared.toString())
        assertTrue(declared.any { it.startsWith("kf_n1_") }, declared.toString())
        assertTrue(declared.none { it.contains("n0") || it.contains("n2") }, "START and the walker's own position never stage: $declared")
        assertTrue(strips.forged.single().startsWith("clip_n1.mp4:"), strips.forged.toString())
        assertEquals(0, strips.videoCuts)
        assertEquals(declared.toSet(), api.puts.map { it.id }.toSet(), "every declared file's bytes landed")
        assertEquals(declared.toSet(), drafts.loadReadyStagedRecord("draft-1")?.files?.keys)
    }

    @Test
    fun theSameCheckpointIsNeverStagedTwiceAndALaterOneIs() = runTest {
        consent()
        val stager = stager()
        walkerAtM = 24.0 + 6.5
        stager.evaluate()
        val first = api.uploadCreates.size
        assertNull(stager.evaluate(), "nothing new since the last pass")
        assertEquals(first, api.uploadCreates.size)

        walkerAtM = 40.0 + 6.5
        assertEquals(ReadyStageOutcome.Staged, stager.evaluate())
        val second = api.uploadCreates.last().map { it.name }
        assertTrue(second.all { it.contains("n2") || it.contains("clip_n2") }, "only the mark's files go in the second batch: $second")
        assertEquals(1, samProbes, "the server's SAM switch is asked once per walk")
    }

    @Test
    fun finishSendsOnlyTheDestinationBecauseTheStagedBytesAreExactlyWhatFinishBuilds() = runTest {
        consent()
        val stager = stager()
        walkerAtM = 54.0
        assertEquals(ReadyStageOutcome.Staged, stager.evaluate())
        val stagedNames = drafts.loadReadyStagedRecord("draft-1")!!.files.keys
        assertTrue(stagedNames.any { it.contains("n1") } && stagedNames.any { it.contains("n2") }, stagedNames.toString())
        api.uploadCreates.clear()
        api.puts.clear()

        // Finish, the way Processing does it: persisted ledger, final route, the same preparer.
        drafts.saveGlimpseEntries("draft-1", ledger)
        val route = segment(full, EchoConfig(), "draft-1", 0L)
        ensureKeyframes(drafts, strips, "draft-1", route, ioDispatcher = Dispatchers.Unconfined)
        val prepared = UploadPreparer(drafts, strips, "draft-1", clear, Dispatchers.Unconfined) { 1_762_000_200_000L }
            .prepare(route, full, samEnabled = true)
        assertTrue(prepared.files.map { it.id }.containsAll(stagedNames), "Finish builds every staged name")

        val jobId = createJobFromWalkStaging(api, drafts, "draft-1", prepared.manifestJson, prepared.files, "key-1")

        assertEquals("job-1", jobId)
        val sent = api.uploadCreates.single().map { it.name }
        assertTrue(sent.isNotEmpty() && sent.all { it.contains("n3") }, "only the destination's files are left for Finish: $sent")
        assertEquals(2, api.stagedCreates.single().second.size, "the final session plus the one walk batch")
    }

    @Test
    fun aRefusedNetworkIsRetriedOnTheNextPassNotForgotten() = runTest {
        consent()
        val stager = stager()
        walkerAtM = 24.0 + 6.5
        network = NetworkKind.OFFLINE
        assertEquals(ReadyStageOutcome.SkippedOffline, stager.evaluate())
        network = NetworkKind.METERED
        assertEquals(ReadyStageOutcome.SkippedMetered, stager.evaluate(), "Wi-Fi only refuses mobile data")
        assertTrue(api.uploadCreates.isEmpty())

        network = NetworkKind.UNMETERED
        assertEquals(ReadyStageOutcome.Staged, stager.evaluate())
    }

    @Test
    fun withoutConsentNoByteMovesAndNothingIsWrittenToTheDraft() = runTest {
        walkerAtM = 24.0 + 6.5
        assertEquals(ReadyStageOutcome.SkippedNoConsent, stager().evaluate())
        assertTrue(api.uploadCreates.isEmpty() && api.puts.isEmpty())
        assertNull(drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun aServerErrorIsSwallowedAndTheCheckpointRetried() = runTest {
        consent()
        val stager = stager()
        walkerAtM = 24.0 + 6.5
        api.putError = EchoApiError.Server(500, "boom")
        assertNull(stager.evaluate(), "the recording must not see the failure")
        api.putError = null
        assertEquals(ReadyStageOutcome.Staged, stager.evaluate())
    }

    @Test
    fun cancellationIsNeverSwallowed() = runTest {
        consent()
        val stager = WalkStager(
            cfg = EchoConfig(), drafts = drafts, draftId = "draft-1",
            preparer = UploadPreparer(drafts, strips, "draft-1", clear, Dispatchers.Unconfined) { 0L },
            uploader = uploader, samEnabled = { true },
            liveLog = { throw CancellationException("recording stopped") },
            liveLedger = { emptyList() },
        )
        assertFailsWith<CancellationException> { stager.evaluate() }
    }

    @Test
    fun runEvaluatesOnlyEveryThreeMetres() = runTest {
        consent()
        var reads = 0
        val stager = WalkStager(
            cfg = EchoConfig(), drafts = drafts, draftId = "draft-1",
            preparer = UploadPreparer(drafts, strips, "draft-1", clear, Dispatchers.Unconfined) { 0L },
            uploader = uploader, samEnabled = { true },
            liveLog = { reads++; null },
            liveLedger = { emptyList() },
        )
        stager.run((0..120).map { it * 0.25 }.asFlow())
        // 30 m of walking in 0.25 m ticks: a pass at 0, 3, 6 ... 30.
        assertEquals(11, reads)
    }

    private companion object {
        const val T0_NS = 1_000_000_000L
        const val TICK_NS = 1_000_000_000L
    }
}
