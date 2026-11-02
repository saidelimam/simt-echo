package app.simt.echo.transfer

import app.simt.echo.platform.TransferProgress
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** W13-14: a rate is only recomputed when bytes arrive, so silence must zero it. */
class ProgressRateTrackerTest {

    private var now = 0L
    private var stalls = 0

    private fun TestScope.tracker() = ProgressRateTracker(backgroundScope, { now })

    @Test
    fun firstSampleHasNoRateAndLaterOnesAreMeasured() = runTest {
        val tracker = tracker()
        assertEquals(0.0, tracker.record(TransferProgress(10L, 100L)) { stalls++ })
        now = 500L
        assertEquals(40.0, tracker.record(TransferProgress(30L, 100L)) { stalls++ })
    }

    @Test
    fun silenceFiresOneStallAfterTheWindow() = runTest {
        val tracker = tracker()
        tracker.record(TransferProgress(10L, 100L)) { stalls++ }
        advanceTimeBy(TRANSFER_STALL_MS - 1)
        runCurrent()
        assertEquals(0, stalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, stalls)
        advanceTimeBy(TRANSFER_STALL_MS * 3)
        runCurrent()
        assertEquals(1, stalls, "a stall is reported once, not repeatedly")
    }

    @Test
    fun everySampleRearmsTheTimer() = runTest {
        val tracker = tracker()
        repeat(5) {
            tracker.record(TransferProgress(10L * (it + 1), 100L)) { stalls++ }
            advanceTimeBy(TRANSFER_STALL_MS - 100)
            runCurrent()
        }
        assertEquals(0, stalls, "bytes keep arriving: never stalled")
    }

    @Test
    fun completeTransferNeverStallsAndResetCancelsTheTimer() = runTest {
        val tracker = tracker()
        tracker.record(TransferProgress(100L, 100L)) { stalls++ }
        advanceTimeBy(TRANSFER_STALL_MS * 3)
        runCurrent()
        assertEquals(0, stalls)

        tracker.record(TransferProgress(10L, 100L)) { stalls++ }
        tracker.reset()
        advanceTimeBy(TRANSFER_STALL_MS * 3)
        runCurrent()
        assertEquals(0, stalls, "a stale timer must not touch a new transfer")
    }
}
