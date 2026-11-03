package app.simt.echo.capture.visual

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VisualImuHistoryAllocationTest {
    @Test
    fun warmedWritesAndBracketedReadsAllocateZeroBytesWithReusedInputs() {
        val bean = assertIs<ThreadMXBean>(ManagementFactory.getThreadMXBean())
        assertTrue(bean.isThreadAllocatedMemorySupported, "host JVM must support thread allocation accounting")
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        repeat(1_000) { assertTrue(bean.getThreadAllocatedBytes(threadId) >= 0L) }
        val history = VisualImuHistory()
        val matrix = rotationX(65.0)
        val target = VisualFramePose()
        history.onRotation(0L, matrix)
        var time = 0L
        for (iteration in 0 until WARMUP_EVENTS) {
            time += INTERVAL_NS
            assertTrue(writeAndRead(history, matrix, target, time))
        }

        var validEvents = 0
        var heldEvents = 0
        var quaternionSum = 0.0
        val before = bean.getThreadAllocatedBytes(threadId)
        for (iteration in 0 until MEASURED_EVENTS) {
            time += INTERVAL_NS
            if (writeAndRead(history, matrix, target, time)) validEvents++
            if (target.mode == PoseMode.HELD_UP) heldEvents++
            quaternionSum += target.qw
        }
        val allocatedBytes = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(MEASURED_EVENTS, validEvents, "every event must exercise both writes, interpolation, exact and rejected reads")
        assertEquals(MEASURED_EVENTS, heldEvents)
        assertTrue(quaternionSum.isFinite() && quaternionSum > 0.0)
        assertEquals(0L, allocatedBytes, "IMU events/readAt allocated $allocatedBytes bytes with matrix and target reused")
    }

    @Test
    fun concurrentOverwriteNeverPublishesATornQuaternion() {
        val history = VisualImuHistory(4)
        val identity = rotationX(0.0)
        val tilted = rotationX(120.0)
        val advertisedTime = AtomicLong(0L)
        val start = CountDownLatch(1)
        history.onRotation(0L, identity)
        val producer = Thread {
            start.await()
            for (index in 1..50_000) {
                val time = index * INTERVAL_NS
                history.onRotation(time, if (index % 2 == 0) identity else tilted)
                advertisedTime.set(time)
            }
        }
        val target = VisualFramePose()
        assertTrue(history.readAt(0L, 100.0, target))
        producer.start()
        var coherent = true
        start.countDown()
        try {
            for (attempt in 0 until 20_000) {
                val time = advertisedTime.get()
                if (history.readAt(time, 100.0, target)) {
                    val tiltedFrame = (time / INTERVAL_NS) % 2L != 0L
                    val w = if (tiltedFrame) 0.5 else 1.0
                    val x = if (tiltedFrame) sin(PI / 3.0) else 0.0
                    if (abs(target.qw - w) > 1e-6 || abs(target.qx - x) > 1e-6 ||
                        target.qy != 0.0 || target.qz != 0.0 || !target.angularRateRadPerSec.isFinite()
                    ) coherent = false
                }
            }
        } finally {
            producer.join(10_000L)
        }
        assertFalse(producer.isAlive, "bounded producer must complete")
        assertTrue(history.readAt(advertisedTime.get(), 100.0, target))
        assertEquals(1.0, target.qw, 1e-6)
        assertEquals(0.0, target.qx, 1e-6)
        assertTrue(coherent, "a successful read must match its exact sample despite concurrent slot overwrites")
    }

    private fun writeAndRead(history: VisualImuHistory, matrix: FloatArray, target: VisualFramePose, time: Long): Boolean {
        history.onAccelerometer(time, 0.0, 9.81 * matrix[7], 9.81 * matrix[8])
        history.onRotation(time, matrix)
        val available = history.isAvailable(time)
        val interpolated = history.readAt(time - INTERVAL_NS / 2L, 100.0, target)
        val exact = history.readAt(time, 100.0, target)
        val extrapolated = history.readAt(time + 1L, 100.0, target)
        return available && interpolated && exact && !extrapolated && target.angularRateRadPerSec.isFinite()
    }

    private fun rotationX(degrees: Double): FloatArray {
        val angle = degrees * PI / 180.0
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    companion object {
        private const val INTERVAL_NS = 20_000_000L
        private const val WARMUP_EVENTS = 20_000
        private const val MEASURED_EVENTS = 2_048
    }
}