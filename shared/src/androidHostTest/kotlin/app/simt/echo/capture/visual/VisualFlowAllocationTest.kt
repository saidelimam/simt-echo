package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWorld
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VisualFlowAllocationTest {
    @Test
    fun translationFramesAllocateZeroBytesAfterWarmup() {
        assertAllocationFree(yawRad = 0.0)
    }

    @Test
    fun gyroCenteredCoarseSearchAllocatesZeroBytesAfterWarmup() {
        assertAllocationFree(yawRad = 0.045)
    }

    private fun assertAllocationFree(yawRad: Double) {
        val bean = assertIs<ThreadMXBean>(ManagementFactory.getThreadMXBean())
        assertTrue(bean.isThreadAllocatedMemorySupported, "host JVM must support thread allocation accounting")
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        repeat(1_000) { assertTrue(bean.getThreadAllocatedBytes(threadId) >= 0L) }

        val world = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED)
        val from = SimPose(x = 0.0, y = 57.0, z = 1.5, yawRad = 0.0)
        val to = from.copy(y = from.y + 0.04, yawRad = yawRad)
        val first = world.render(from, START_NS).luma
        val second = world.render(to, START_NS + INTERVAL_NS).luma
        val intrinsics = CameraIntrinsics(
            fPx = world.phone.focalLengthPx,
            cx = SimWorld.LUMA_WIDTH / 2.0,
            cy = SimWorld.LUMA_HEIGHT / 2.0,
            source = IntrinsicsSource.CHARACTERISTICS,
        )
        val tracker = VisualFlow()
        var tNs = START_NS
        assertFalse(tracker.onFrame(first, tNs, intrinsics))
        for (pair in 0 until WARMUP_PAIRS) {
            tNs += INTERVAL_NS
            tracker.onFrame(second, tNs, intrinsics, deltaYawRad = yawRad)
            tNs += INTERVAL_NS
            tracker.onFrame(first, tNs, intrinsics, deltaYawRad = -yawRad)
        }
        assertTrue(tracker.flow.count >= MIN_BLOCKS, "warm-up must exercise usable textured flow")

        var usableFrames = 0
        var minimumBlocks = 12
        val before = bean.getThreadAllocatedBytes(threadId)
        for (pair in 0 until MEASURED_PAIRS) {
            tNs += INTERVAL_NS
            if (tracker.onFrame(second, tNs, intrinsics, deltaYawRad = yawRad)) usableFrames++
            minimumBlocks = minOf(minimumBlocks, tracker.flow.count)
            tNs += INTERVAL_NS
            if (tracker.onFrame(first, tNs, intrinsics, deltaYawRad = -yawRad)) usableFrames++
            minimumBlocks = minOf(minimumBlocks, tracker.flow.count)
        }
        val allocatedBytes = bean.getThreadAllocatedBytes(threadId) - before

        assertEquals(MEASURED_PAIRS * 2, usableFrames, "every measured frame must execute the flow path")
        assertTrue(minimumBlocks >= MIN_BLOCKS, "measured frames must retain at least $MIN_BLOCKS valid blocks")
        assertEquals(0L, allocatedBytes, "frame path allocated $allocatedBytes bytes; inputs and timestamps were reused")
    }

    companion object {
        private const val TEST_SEED = 1_234_567L
        private const val START_NS = 1_000_000_000L
        private const val INTERVAL_NS = 125_000_000L
        private const val WARMUP_PAIRS = 5_000
        private const val MEASURED_PAIRS = 256
        private const val MIN_BLOCKS = 6
    }
}
