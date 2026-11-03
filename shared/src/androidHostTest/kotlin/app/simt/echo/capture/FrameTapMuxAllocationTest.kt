package app.simt.echo.capture

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTapMuxAllocationTest {
    @Test
    fun warmDispatchAndInterestChecksAllocateZeroBytes() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val mux = FrameTapMux()
        val sample = FrameSample(1L, 8, 8, 1, ByteArray(64))
        var calls = 0
        val sink = FrameConsumer { calls++ }
        mux.setConsumer(sink)
        repeat(3) { mux.addConsumer(FrameConsumer { calls++ }) }
        repeat(50_000) { mux.wantsFrame(1L); mux.onFrame(sample) }
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(100_000) { mux.wantsFrame(1L); mux.onFrame(sample) }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(600_000, calls)
        assertEquals(0L, allocated, "mux bytes allocated after warmup")
    }
}