package app.simt.echo.transfer

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.media.sha256Hex
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W11-09 gate: no byte leaves before consent, a metered network blocks
 * upload, discard deletes staged uploads, each closed window stages,
 * and Finish creates the job against already-uploaded blobs.
 */
class ReadyUploadTest {

    private data class Harness(
        val api: FakeV11Api,
        val drafts: DraftRepository,
        val uploader: ReadyUploader,
    )

    private fun harness(
        metered: Boolean = false,
        network: NetworkKind = if (metered) NetworkKind.METERED else NetworkKind.UNMETERED,
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
            nowMs = { 1_762_000_100_000L },
        )
        return Harness(api, drafts, uploader)
    }

    private fun file(id: String, content: String = "$id-bytes") = UploadFile(id, "image/jpeg", content.encodeToByteArray())

    private fun sha(file: UploadFile) = sha256Hex(file.bytes)

    @Test
    fun noByteLeavesBeforeConsent() = runTest {
        val h = harness()
        val outcome = h.uploader.stage(listOf(file("a"), file("b")))
        assertEquals(ReadyStageOutcome.SkippedNoConsent, outcome)
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun meteredNetworkBlocksUpload() = runTest {
        val h = harness(metered = true)
        h.uploader.grantConsent()
        assertEquals(ReadyStageOutcome.SkippedMetered, h.uploader.stage(listOf(file("a"))))
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun wifiOnlyOffUploadsOnMetered() = runTest {
        val h = harness(metered = true)
        h.uploader.grantConsent(ReadyUploadConsent(enabled = true, wifiOnly = false))
        assertEquals(ReadyStageOutcome.Staged, h.uploader.stage(listOf(file("a"))))
        assertEquals(1, h.api.uploadCreates.size)
    }

    @Test
    fun mobileDataAllowedUploadsOnMeteredViaSettingsPolicy() = runTest {
        val h = harness(network = NetworkKind.METERED)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = true))
        assertEquals(ReadyStageOutcome.Staged, h.uploader.stage(listOf(file("a"))))
        assertEquals(1, h.api.puts.size)
    }

    @Test
    fun mobileDataDefaultKeepsWifiOnly() = runTest {
        val h = harness(network = NetworkKind.METERED)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = false))
        assertEquals(ReadyStageOutcome.SkippedMetered, h.uploader.stage(listOf(file("a"))))
        assertTrue(h.api.uploadCreates.isEmpty())
    }

    @Test
    fun roamingNeverUploadsEvenWithMobileData() = runTest {
        val h = harness(network = NetworkKind.ROAMING)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = true))
        assertEquals(ReadyStageOutcome.SkippedMetered, h.uploader.stage(listOf(file("a"))))
        assertTrue(h.api.uploadCreates.isEmpty())
        assertTrue(h.api.puts.isEmpty())
    }

    @Test
    fun offlineNeverTouchesTheApiOrTheDraft() = runTest {
        val h = harness(network = NetworkKind.OFFLINE)
        h.uploader.grantConsent(ReadyUploadConsent.granted(mobileDataAllowed = true))
        assertEquals(ReadyStageOutcome.SkippedOffline, h.uploader.stage(listOf(file("a"))))
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
    fun stagingDeclaresHashesPutsTheBytesAndRecordsTheSession() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        val a = file("a")
        val b = file("b")
        assertEquals(ReadyStageOutcome.Staged, h.uploader.stage(listOf(a, b)))
        assertEquals(listOf(UploadFileRef("a", sha(a), a.bytes.size.toLong()), UploadFileRef("b", sha(b), b.bytes.size.toLong())), h.api.uploadCreates.single())
        assertEquals(listOf("a", "b"), h.api.puts.map { it.id })
        val record = checkNotNull(h.drafts.loadReadyStagedRecord("draft-1"))
        assertEquals(listOf("upload-1"), record.uploadIds)
        assertEquals(mapOf("a" to sha(a), "b" to sha(b)), record.files)
    }

    @Test
    fun theSessionIsRecordedBeforeTheFirstPutSoACrashStillDiscards() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.api.putError = EchoApiError.Server(500, "boom")
        assertFailsWith<EchoApiError.Server> { h.uploader.stage(listOf(file("a"))) }
        val record = checkNotNull(h.drafts.loadReadyStagedRecord("draft-1"))
        assertEquals(listOf("upload-1"), record.uploadIds)
        assertTrue(record.files.isEmpty(), "a half-sent batch claims no file")
        h.uploader.discard()
        assertEquals(listOf("upload-1"), h.api.uploadDeletes)
    }

    @Test
    fun finishResendsAFileWhoseBatchNeverFinishedLanding() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.api.putError = EchoApiError.Server(500, "cut off")
        assertFailsWith<EchoApiError.Server> { h.uploader.stage(listOf(file("a"))) }
        h.api.putError = null
        h.api.uploadCreates.clear()

        createJobFromWalkStaging(h.api, h.drafts, "draft-1", "{}", listOf(file("a")), "key-1")

        assertEquals(listOf("a"), h.api.puts.map { it.id }, "the unclaimed file goes in the final session")
        assertEquals(listOf("upload-2", "upload-1"), h.api.stagedCreates.single().second)
    }

    @Test
    fun filesAlreadyStagedWithTheSameBytesAreNotSentAgainButChangedBytesAre() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stage(listOf(file("a"), file("b")))
        assertEquals(ReadyStageOutcome.SkippedEmpty, h.uploader.stage(listOf(file("a"), file("b"))))
        assertEquals(1, h.api.uploadCreates.size)

        assertEquals(ReadyStageOutcome.Staged, h.uploader.stage(listOf(file("a"), file("b", content = "recaptured"), file("c"))))
        assertEquals(listOf("b", "c"), h.api.uploadCreates.last().map { it.name })
        assertEquals(listOf("upload-1", "upload-2"), h.drafts.loadReadyStagedRecord("draft-1")?.uploadIds)
    }

    @Test
    fun anEmptyBatchStagesNothing() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        assertEquals(ReadyStageOutcome.SkippedEmpty, h.uploader.stage(emptyList()))
        assertTrue(h.api.uploadCreates.isEmpty())
    }

    @Test
    fun stagingStopsAtTheSessionCapSoFinishKeepsOneSlot() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        repeat(MAX_STAGED_SESSIONS) { i -> assertEquals(ReadyStageOutcome.Staged, h.uploader.stage(listOf(file("n$i")))) }
        assertEquals(ReadyStageOutcome.SkippedFull, h.uploader.stage(listOf(file("late"))))
        assertEquals(MAX_STAGED_SESSIONS, h.api.uploadCreates.size)
        assertTrue(MAX_STAGED_SESSIONS < 12, "the worker takes 12 sessions; one is for Finish")
    }

    @Test
    fun discardDeletesEverySessionAndClearsTheRecord() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stage(listOf(file("a")))
        h.uploader.stage(listOf(file("b")))
        h.uploader.discard()
        assertEquals(listOf("upload-1", "upload-2"), h.api.uploadDeletes)
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun aFailedDeleteKeepsTheRecordAndRethrows() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stage(listOf(file("a")))
        h.api.deleteError = EchoApiError.Server(500, "boom")
        assertFailsWith<EchoApiError.Server> { h.uploader.discard() }
        assertEquals(listOf("upload-1"), h.drafts.loadReadyStagedRecord("draft-1")?.uploadIds)
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

    @Test
    fun finishWithNothingStagedFallsBackToTheCaller() = runTest {
        val h = harness()
        assertNull(createJobFromWalkStaging(h.api, h.drafts, "draft-1", "{}", listOf(file("a")), "key-1"))
        assertTrue(h.api.stagedCreates.isEmpty())
        assertTrue(h.api.uploadCreates.isEmpty())
    }

    @Test
    fun finishWithEverythingStagedSendsNoByteAndCreatesTheJobFromTheSessions() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stage(listOf(file("a"), file("b")))
        h.uploader.stage(listOf(file("c")))
        h.api.uploadCreates.clear()
        h.api.puts.clear()

        val jobId = createJobFromWalkStaging(h.api, h.drafts, "draft-1", """{"m":1}""", listOf(file("a"), file("b"), file("c")), "key-1")

        assertEquals("job-1", jobId)
        assertTrue(h.api.uploadCreates.isEmpty(), "nothing left to declare")
        assertTrue(h.api.puts.isEmpty(), "no byte leaves at Finish")
        assertEquals(Triple("""{"m":1}""", listOf("upload-1", "upload-2"), "key-1"), h.api.stagedCreates.single())
        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
    }

    @Test
    fun finishSendsOnlyTheRemainderInAFinalSessionListedFirst() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stage(listOf(file("a"), file("b")))
        h.api.uploadCreates.clear()
        h.api.puts.clear()

        // "b" was recaptured in Review since the walk; "z" closed after the last window.
        val files = listOf(file("a"), file("b", content = "recaptured"), file("z"))
        createJobFromWalkStaging(h.api, h.drafts, "draft-1", "{}", files, "key-1")

        assertEquals(listOf("b", "z"), h.api.uploadCreates.single().map { it.name })
        assertEquals(listOf("b", "z"), h.api.puts.map { it.id })
        assertEquals("recaptured", h.api.puts.first { it.id == "b" }.bytes.decodeToString())
        assertEquals(listOf("upload-2", "upload-1"), h.api.stagedCreates.single().second, "the final session wins a name conflict")
    }

    @Test
    fun aSessionTheServerForgotClearsTheRecordSoTheCallerCanUploadEverything() = runTest {
        val h = harness()
        h.uploader.grantConsent()
        h.uploader.stage(listOf(file("a")))
        h.api.staleOnCreate = EchoApiError.UploadStale("upload_expired")

        assertFailsWith<EchoApiError.UploadStale> {
            createJobFromWalkStaging(h.api, h.drafts, "draft-1", "{}", listOf(file("a")), "key-1")
        }

        assertNull(h.drafts.loadReadyStagedRecord("draft-1"))
        assertNull(createJobFromWalkStaging(h.api, h.drafts, "draft-1", "{}", listOf(file("a")), "key-1"))
    }
}
