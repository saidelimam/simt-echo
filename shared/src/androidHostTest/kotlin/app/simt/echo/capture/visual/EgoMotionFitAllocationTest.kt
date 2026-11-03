package app.simt.echo.capture.visual

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EgoMotionFitAllocationTest {
    @Test
    fun jointExpansionAndYawFitAllocatesZeroBytesAfterWarmup() {
        assertAllocationFree(
            egoMotionTestFlow(0.16, 0.18, -0.11, yawResidualRadPerSec = 0.0005),
            expectedHasFoe = true,
        )
    }

    @Test
    fun robustFitAllocatesZeroBytesAfterWarmup() {
        val flow = egoMotionTestFlow(foeX = 0.18, foeY = -0.11)
        flow.dx[2] += 0.01
        flow.dy[2] -= 0.005
        assertAllocationFree(flow, expectedHasFoe = true)
    }

    @Test
    fun standingFitAllocatesZeroBytesWithPrimitiveAbsentFocus() {
        assertAllocationFree(egoMotionTestFlow(expansionPerSec = 0.0), expectedHasFoe = false)
    }

    @Test
    fun sidewaysFitAllocatesZeroBytesWithPrimitiveAbsentFocus() {
        val flow = egoMotionTestFlow(expansionPerSec = 0.0)
        flow.dx.fill(0.12 * flow.deltaSeconds)
        flow.dy.fill(-0.03 * flow.deltaSeconds)
        assertAllocationFree(flow, expectedHasFoe = false)
    }

    private fun assertAllocationFree(flow: BlockFlow, expectedHasFoe: Boolean) {
        val bean = assertIs<ThreadMXBean>(ManagementFactory.getThreadMXBean())
        assertTrue(bean.isThreadAllocatedMemorySupported, "host JVM must support thread allocation accounting")
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        repeat(1_000) { assertTrue(bean.getThreadAllocatedBytes(threadId) >= 0L) }

        val fitter = EgoMotionFit()
        val reused = fitter.fit(flow, usability01 = 1.0)
        assertEquals(expectedHasFoe, reused.hasFoe)
        assertEquals(expectedHasFoe, reused.travelOffsetRad.isFinite())
        assertTrue(reused.inliers >= MIN_BLOCKS)
        assertTrue(reused.confidence01 > 0.5)
        for (iteration in 0 until WARMUP_FITS) {
            fitter.fit(flow, usability01 = 1.0)
        }

        var identicalResults = 0
        var confidentResults = 0
        var presentFocusResults = 0
        var finiteFocusResults = 0
        var absentFocusResults = 0
        var expansionSum = 0.0
        var yawSum = 0.0
        val before = bean.getThreadAllocatedBytes(threadId)
        for (iteration in 0 until MEASURED_FITS) {
            val result = fitter.fit(flow, usability01 = 1.0)
            if (result === reused) identicalResults++
            if (result.inliers >= MIN_BLOCKS && result.confidence01 > 0.5) confidentResults++
            if (result.hasFoe) presentFocusResults++
            if (result.foeX.isFinite() && result.foeY.isFinite() && result.travelOffsetRad.isFinite()) finiteFocusResults++
            if (result.foeX.isNaN() && result.foeY.isNaN() && result.travelOffsetRad.isNaN()) absentFocusResults++
            expansionSum += result.expansionPerSec
            yawSum += result.yawResidualRadPerSec
        }
        val allocatedBytes = bean.getThreadAllocatedBytes(threadId) - before

        assertEquals(MEASURED_FITS, identicalResults, "every fit must return the same mutable result")
        assertEquals(MEASURED_FITS, confidentResults, "every measured fit must exercise a usable twelve-block field")
        assertEquals(if (expectedHasFoe) MEASURED_FITS else 0, presentFocusResults)
        assertEquals(if (expectedHasFoe) MEASURED_FITS else 0, finiteFocusResults)
        assertEquals(if (expectedHasFoe) 0 else MEASURED_FITS, absentFocusResults)
        assertTrue(expansionSum.isFinite() && yawSum.isFinite())
        assertEquals(0L, allocatedBytes, "fit allocated $allocatedBytes bytes; inputs were reused and FOE values must not box")
    }

    companion object {
        private const val MIN_BLOCKS = 6
        private const val WARMUP_FITS = 20_000
        private const val MEASURED_FITS = 2_048
    }
}