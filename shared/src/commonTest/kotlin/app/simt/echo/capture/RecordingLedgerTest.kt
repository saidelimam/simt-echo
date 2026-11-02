package app.simt.echo.capture

import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.platform.MotionUpdate
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan W11-01/W11-02: the Record-time ledger scores against motion and persists into the draft. */
class RecordingLedgerTest {

    private class RecordingTap : FrameTap {
        var consumer: FrameConsumer? = null
            private set
        override fun setConsumer(consumer: FrameConsumer?) {
            this.consumer = consumer
        }
    }

    private val drafts = DraftRepository(FileStore(FakeFileSystem(), "/data".toPath()))
    private val tap = RecordingTap()
    private val ledger = RecordingLedger(drafts, "d1", tap)

    private fun motion(tNs: Long, d: Double, yaw: Double = 0.0) = MotionUpdate(
        tNs = tNs, distanceM = d, yawRad = yaw, stepCount = 0,
        isStep = false, pressureHpa = null, source = "echo_builtin_pdr",
    )

    private fun sharp(tNs: Long, jpeg: (() -> ByteArray?)? = { byteArrayOf(7) }) = FrameSample(
        tNs = tNs,
        width = 32,
        height = 32,
        decimation = 1,
        luma = ByteArray(32 * 32) { i -> (if (((i % 32) / 4 + (i / 32) / 4) % 2 == 0) 200 else 40).toByte() },
        jpeg = jpeg,
    )

    @Test
    fun attachRegistersAndDetachClearsTheConsumer() {
        assertNull(tap.consumer)
        ledger.attach()
        assertNotNull(tap.consumer)
        ledger.detach()
        assertNull(tap.consumer)
    }

    @Test
    fun keptFramesLandInTheDraftWithDraftRelativePaths() {
        ledger.onMotion(motion(1L, d = 1.2))
        ledger.onFrame(sharp(tNs = 5L))
        ledger.persist()

        val entry = assertNotNull(drafts.loadGlimpseEntries("d1")).single()
        assertEquals(2L, entry.bucketIndex)
        assertEquals(1.2, entry.odometerM, 1e-12)
        assertEquals("glimpse/2.jpg", entry.jpegPath)
        assertEquals(listOf<Byte>(7), drafts.readDraftFile("d1", "glimpse/2.jpg")?.toList())
    }

    @Test
    fun framesWithoutAJpegKeepMetadataOnly() {
        ledger.onFrame(sharp(tNs = 5L, jpeg = null))
        ledger.persist()

        assertNull(assertNotNull(drafts.loadGlimpseEntries("d1")).single().jpegPath)
    }

    @Test
    fun anEmptyLedgerNeverOverwritesEarlierEntries() {
        ledger.onFrame(sharp(tNs = 5L))
        ledger.persist()
        val resumed = RecordingLedger(drafts, "d1", tap)

        resumed.persist()

        assertEquals(1, assertNotNull(drafts.loadGlimpseEntries("d1")).size)
    }

    @Test
    fun yawRateIsTheShortWayRoundTheCircle() {
        // A quarter radian in half a second.
        assertEquals(0.5, yawRate(0.0, 0.25, 500_000_000L), 1e-12)
        // Crossing ±π is a 0.2 rad turn, not a 2π - 0.2 spin.
        assertEquals(0.2, yawRate(PI - 0.1, -PI + 0.1, 1_000_000_000L), 1e-9)
        assertTrue(yawRate(0.3, 0.3, 1_000_000_000L) == 0.0)
    }

    @Test
    fun aTurningFrameScoresBelowAStillOneAtTheSameOdometer() {
        // Same bucket, same pixels: the frame taken mid-turn has a lower
        // stability term, so the still frame replaces it.
        ledger.onMotion(motion(0L, d = 0.1, yaw = 0.0))
        ledger.onMotion(motion(500_000_000L, d = 0.1, yaw = 1.0)) // 2 rad/s
        ledger.onFrame(sharp(tNs = 1L, jpeg = { byteArrayOf(1) }))
        ledger.onMotion(motion(1_000_000_000L, d = 0.1, yaw = 1.0)) // 0 rad/s
        ledger.onFrame(sharp(tNs = 2L, jpeg = { byteArrayOf(2) }))
        ledger.persist()

        val entry = assertNotNull(drafts.loadGlimpseEntries("d1")).single()
        assertEquals(2L, entry.tNs)
        assertEquals(listOf<Byte>(2), drafts.readDraftFile("d1", "glimpse/0.jpg")?.toList())
    }
}
