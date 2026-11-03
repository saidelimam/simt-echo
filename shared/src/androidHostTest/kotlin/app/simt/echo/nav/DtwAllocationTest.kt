package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.nav.ghost.Dtw
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.Observation
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DtwAllocationTest {
    @Test
    fun scratchReuseAndCapturedInlineCostsAllocateZeroBytes() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val scratch = Dtw.Scratch(20)
        val a = DoubleArray(20) { it.toDouble() }
        val b = DoubleArray(20) { it + 0.1 }
        var total = 0.0
        repeat(30_000) { total += Dtw.distance(20, 20, 2, scratch, mean = true) { i, j -> val d = a[i] - b[j]; d * d } }
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(30_000) { total += Dtw.distance(20, 20, 2, scratch, mean = true) { i, j -> val d = a[i] - b[j]; d * d } }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertTrue(total > 0.0)
        assertEquals(0L, allocated)
    }

    @Test
    fun ghostFilterPredictObserveAndAdaptiveResizeAllocateZeroBytes() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 7L)
        val headingObs = Observation.Heading(0.02, 0.90)
        val speedObs = Observation.VisualSpeed(1.0, 1.02, 0.12)

        repeat(500) { i ->
            filter.predict(0.05)
            filter.observe(headingObs)
            filter.observe(speedObs)
            if (i % 50 == 0) filter.resizeParticles(GhostFilter.MIN_PARTICLE_COUNT)
            if (i % 50 == 25) filter.resizeParticles(GhostFilter.PARTICLE_COUNT)
        }
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(500) { i ->
            filter.predict(0.05)
            filter.observe(headingObs)
            filter.observe(speedObs)
            if (i % 50 == 0) filter.resizeParticles(GhostFilter.MIN_PARTICLE_COUNT)
            if (i % 50 == 25) filter.resizeParticles(GhostFilter.PARTICLE_COUNT)
        }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(0L, allocated, "GhostFilter predict/observe/resize must allocate 0 bytes, allocated $allocated B")
    }
}
