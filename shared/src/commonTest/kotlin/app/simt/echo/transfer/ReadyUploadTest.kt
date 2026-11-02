package app.simt.echo.transfer

import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W11-09 gate: no byte leaves before consent, a metered network blocks
 * upload, discard deletes staged uploads, each closed window stages,
 * and Finish creates the job against already-uploaded blobs.
 */
class ReadyUploadTest {

    private class FakeV11Api : V11CloudApi {
        val uploadCreates = mutableListOf<List<UploadFileRef>>()
        val puts = mutableListOf<UploadFile>()
        val uploadDeletes = mutableListOf<String>()
        val stagedCreates = mutableListOf<Triple<String, List<String>, String>>()
        var deleteError: EchoApiError? = null
        var putError: EchoApiError? = null
        private var nextUpload = 0

        override suspend fun submit(manifestJson: String, files: List<UploadFile>): String = "job-x"

        override suspend fun get(jobId: String): JobPoll = JobPoll("complete", JobStage.COMPLETE)

        override suspend fun delete(jobId: String) = Unit

        override suspend fun samEnabled(): Boolean = false

        override suspend fun createUpload(files: List<UploadFileRef>): CreatedUpload {
            uploadCreates += files
            nextUpload++
            return CreatedUpload("upload-$nextUpload", files.map { it.name })
        }

        override suspend fun uploadMissing(uploadId: String): List<String> = emptyList()

        override suspend fun putUploadFile(uploadId: String, file: UploadFile) {
            putError?.let { throw it }
            puts += file
        }

        override suspend fun createJob(manifestJson: String, uploadId: String, idempotencyKey: String, resumeOf: String?): String =
            "job-x"

        override suspend fun deleteUpload(uploadId: String) {
            deleteError?.let { throw it }
            uploadDeletes += uploadId
        }

        override suspend fun createJobFromStaged(
            manifestJson: String,
            uploadIds: List<String>,
            idempotencyKey: String,
        ): String {
            stagedCreates += Triple(manifestJson, uploadIds, idempotencyKey)
            return "job-1"
        }

        override fun watchEvents(jobId: String, lastEventId: String?): Flow<JobEvent> = emptyFlow()
    }

    private fun entry(id: String, d: Double = 12.0): GlimpseEntry = GlimpseEntry(
        bucketIndex = 1L,
        tNs = 1_000_000_000L,
        odometerM = d,
        score = 0.9,
        sharp = 0.8,
        hash = id.hashCode().toLong(),
        jpegPath = "glimpse/$id.jpg",
    )

    private data class Harness(
        val api: FakeV11Api,
        val drafts: DraftRepository,
        val uploader: ReadyUploader,
    )

    /** A scanner that clears every frame; an explicit `null` means no detector. */
    private val faceFree = object : FaceDetector {
        override fun verdictFor(entry: GlimpseEntry): FaceVerdict = FaceVerdict.FACE_FREE
    }

    private fun harness(
        metered: Boolean = false,
        network: NetworkKind = if (metered) NetworkKind.METERED else NetworkKind.UNMETERED,
        detector: FaceDetector? = faceFree,
    ): Harness {
        val api = FakeV11Api()
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/data/drafts/draft-1".toPath())
        val drafts = DraftRepository(FileStore(fileSystem, "/data".toPath()))
        val uploader = ReadyUploader(
            api = api,
            network = NetworkGate { network },
            drafts = drafts,
            draftId = "draft-1",
            faceDetector = detector,
            nowMs = { 1_762_000_100_000L },
        )
        return Harness(api, drafts, uploader)
    }

    private fun bytesFor(vararg ids: String): (GlimpseEntry) -> ByteArray? {
        val map = ids.associateWith { "$it-bytes".encodeToByteArray() }
        return { entry -> entry.jpegPath?.substringAfterLast('/')?.removeSuffix(".jpg")?.let(map::get) }
    }

    @Test
    fun noByteLeavesBeforeConsent() = runTest {
        val h = harness()
        val outcome = h.uploader.stageWindow(listOf(entry("a"), entry("b")), bytesFor("a", "b"))
        assertEquals(ReadyStageOutcome.SkippedNoConsent, outcome)
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
        assertFailsWith<EchoApiError.ConsentRequired> { h.uploader.finish("""{"ok":true}""", "key-1") }
        assertTrue(h.api.stagedCreates.isEmpty())
    }

    @Test
    fun meteredNetworkBlocksUpload() = runTest {
        val h = harness(metered = true)
        h.uploader.grantConsent()
        val outcome = h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        assertEquals(ReadyStageOutcome.SkippedMetered, outcome)
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun wifiOnlyOffUploadsOnMetered() = runTest {
        val h = harness(metered = true)
        h.uploader.grantConsent(ReadyUploadConsent(enabled = true, wifiOnly = false))
        val outcome = h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        assertEquals(ReadyStageOutcome.Staged, outcome)
        assertEquals(1, h.api.uploadCreates.size)
    }

    @Test
    fun mobileDataAllowedUploadsOnMeteredViaSettingsPolicy() = runTest {
        val h = harness(network = NetworkKind.METERED)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = true))
        assertEquals(ReadyStageOutcome.Staged, h.uploader.stageWindow(listOf(entry("a")), bytesFor("a")))
        assertEquals(1, h.api.puts.size)
    }

    @Test
    fun mobileDataDefaultKeepsWifiOnly() = runTest {
        val h = harness(network = NetworkKind.METERED)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = false))
        assertEquals(ReadyStageOutcome.SkippedMetered, h.uploader.stageWindow(listOf(entry("a")), bytesFor("a")))
        assertTrue(h.api.uploadCreates.isEmpty())
    }

    @Test
    fun roamingNeverUploadsEvenWithMobileData() = runTest {
        val h = harness(network = NetworkKind.ROAMING)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = true))
        assertEquals(ReadyStageOutcome.SkippedMetered, h.uploader.stageWindow(listOf(entry("a")), bytesFor("a")))
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
    }

    @Test
    fun offlineNeverTouchesTheApiOrTheDraft() = runTest {
        val h = harness(network = NetworkKind.OFFLINE)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = true))
        assertEquals(ReadyStageOutcome.SkippedOffline, h.uploader.stageWindow(listOf(entry("a")), bytesFor("a")))
        assertTrue(h.api.uploadCreates.isEmpty())
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun networkPolicyTableIsExactlyTheDocumentedOne() {
        val wifiOnly = ReadyUploadConsent.granted(mobileDataAllowed = false)
        val withData = ReadyUploadConsent.granted(mobileDataAllowed = true)
        assertEquals(
            listOf(true, false, false, false),
            NetworkKind.entries.map { wifiOnly.allows(it) },
            "Wi-Fi only: unmetered yes; metered, roaming, offline no",
        )
        assertEquals(
            listOf(true, true, false, false),
            NetworkKind.entries.map { withData.allows(it) },
            "mobile data allowed: unmetered and metered yes; roaming, offline no",
        )
    }

    @Test
    fun capabilitiesMapToTheNetworkKind() {
        assertEquals(NetworkKind.OFFLINE, networkKindOf(hasInternet = false, notMetered = true, notRoaming = true))
        assertEquals(NetworkKind.UNMETERED, networkKindOf(hasInternet = true, notMetered = true, notRoaming = true))
        assertEquals(NetworkKind.METERED, networkKindOf(hasInternet = true, notMetered = false, notRoaming = true))
        assertEquals(NetworkKind.ROAMING, networkKindOf(hasInternet = true, notMetered = false, notRoaming = false))
        assertEquals(NetworkKind.OFFLINE, OfflineNetworkGate.kind())
    }

    @Test
    fun windowCloseTriggersUpload() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        val outcome = h.uploader.stageWindow(listOf(entry("a"), entry("b")), bytesFor("a", "b"))
        assertEquals(ReadyStageOutcome.Staged, outcome)
        assertEquals(1, h.api.uploadCreates.size)
        assertEquals(listOf("a.jpg", "b.jpg"), h.api.uploadCreates.single().map { it.name })
        assertEquals(listOf("a.jpg", "b.jpg"), h.api.puts.map { it.id })
        assertEquals(listOf("upload-1"), h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun privacyFilterRunsBeforeUpload() = runTest {
        val detector = object : FaceDetector {
            override fun verdictFor(entry: GlimpseEntry): FaceVerdict =
                if (entry.jpegPath?.contains("faced") == true) FaceVerdict.HAS_FACE else FaceVerdict.FACE_FREE
        }
        val h = harness(detector = detector)
        h.uploader.grantConsent()
        val outcome = h.uploader.stageWindow(listOf(entry("faced"), entry("free")), bytesFor("faced", "free"))
        assertEquals(ReadyStageOutcome.Staged, outcome)
        assertEquals(listOf("free.jpg"), h.api.uploadCreates.single().map { it.name })
        assertEquals(listOf("free.jpg"), h.api.puts.map { it.id })
    }

    @Test
    fun unknownScanIsExcludedLikeAFace() = runTest {
        val detector = object : FaceDetector {
            override fun verdictFor(entry: GlimpseEntry): FaceVerdict = FaceVerdict.UNKNOWN
        }
        val h = harness(detector = detector)
        h.uploader.grantConsent()
        // NoOp-style detector clears nothing: the window stages nothing
        // and, critically, PUTs nothing.
        val outcome = h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        assertEquals(ReadyStageOutcome.SkippedEmpty, outcome)
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
    }

    @Test
    fun withoutADetectorNothingIsStagedUnfiltered() = runTest {
        // The consent sheet promises face-filtered strips: no detector, no upload.
        val h = harness(detector = null)
        h.uploader.grantConsent()
        val outcome = h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        assertEquals(ReadyStageOutcome.SkippedNoFilter, outcome)
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun uploadIdIsRecordedBeforeThePutsSoACrashStillDiscards() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        // The process dies (or the network drops) mid-window, after the
        // session exists and before every PUT lands.
        h.api.putError = EchoApiError.Network(RuntimeException("connection lost"))
        assertFailsWith<EchoApiError.Network> {
            h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        }
        assertEquals(listOf("upload-1"), h.drafts.loadReadyStagedRecord("draft-1"))

        h.uploader.discard()
        assertEquals(listOf("upload-1"), h.api.uploadDeletes)
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun finishCreatesJobAgainstStagedBlobsWithoutNewPuts() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        h.uploader.stageWindow(listOf(entry("b")), bytesFor("b"))
        val putsBefore = h.api.puts.size
        assertEquals(2, putsBefore)
        val jobId = h.uploader.finish("""{"schema":1}""", "key-1")
        assertEquals("job-1", jobId)
        assertEquals(1, h.api.stagedCreates.size)
        val (manifest, ids, key) = h.api.stagedCreates.single()
        assertEquals("""{"schema":1}""", manifest)
        assertEquals(listOf("upload-1", "upload-2"), ids)
        assertEquals("key-1", key)
        assertEquals(putsBefore, h.api.puts.size)
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun finishWithNothingStagedFailsClosed() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        assertFailsWith<IllegalStateException> { h.uploader.finish("""{"schema":1}""", "key-1") }
        assertTrue(h.api.stagedCreates.isEmpty())
    }

    @Test
    fun discardDeletesStagedUploads() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        h.uploader.stageWindow(listOf(entry("b")), bytesFor("b"))
        h.uploader.discard()
        assertEquals(listOf("upload-1", "upload-2"), h.api.uploadDeletes)
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun discardFailureKeepsRecord() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stageWindow(listOf(entry("a")), bytesFor("a"))
        h.api.deleteError = EchoApiError.Server(500, "boom")
        assertFailsWith<EchoApiError.Server> { h.uploader.discard() }
        assertNotNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun discardWithNothingStagedIsNoop() = runTest {
        val h = harness()
        h.uploader.discard()
        assertTrue(h.api.uploadDeletes.isEmpty())
    }

    @Test
    fun consentDefaultsOff() {
        val h = harness()
        assertIs<ReadyUploadConsent>(h.uploader.consent)
        assertEquals(false, h.uploader.consent.enabled)
        assertEquals(true, h.uploader.consent.wifiOnly)
    }
}
