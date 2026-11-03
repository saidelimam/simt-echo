package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.FrameConsumer
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.FrameTapMux
import app.simt.echo.capture.ImuSample
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.capture.PdrEngine
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWorld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VisualMotionConsumerTest {
    @Test
    fun boundedRingDropsOldestInFifoOrderAndCopiesEveryEgoField() {
        val ring = VisualEgoRing(3)
        val source = VisualEgo()
        for (i in 1..5) {
            source.expansionPerSec = i.toDouble()
            source.foeX = i + 0.25
            source.foeY = -i.toDouble()
            source.yawResidualRadPerSec = i + 0.5
            source.yawSigmaRadPerSec = i + 0.75
            source.confidence01 = i / 10.0
            source.inliers = i
            source.hasFoe = i % 2 == 1
            assertTrue(ring.publish(i.toLong(), source, i / 20.0, VisualReason.NONE))
            source.clear()
        }
        val target = VisualMotionFrame()
        assertEquals(3, ring.capacity)
        for (i in 3..5) {
            assertTrue(ring.poll(target))
            assertEquals(i.toLong(), target.tNs)
            assertEquals(i.toDouble(), target.ego.expansionPerSec)
            assertEquals(i + 0.25, target.ego.foeX)
            assertEquals(-i.toDouble(), target.ego.foeY)
            assertEquals(i + 0.5, target.ego.yawResidualRadPerSec)
            assertEquals(i + 0.75, target.ego.yawSigmaRadPerSec)
            assertEquals(i / 10.0, target.ego.confidence01)
            assertEquals(i, target.ego.inliers)
            assertEquals(i % 2 == 1, target.ego.hasFoe)
            assertEquals(i / 20.0, target.usability01)
            assertEquals(VisualReason.NONE, target.reason)
        }
        assertEquals(2L, ring.dropped)
        assertFalse(ring.poll(target))
    }

    @Test
    fun throughTimeLeavesFutureQueuedAndNonIncreasingPublishDoesNotReplaceIt() {
        val ring = VisualEgoRing(2)
        val target = VisualMotionFrame()
        assertTrue(ring.publish(10L, VisualEgo(), 0.4, VisualReason.BLUR))
        assertTrue(ring.publish(20L, VisualEgo(), 0.8, VisualReason.NONE))
        assertFalse(ring.publish(20L, VisualEgo(), 0.0, VisualReason.UNKNOWN))
        assertFalse(ring.publish(19L, VisualEgo(), 0.0, VisualReason.UNKNOWN))
        assertFalse(ring.poll(target, 9L))
        assertTrue(ring.poll(target, 10L))
        assertEquals(10L, target.tNs)
        assertEquals(VisualReason.BLUR, target.reason)
        assertFalse(ring.poll(target, 19L))
        assertEquals(10L, target.tNs)
        assertTrue(ring.poll(target, 20L))
        assertEquals(20L, target.tNs)
        assertEquals(0.8, target.usability01)
        assertEquals(VisualReason.NONE, target.reason)
        assertEquals(0L, ring.dropped)
    }

    @Test
    fun disabledConsumerDoesNotJoinReadInputOrChangeGovernor() {
        val tap = TestTap()
        val input = TestInput()
        val consumer = VisualMotionConsumer(tap, input, enabled = false)
        assertFalse(consumer.start())
        assertFalse(consumer.wantsFrame(START_NS))
        consumer.setInterval(INTERVAL_NS)
        consumer.onFrame(FrameSample(START_NS, 240, 180, 1, ByteArray(43_200)))
        consumer.stop()
        assertEquals(0, tap.addCalls)
        assertEquals(0, tap.setCalls)
        assertTrue(tap.intervals.isEmpty())
        assertFalse(tap.mux.hasConsumers)
        assertEquals(0, input.availabilityChecks)
        assertEquals(0, input.reads)
        assertEquals(0L, consumer.framesProcessed)
        assertNull(consumer.drain(START_NS))
    }

    @Test
    fun guestAttachmentKeepsLedgerAndStopRemovesOnlyVisualConsumer() {
        val tap = TestTap()
        var ledgerFrames = 0
        tap.mux.setConsumer { ledgerFrames++ }
        val consumer = VisualMotionConsumer(tap, TestInput(), intervalNs = INTERVAL_NS)
        assertTrue(consumer.start())
        assertTrue(consumer.start())
        assertEquals(1, tap.addCalls)
        assertEquals(0, tap.setCalls)
        val world = world()
        tap.mux.onFrame(sample(world, START, START_NS))
        assertEquals(1, ledgerFrames)
        assertEquals(1L, consumer.framesProcessed)
        consumer.stop()
        assertFalse(consumer.wantsFrame(START_NS + INTERVAL_NS))
        tap.mux.onFrame(sample(world, START, START_NS + INTERVAL_NS))
        assertEquals(2, ledgerFrames)
        assertEquals(1L, consumer.framesProcessed)
        assertTrue(tap.mux.hasConsumers)
        assertEquals(listOf(INTERVAL_NS), tap.intervals)
        assertEquals(0L, tap.mux.consumerFailures)
        tap.mux.setConsumer(null)
        assertFalse(tap.mux.hasConsumers)
    }

    @Test
    fun failedRegistrationDoesNotBecomeRunningOrChangeGovernor() {
        val tap = TestTap()
        repeat(3) { i -> assertTrue(tap.mux.addConsumer(FrameConsumer { check(i in 0..2) })) }
        val input = TestInput()
        val consumer = VisualMotionConsumer(tap, input)
        assertFalse(consumer.start())
        assertFalse(consumer.wantsFrame(START_NS))
        assertTrue(tap.intervals.isEmpty())
        assertEquals(0, input.availabilityChecks)
    }

    @Test
    fun interestRespectsIntervalPauseAndInputAvailabilityBeforeReading() {
        val input = TestInput()
        val tap = TestTap()
        val consumer = VisualMotionConsumer(tap, input, intervalNs = INTERVAL_NS)
        assertFalse(consumer.wantsFrame(START_NS))
        assertTrue(consumer.start())
        input.available = false
        assertFalse(consumer.wantsFrame(START_NS))
        consumer.onFrame(FrameSample(START_NS, 240, 180, 1, ByteArray(43_200)))
        assertEquals(0L, consumer.framesProcessed)
        assertEquals(0, input.reads)
        assertNull(consumer.drain(START_NS))
        input.available = true
        consumer.onFrame(sample(world(), START, START_NS))
        assertFalse(consumer.wantsFrame(START_NS - 1L))
        assertFalse(consumer.wantsFrame(START_NS + INTERVAL_NS - 1L))
        assertTrue(consumer.wantsFrame(START_NS + INTERVAL_NS))
        consumer.setInterval(0L)
        val checks = input.availabilityChecks
        assertFalse(consumer.wantsFrame(START_NS + 10 * INTERVAL_NS))
        assertEquals(checks, input.availabilityChecks)
        consumer.setInterval(INTERVAL_NS)
        assertTrue(consumer.wantsFrame(START_NS + INTERVAL_NS))
        assertEquals(listOf(INTERVAL_NS, 0L, INTERVAL_NS), tap.intervals)
        consumer.stop()
    }

    @Test
    fun untrustedMalformedFlatBlankAndMissingPoseNeverReuseConfidence() {
        val input = TestInput()
        val consumer = VisualMotionConsumer(TestTap(), input, intervalNs = INTERVAL_NS)
        assertTrue(consumer.start())
        val world = world()
        val plane = world.render(START, START_NS).luma
        consumer.onFrame(sample(world, START, START_NS))
        consumer.onFrame(sample(world, START, START_NS + INTERVAL_NS))
        assertTrue(assertNotNull(consumer.drain(START_NS + INTERVAL_NS)).ego.confidence01 >= 0.5)
        var tNs = START_NS + 2 * INTERVAL_NS
        val malformed = listOf(
            FrameSample(tNs, 240, 180, 1, plane),
            FrameSample(tNs + INTERVAL_NS, 240, 180, 0, plane, visualClockAligned = true),
            FrameSample(tNs + 2 * INTERVAL_NS, 239, 180, 1, plane, visualClockAligned = true),
            FrameSample(tNs + 3 * INTERVAL_NS, 240, 180, 1, ByteArray(1), visualClockAligned = true),
        )
        for (frame in malformed) assertRejected(consumer, frame, VisualReason.UNKNOWN)
        tNs += 4 * INTERVAL_NS
        input.mode = PoseMode.FLAT
        assertRejected(consumer, aligned(tNs, plane), VisualReason.FLAT)
        input.mode = PoseMode.HELD_UP
        tNs += INTERVAL_NS
        assertRejected(consumer, aligned(tNs, ByteArray(43_200) { 120 }), VisualReason.BLANK_WALL)
        input.readable = false
        tNs += INTERVAL_NS
        assertRejected(consumer, aligned(tNs, plane), VisualReason.UNKNOWN)
        input.readable = true
        input.qw = 2.0
        tNs += INTERVAL_NS
        assertRejected(consumer, aligned(tNs, plane), VisualReason.UNKNOWN)
        input.qw = 1.0
        input.angularRate = Double.NaN
        tNs += INTERVAL_NS
        assertRejected(consumer, aligned(tNs, plane), VisualReason.UNKNOWN)
        input.angularRate = 0.0
        tNs += INTERVAL_NS
        consumer.onFrame(sample(world, START, tNs))
        assertEquals(0.0, assertNotNull(consumer.drain(tNs)).ego.confidence01, "recovery must seed a new reference")
        tNs += INTERVAL_NS
        consumer.onFrame(sample(world, START, tNs))
        assertTrue(assertNotNull(consumer.drain(tNs)).ego.confidence01 >= 0.5)
        consumer.stop()
    }

    @Test
    fun actualStaticAndWalkingSequencesProduceUsableResults() {
        for (distancePerFrame in listOf(0.0, 0.04)) {
            val world = world()
            val tap = TestTap()
            val consumer = VisualMotionConsumer(tap, TestInput(), intervalNs = INTERVAL_NS)
            assertTrue(consumer.start())
            for (i in 0..4) {
                val tNs = START_NS + i * INTERVAL_NS
                tap.mux.onFrame(sample(world, START.copy(y = START.y + i * distancePerFrame), tNs))
                val frame = assertNotNull(consumer.drain(tNs))
                assertEquals(tNs, frame.tNs)
                assertEquals(VisualReason.NONE, frame.reason)
                assertTrue(frame.usability01 >= 0.5)
                if (i == 0) {
                    assertEquals(0.0, frame.ego.confidence01)
                } else {
                    assertTrue(frame.ego.confidence01 >= 0.5, "distance=$distancePerFrame frame=$i confidence=${frame.ego.confidence01}")
                    assertTrue(frame.ego.inliers >= 6)
                    if (distancePerFrame == 0.0) assertTrue(abs(frame.ego.expansionPerSec) < 0.01)
                    else assertTrue(frame.ego.expansionPerSec > 0.0)
                }
            }
            assertEquals(5L, consumer.framesProcessed)
            assertTrue(consumer.hasUsableFrame)
            assertEquals(0L, tap.mux.consumerFailures)
            consumer.stop()
        }
    }

    @Test
    fun deviceToWorldQuaternionUsesRearCameraAxesForYawAndPitch() {
        val world = world()
        for (to in listOf(START.copy(yawRad = 0.015), START.copy(yawRad = -0.015), START.copy(pitchRad = 0.015), START.copy(pitchRad = -0.015))) {
            val input = TestInput()
            val consumer = VisualMotionConsumer(TestTap(), input, intervalNs = INTERVAL_NS)
            val first = sample(world, START, START_NS)
            val second = sample(world, to, START_NS + INTERVAL_NS)
            val flow = VisualFlow()
            assertFalse(flow.onFrame(first.luma, first.tNs, assertNotNull(first.intrinsics)))
            assertTrue(flow.onFrame(second.luma, second.tNs, assertNotNull(second.intrinsics), deltaYawRad = to.yawRad, deltaPitchRad = to.pitchRad))
            val expected = EgoMotionFit().fit(flow.flow, VisualUsability().evaluate(second.luma, 240, 180, PoseMode.HELD_UP, 0.0))
            assertTrue(expected.confidence01 >= 0.5)
            assertTrue(consumer.start())
            input.orientation(START)
            consumer.onFrame(first)
            input.orientation(to)
            consumer.onFrame(second)
            val actual = assertNotNull(consumer.drain(second.tNs)).ego
            assertEquals(expected.expansionPerSec, actual.expansionPerSec, 1e-8)
            assertEquals(expected.yawResidualRadPerSec, actual.yawResidualRadPerSec, 1e-8)
            assertEquals(expected.confidence01, actual.confidence01, 1e-8)
            assertEquals(expected.inliers, actual.inliers)
            assertTrue(abs(actual.yawResidualRadPerSec) < 0.04, "pure camera rotation must not become residual yaw")
            consumer.stop()
        }
    }

    @Test
    fun drainReturnsNewestPastFrameWithoutConsumingFutureAndExpiresAtTwoSeconds() {
        val ring = VisualEgoRing(3)
        val consumer = VisualMotionConsumer(TestTap(), TestInput(), ring = ring)
        assertNull(consumer.drain(START_NS))
        for (i in 0..2) assertTrue(ring.publish(START_NS + i * INTERVAL_NS, VisualEgo(), 1.0, VisualReason.NONE))
        assertNull(consumer.drain(START_NS - 1L))
        val first = assertNotNull(consumer.drain(START_NS + INTERVAL_NS))
        assertEquals(START_NS + INTERVAL_NS, first.tNs)
        assertEquals(first.tNs, assertNotNull(consumer.drain(START_NS + 2 * INTERVAL_NS - 1L)).tNs)
        val latestNs = START_NS + 2 * INTERVAL_NS
        val latest = assertNotNull(consumer.drain(latestNs))
        assertSame(first, latest)
        assertEquals(latestNs, latest.tNs)
        assertNull(consumer.drain(latestNs - 1L))
        assertNotNull(consumer.drain(latestNs + 2_000_000_000L))
        assertNull(consumer.drain(latestNs + 2_000_000_001L))
        assertEquals(0L, ring.dropped)
    }

    /**
     * Regression (W16-04 ordering): the step gate must already hold this
     * instant's frame when it judges a candidate. Frames reach the engine at
     * frame time here — exactly the wiring `BuiltInPdrMotionSource` performs
     * through `onEgo` — and nothing drains to feed it. Feeding the gate only
     * after a step (the old path) left its ±0.4 s window one step stale, so a
     * phone bobbed up and down with a still camera counted every bounce.
     */
    @Test
    fun frameTimeEgoFeedsTheGateSoStaticBobbingIsRejected() {
        val engine = PdrEngine(visualAssist = true)
        val consumer = VisualMotionConsumer(
            TestTap(),
            TestInput(),
            intervalNs = INTERVAL_NS,
            onEgo = { ego, tNs -> engine.onVisualEgo(ego, tNs) },
        )
        assertTrue(consumer.start())
        engine.start()
        bobForTwelveSeconds(engine, consumer)

        val candidates = engine.stepGate.acceptedStepCount + engine.stepGate.rejectedPhantomCount
        assertTrue(candidates >= 10, "bobbing fixture must produce step candidates, got $candidates")
        val rejectionRate = engine.stepGate.rejectedPhantomCount.toDouble() / candidates
        assertTrue(
            rejectionRate >= 0.95,
            "a still camera must veto bobbing: rejected ${engine.stepGate.rejectedPhantomCount}/$candidates",
        )
        consumer.stop()
        engine.stop()
    }

    /**
     * The opposite failure: the fix must not veto real walking. Same bobbing
     * accelerometer, camera advancing, so expansion clears the motion floor.
     */
    @Test
    fun frameTimeEgoKeepsRealStepsWhenTheCameraAdvances() {
        val engine = PdrEngine(visualAssist = true)
        val consumer = VisualMotionConsumer(
            TestTap(),
            TestInput(),
            intervalNs = INTERVAL_NS,
            onEgo = { ego, tNs -> engine.onVisualEgo(ego, tNs) },
        )
        assertTrue(consumer.start())
        engine.start()
        bobForTwelveSeconds(engine, consumer, metresPerFrame = 0.04)

        assertTrue(
            engine.stepGate.acceptedStepCount > 0,
            "forward camera expansion must keep real steps accepted",
        )
        consumer.stop()
        engine.stop()
    }

    /**
     * Twelve seconds of 2 Hz vertical bobbing at 50 Hz IMU rate, with camera
     * frames delivered at [INTERVAL_NS] through the consumer's frame-time path.
     */
    private fun bobForTwelveSeconds(
        engine: PdrEngine,
        consumer: VisualMotionConsumer,
        metresPerFrame: Double = 0.0,
    ) {
        val world = world()
        val hz = 50
        var nextFrameNs = START_NS
        var frame = 0
        for (i in 0 until hz * 12) {
            val tNs = START_NS + i * (1_000_000_000L / hz)
            while (nextFrameNs <= tNs) {
                consumer.onFrame(sample(world, START.copy(y = START.y + frame * metresPerFrame), nextFrameNs))
                frame++
                nextFrameNs += INTERVAL_NS
            }
            val tSec = i.toDouble() / hz
            val az = 9.81 + 2.2 * sin(2.0 * PI * 2.0 * tSec)
            engine.onAccelerometer(ImuSample(tNs, 0.0, 0.0, az))
        }
    }

    private fun assertRejected(consumer: VisualMotionConsumer, sample: FrameSample, reason: VisualReason) {
        val count = consumer.framesProcessed
        consumer.onFrame(sample)
        val frame = assertNotNull(consumer.drain(sample.tNs))
        assertEquals(count + 1, consumer.framesProcessed)
        assertEquals(sample.tNs, frame.tNs)
        assertEquals(reason, frame.reason)
        assertEquals(0.0, frame.ego.confidence01)
        assertEquals(0, frame.ego.inliers)
        assertFalse(frame.ego.hasFoe)
    }

    private fun world() = SimWorld(SimEnvironment.CORRIDOR, seed = 1_234_567L)

    private fun sample(world: SimWorld, pose: SimPose, tNs: Long): FrameSample = aligned(
        tNs, world.render(pose, tNs).luma,
        CameraIntrinsics(world.phone.focalLengthPx, 120.0, 90.0, IntrinsicsSource.CHARACTERISTICS),
    )

    private fun aligned(tNs: Long, plane: ByteArray, intrinsics: CameraIntrinsics? = null) =
        FrameSample(tNs, 240, 180, 1, plane, intrinsics = intrinsics, visualClockAligned = true)

    private class TestInput : VisualMotionInput {
        var available = true
        var readable = true
        var availabilityChecks = 0
        var reads = 0
        var mode = PoseMode.HELD_UP
        var angularRate = 0.0
        var qw = 1.0
        var qx = 0.0
        var qy = 0.0
        var qz = 0.0

        fun orientation(pose: SimPose) {
            // World right/up/forward at zero pose are X/Z/Y. Device Z faces away
            // from the rear camera: R(device -> world) = Rz(-yaw) Rx(pi/2 + pitch).
            val halfX = PI / 4.0 + pose.pitchRad / 2.0
            val halfZ = -pose.yawRad / 2.0
            qw = cos(halfZ) * cos(halfX)
            qx = cos(halfZ) * sin(halfX)
            qy = sin(halfZ) * sin(halfX)
            qz = sin(halfZ) * cos(halfX)
        }

        override fun isAvailable(tNs: Long): Boolean { availabilityChecks++; return available }

        override fun readAt(tNs: Long, meanLuma: Double, target: VisualFramePose): Boolean {
            reads++
            target.qw = qw; target.qx = qx; target.qy = qy; target.qz = qz
            target.mode = mode
            target.angularRateRadPerSec = angularRate
            return readable
        }
    }

    private class TestTap : FrameTap {
        val mux = FrameTapMux()
        val intervals = mutableListOf<Long>()
        var addCalls = 0
        var setCalls = 0
        override fun setConsumer(consumer: FrameConsumer?) { setCalls++; mux.setConsumer(consumer) }
        override fun addConsumer(consumer: FrameConsumer): Boolean { addCalls++; return mux.addConsumer(consumer) }
        override fun removeConsumer(consumer: FrameConsumer): Boolean = mux.removeConsumer(consumer)
        override fun setBaseInterval(intervalNs: Long) { intervals += intervalNs }
    }

    companion object {
        private const val START_NS = 1_000_000_000L
        private const val INTERVAL_NS = 125_000_000L
        private val START = SimPose(0.0, 57.0, 1.5, 0.0)
    }
}