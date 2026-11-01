package app.simt.echo.capture

import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W2-05 replay gates: interpolation, cadence, and the replay source tag. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayMotionSourceTest {

    private fun log(): CaptureLog = CaptureLog(
        draftId = "replay-test",
        startedAtEpochMs = 0L,
        motionSource = "echo_builtin_pdr",
        videoStartNs = 0L,
        samples = listOf(
            CaptureSample(tNs = 0L, d = 0.0, yaw = 0.0, p = 1000.0),
            CaptureSample(tNs = 1_000_000_000L, d = 10.0, yaw = PI / 2, p = 1002.0),
        ),
        marks = emptyList(),
        startPhoto = "start.jpg",
    )

    @Test
    fun interpolatesDistanceAndYawAt20Hz() = runTest {
        val source = ReplayMotionSource(log(), this)
        val collected = mutableListOf<MotionUpdate>()
        val job = launch { source.updates.collect { collected += it } }
        runCurrent()
        source.start()
        advanceUntilIdle()
        job.cancel()

        assertTrue(collected.size >= 20, "expected ~20 Hz ticks, got ${collected.size}")
        assertTrue(collected.all { it.source == "replay" })
        val mid = collected.minByOrNull { kotlin.math.abs(it.tNs - 500_000_000L) }
            ?: error("no midpoint update")
        assertEquals(5.0, mid.distanceM, 0.3)
        assertEquals(PI / 4, mid.yawRad, 0.08)
        assertEquals(10.0, collected.last().distanceM, 1e-9)
        assertEquals(PI / 2, collected.last().yawRad, 1e-9)
    }

    @Test
    fun speedScalesCaptureTime() = runTest {
        val source = ReplayMotionSource(log(), this, speed = 2.0)
        val collected = mutableListOf<MotionUpdate>()
        val job = launch { source.updates.collect { collected += it } }
        runCurrent()
        source.start()
        advanceUntilIdle()
        job.cancel()

        // Twice the speed: half the ticks over the same capture span.
        assertTrue(collected.size in 9..12, "expected ~10 ticks, got ${collected.size}")
        assertEquals(10.0, collected.last().distanceM, 1e-9)
    }

    @Test
    fun isMotionSourceAndStopsCleanly() = runTest {
        val source: MotionSource = ReplayMotionSource(log(), this)
        val collected = mutableListOf<MotionUpdate>()
        val job = launch { source.updates.collect { collected += it } }
        runCurrent()
        source.start()
        runCurrent()
        source.stop()
        job.cancel()
        assertTrue(collected.size >= 1)
    }
}
