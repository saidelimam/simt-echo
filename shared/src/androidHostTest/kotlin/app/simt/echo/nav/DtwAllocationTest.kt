package app.simt.echo.nav

import app.simt.echo.nav.ghost.Dtw
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
}
