package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTapMux
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWorld
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VisualAllocationHostTest {
    @Test
    fun fullTexturedPipelineDispatchAndRingDrainAllocateZeroBytesAfterWarmup() {
        val bean = assertIs<ThreadMXBean>(ManagementFactory.getThreadMXBean())
        assertTrue(bean.isThreadAllocatedMemorySupported, "host must expose allocation accounting")
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        repeat(1_000) { assertTrue(bean.getThreadAllocatedBytes(threadId) >= 0L) }
        val fixture = VisualPipelineHostFixture(WARM_FRAMES + MEASURED_FRAMES)
        try {
            assertFalse(fixture.dispatch(0), "first frame seeds the actual kernel")
            var warmUsable = 0
            for (i in 1 until WARM_FRAMES) if (fixture.dispatch(i)) warmUsable++
            assertEquals(WARM_FRAMES - 1, warmUsable, "warmup must reach fit, result getters and ring drain")
            val processedBefore = fixture.consumer.framesProcessed
            val ledgerBefore = fixture.ledgerFrames
            var usable = 0
            val before = bean.getThreadAllocatedBytes(threadId)
            for (i in WARM_FRAMES until WARM_FRAMES + MEASURED_FRAMES) {
                if (fixture.dispatch(i)) usable++
            }
            val allocated = bean.getThreadAllocatedBytes(threadId) - before
            assertEquals(MEASURED_FRAMES, usable, "every measured timestamp must yield a fresh usable result")
            assertEquals(MEASURED_FRAMES.toLong(), fixture.consumer.framesProcessed - processedBefore)
            assertEquals(MEASURED_FRAMES.toLong(), fixture.ledgerFrames - ledgerBefore)
            assertEquals(0L, fixture.mux.consumerFailures)
            assertEquals(0L, fixture.consumer.ring.dropped)
            assertTrue(fixture.checksum.isFinite())
            assertEquals(0L, allocated, "full dispatch/flow/fit/publish/drain path allocated $allocated bytes")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun spscStressPreservesAllFieldCorrelationsAndAccountsForEveryOverwrite() {
        val ring = VisualEgoRing(16)
        val start = CountDownLatch(1)
        val readerReady = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        var reads = 0L
        var lastRead = 0L
        val producer = thread(start = false, isDaemon = true, name = "visual-ring-producer") {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                assertTrue(readerReady.await(5, TimeUnit.SECONDS))
                val ego = VisualEgo()
                for (i in 1..STRESS_FRAMES) {
                    if (cancelled.get()) break
                    fill(ego, i)
                    assertTrue(ring.publish(i.toLong(), ego, i / 1_000_000.0, VisualReason.entries[i % VisualReason.entries.size]))
                    ego.clear()
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                done.set(true)
            }
        }
        val reader = thread(start = false, isDaemon = true, name = "visual-ring-reader") {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                val target = VisualMotionFrame()
                readerReady.countDown()
                while (!cancelled.get()) {
                    if (ring.poll(target)) {
                        assertTrue(target.tNs > lastRead, "successful polls must be strictly FIFO")
                        assertCorrelated(target)
                        lastRead = target.tNs
                        reads++
                    } else if (done.get()) {
                        // Retry once after observing completion: the preceding poll may
                        // have raced the final publish, but no writer can race this one.
                        if (!ring.poll(target)) break
                        assertTrue(target.tNs > lastRead)
                        assertCorrelated(target)
                        lastRead = target.tNs
                        reads++
                    }
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                cancelled.set(true)
            }
        }
        producer.start()
        reader.start()
        start.countDown()
        producer.join(15_000L)
        reader.join(15_000L)
        cancelled.set(true)
        producer.join(1_000L)
        reader.join(1_000L)
        assertFalse(producer.isAlive, "publisher must finish without waiting for a reader")
        assertFalse(reader.isAlive, "poll must return rather than spin waiting for publication")
        failure.get()?.let { throw AssertionError("SPSC worker failed", it) }
        assertTrue(reads > 0L)
        assertEquals(STRESS_FRAMES.toLong(), lastRead)
        assertEquals(STRESS_FRAMES.toLong(), reads + ring.dropped)
    }

    @Test
    fun fullRingPublisherFinishesWithoutAnyReaderAndRetainsOnlyBoundedTail() {
        val ring = VisualEgoRing(4)
        val failure = AtomicReference<Throwable?>(null)
        val cancelled = AtomicBoolean(false)
        val producer = thread(isDaemon = true, name = "visual-ring-unread-producer") {
            try {
                val ego = VisualEgo()
                for (i in 1..STRESS_FRAMES) {
                    if (cancelled.get()) break
                    fill(ego, i)
                    assertTrue(ring.publish(i.toLong(), ego, i / 1_000_000.0, VisualReason.entries[i % VisualReason.entries.size]))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        producer.join(15_000L)
        val finishedWithoutReader = !producer.isAlive
        cancelled.set(true)
        producer.join(1_000L)
        assertTrue(finishedWithoutReader, "bounded overwrite must not wait for consumer capacity")
        failure.get()?.let { throw AssertionError("publisher failed", it) }
        val target = VisualMotionFrame()
        for (i in STRESS_FRAMES - ring.capacity + 1..STRESS_FRAMES) {
            assertTrue(ring.poll(target))
            assertEquals(i.toLong(), target.tNs)
            assertCorrelated(target)
        }
        assertFalse(ring.poll(target))
        assertEquals((STRESS_FRAMES - ring.capacity).toLong(), ring.dropped)
        assertEquals(4, ring.capacity)
    }

    private fun fill(ego: VisualEgo, i: Int) {
        ego.expansionPerSec = i + 0.125
        ego.foeX = i + 0.25
        ego.foeY = -i - 0.375
        ego.yawResidualRadPerSec = i + 0.5
        ego.yawSigmaRadPerSec = i + 0.625
        ego.confidence01 = i / 1_000_000.0
        ego.inliers = i % 13
        ego.hasFoe = i % 2 == 0
    }

    private fun assertCorrelated(frame: VisualMotionFrame) {
        val i = frame.tNs.toInt()
        assertEquals(i + 0.125, frame.ego.expansionPerSec)
        assertEquals(i + 0.25, frame.ego.foeX)
        assertEquals(-i - 0.375, frame.ego.foeY)
        assertEquals(i + 0.5, frame.ego.yawResidualRadPerSec)
        assertEquals(i + 0.625, frame.ego.yawSigmaRadPerSec)
        assertEquals(i / 1_000_000.0, frame.ego.confidence01)
        assertEquals(i % 13, frame.ego.inliers)
        assertEquals(i % 2 == 0, frame.ego.hasFoe)
        assertEquals(i / 1_000_000.0, frame.usability01)
        assertEquals(VisualReason.entries[i % VisualReason.entries.size], frame.reason)
    }

    companion object {
        // Match the kernel probes' warmup: compilation/resolution of the full
        // dispatch path must finish before measuring steady-state allocation.
        private const val WARM_FRAMES = 20_000
        private const val MEASURED_FRAMES = 512
        private const val STRESS_FRAMES = 200_000
    }
}

internal class VisualPipelineHostFixture(frameCount: Int) {
    private val world = SimWorld(SimEnvironment.CORRIDOR, seed = 1_234_567L)
    private val from = SimPose(0.0, 57.0, 1.5, 0.0)
    private val to = from.copy(y = from.y + 0.04, yawRad = YAW_RAD)
    private val first = world.render(from, START_NS).luma
    private val second = world.render(to, START_NS + INTERVAL_NS).luma
    private val intrinsics = CameraIntrinsics(world.phone.focalLengthPx, 120.0, 90.0, IntrinsicsSource.CHARACTERISTICS)
    // Samples, unlike luma storage, cannot be reused: their timestamps are immutable.
    private val samples = Array(frameCount) { i ->
        FrameSample(START_NS + i * INTERVAL_NS, 240, 180, 1, if (i % 2 == 0) first else second, intrinsics = intrinsics, visualClockAligned = true)
    }
    private val input = object : VisualMotionInput {
        private val baseW = cos(PI / 4.0)
        private val baseX = sin(PI / 4.0)
        private val turnedW = cos(-YAW_RAD / 2.0) * baseW
        private val turnedX = cos(-YAW_RAD / 2.0) * baseX
        private val turnedY = sin(-YAW_RAD / 2.0) * baseX
        private val turnedZ = sin(-YAW_RAD / 2.0) * baseW
        override fun isAvailable(tNs: Long): Boolean = true
        override fun readAt(tNs: Long, meanLuma: Double, target: VisualFramePose): Boolean {
            val turned = ((tNs - START_NS) / INTERVAL_NS) % 2L == 1L
            target.qw = if (turned) turnedW else baseW
            target.qx = if (turned) turnedX else baseX
            target.qy = if (turned) turnedY else 0.0
            target.qz = if (turned) turnedZ else 0.0
            target.mode = PoseMode.HELD_UP
            target.angularRateRadPerSec = 0.0
            return true
        }
    }
    val mux = FrameTapMux()
    val consumer = VisualMotionConsumer(mux, input, intervalNs = INTERVAL_NS)
    private val flow = VisualFlow()
    var ledgerFrames = 0L
        private set
    var checksum = 0.0
        private set

    init {
        mux.setConsumer { ledgerFrames++ }
        check(consumer.start())
    }

    fun dispatch(index: Int): Boolean {
        val sample = samples[index]
        val before = consumer.framesProcessed
        val wanted = mux.wantsFrame(sample.tNs)
        mux.onFrame(sample)
        val result = consumer.drain(sample.tNs) ?: return false
        val ego = result.ego
        val offset = ego.travelOffsetRad
        val sigma = ego.yawSigmaRadPerSec
        checksum += ego.expansionPerSec + ego.yawResidualRadPerSec + (if (sigma.isFinite()) sigma else 0.0) +
            ego.confidence01 + ego.inliers + result.usability01 + result.reason.ordinal +
            (if (ego.yawResolved) 1.0 else 0.0) + (if (offset.isFinite()) offset else 0.0)
        return wanted && consumer.framesProcessed == before + 1L && result.tNs == sample.tNs &&
            result.reason == VisualReason.NONE && result.usability01 >= 0.5 && ego.confidence01 >= 0.5 &&
            ego.inliers >= 6 && ego.hasFoe && offset.isFinite() && (!ego.yawResolved || sigma.isFinite())
    }

    fun flowOnly(index: Int): Boolean {
        val sample = samples[index]
        val measured = flow.onFrame(sample.luma, sample.tNs, intrinsics, deltaYawRad = if (index % 2 == 1) YAW_RAD else -YAW_RAD)
        checksum += flow.flow.count
        return measured && flow.flow.count >= 6
    }

    fun close() {
        consumer.stop()
        mux.setConsumer(null)
    }

    companion object {
        private const val START_NS = 1_000_000_000L
        private const val INTERVAL_NS = 125_000_000L
        private const val YAW_RAD = 0.015
    }
}