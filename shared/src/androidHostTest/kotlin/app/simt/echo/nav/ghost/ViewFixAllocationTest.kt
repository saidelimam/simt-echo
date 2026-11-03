package app.simt.echo.nav.ghost

import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.ViewStrip
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.io.encoding.Base64
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W17-03 zero-allocation and host-cost probe for [ViewFixMatcher]. */
class ViewFixAllocationTest {

    @Test
    fun viewFixMatchAllocatesZeroBytesAfterWarmup() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val strips = (1..8).map { idx ->
            val bytes = ByteArray(ViewStrip.BYTES) { k ->
                (sin(k * (0.15 + idx * 0.08) + idx * 1.7) * 90.0).toInt().toByte()
            }
            ViewStrip(atDistanceM = idx * 2.0, dataB64 = Base64.encode(bytes))
        }
        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(40) { ParallaxProfiler.quantiseRho(0.22) }),
            trackability = Base64.encode(ByteArray(40) { 220.toByte() }),
            viewStrips = strips,
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
        // Single stored strip at 8.0 m so the single-waypoint match path is exercised cleanly.
        val singleProfile = profile.copy(viewStrips = listOf(strips[3]))
        val matcher = ViewFixMatcher(singleProfile)
        val live = Base64.decode(strips[3].dataB64)

        var hits = 0
        repeat(2_000) { i ->
            if (matcher.match(live, odoM = 8.0 + (i % 3) * 0.1, meanS = 8.0, sigmaM = 1.5)) hits++
        }
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(2_000) { i ->
            if (matcher.match(live, odoM = 8.0 + (i % 3) * 0.1, meanS = 8.0, sigmaM = 1.5)) hits++
        }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertTrue(hits > 0)
        assertEquals(0L, allocated, "ViewFixMatcher.match must allocate 0 bytes on the warmed path, allocated $allocated B")
    }
}
