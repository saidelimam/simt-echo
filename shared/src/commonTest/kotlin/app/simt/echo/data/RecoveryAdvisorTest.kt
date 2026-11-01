package app.simt.echo.data

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.transfer.JobRecord
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W4-03 gate: kill-mid-capture (capture without route) and kill-mid-upload
 * (live job) both surface as recoverable; empty store recovers to nothing.
 * Runs on FakeFileSystem — no device, no timing.
 */
class RecoveryAdvisorTest {

    private val fileSystem = FakeFileSystem()
    private val store = FileStore(fileSystem, "/data".toPath())
    private val drafts = DraftRepository(store)

    @Test
    fun emptyStoreHasNothingRecoverable() {
        assertTrue(listRecoverable(drafts).isEmpty())
    }

    @Test
    fun killMidCaptureIsRecoverableWithoutRoute() {
        drafts.saveCapture(capture("draft-a", samples = 21, marks = 1))
        val found = listRecoverable(drafts)
        assertEquals(listOf("draft-a"), found.map { it.draftId })
        assertFalse(found.single().hasRouteDraft)
        assertEquals(21, found.single().sampleCount)
        assertEquals(app.simt.echo.ui.EchoRoute.Record(resumeDraftId = "draft-a"), found.single().destination())
    }

    @Test
    fun finishedSegmentPointsAtReviewAndLiveJobSurvives() {
        drafts.saveCapture(capture("draft-b", samples = 5, marks = 0))
        drafts.saveDraftRoute(app.simt.echo.Fixtures.L_ROUTE.copy(routeId = "draft-b"))
        drafts.saveJobRecord("draft-b", JobRecord("job-1", "working", 7L))
        val found = listRecoverable(drafts)
        assertEquals(1, found.size)
        assertTrue(found.single().hasRouteDraft)
        assertEquals("working", found.single().jobStatus)
        assertEquals(app.simt.echo.ui.EchoRoute.Review("draft-b"), found.single().destination())
    }

    @Test
    fun draftWithoutCaptureNeverLists() {
        // A route draft with no capture is not a kill-mid-capture: ignore.
        drafts.saveDraftRoute(app.simt.echo.Fixtures.L_ROUTE.copy(routeId = "ghost"))
        assertTrue(listRecoverable(drafts).isEmpty())
    }

    private fun capture(id: String, samples: Int, marks: Int) = CaptureLog(
        draftId = id,
        startedAtEpochMs = 1L,
        motionSource = "echo_builtin_pdr",
        videoStartNs = 0L,
        samples = List(samples) { i ->
            CaptureSample(tNs = i.toLong() * 500_000_000L, d = i.toDouble(), yaw = 0.0, p = null)
        },
        marks = List(marks) {
            app.simt.echo.model.Mark(tNs = 0L, d = 1.0, label = "Spot 1")
        },
        startPhoto = "start.jpg",
    )
}
