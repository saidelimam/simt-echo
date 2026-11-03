package app.simt.echo.capture.visual

import app.simt.echo.capture.CameraIntrinsics
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPhone
import app.simt.echo.sim.SimPixel
import app.simt.echo.sim.SimPoint3
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWorld
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VisualFlowTest {
    @Test
    fun blankTargetIsUnobservableAndRecoveryRequiresTwoTexturedFrames() {
        val phone = SimPhone()
        val world = SimWorld(SimEnvironment.CORRIDOR, phone, 19L)
        val image = world.render(SimPose(0.0, 57.0, 1.5, 0.0), 1_000_000_000L).luma
        val intrinsics = CameraIntrinsics(phone.focalLengthPx, 120.0, 90.0, IntrinsicsSource.DEFAULT)
        val kernel = VisualFlow()
        assertFalse(kernel.onFrame(image, 1_000_000_000L, intrinsics))
        assertFalse(kernel.onFrame(ByteArray(image.size) { 120 }, 1_100_000_000L, intrinsics))
        assertEquals(0, kernel.flow.count)
        assertFalse(kernel.onFrame(image, 1_200_000_000L, intrinsics))
        assertEquals(0, kernel.flow.count)
        assertTrue(kernel.onFrame(image, 1_300_000_000L, intrinsics))
        assertTrue(kernel.flow.count >= 6)
    }

    @Test
    fun forwardTranslationMatchesOracleAcrossSuccessiveReferenceFrames() {
        val world = world()
        val intrinsics = intrinsics(world.phone)
        val tracker = VisualFlow()
        assertFalse(tracker.onFrame(world.render(START, START_NS).luma, START_NS, intrinsics))
        assertEquals(0, tracker.flow.count)

        for (step in 1..4) {
            val from = START.copy(y = START.y + (step - 1) * FORWARD_M)
            val to = START.copy(y = START.y + step * FORWARD_M)
            val fromNs = START_NS + (step - 1) * INTERVAL_NS
            val toNs = fromNs + INTERVAL_NS
            assertTrue(tracker.onFrame(world.render(to, toNs).luma, toNs, intrinsics))
            assertMatchesOracle(tracker.flow, world, from, to, fromNs, toNs, intrinsics)
        }
    }

    @Test
    fun pureYawInBothDirectionsCancelsToSubpixelResidual() {
        for (yaw in listOf(-ROTATION_RAD, ROTATION_RAD)) {
            assertPair(to = START.copy(yawRad = yaw), rotationOnly = true)
        }
    }

    @Test
    fun purePitchInBothDirectionsCancelsToSubpixelResidual() {
        for (pitch in listOf(-ROTATION_RAD, ROTATION_RAD)) {
            assertPair(to = START.copy(pitchRad = pitch), rotationOnly = true)
        }
    }

    @Test
    fun yawAndForwardTranslationRetainOnlyTranslationalFlow() {
        for (yaw in listOf(-ROTATION_RAD, ROTATION_RAD)) {
            assertPair(to = START.copy(y = START.y + FORWARD_M, yawRad = yaw))
        }
    }

    @Test
    fun shutterBlurStillTracksSmallForwardMotionAndYaw() {
        val phone = PHONE.copy(exposureSeconds = 0.12)
        val world = world(phone)
        val angularVelocity = 0.12
        assertPair(
            to = START.copy(y = START.y + FORWARD_M, yawRad = angularVelocity * INTERVAL_SECONDS),
            world = world,
            angularVelocity = angularVelocity,
        )
    }

    @Test
    fun exposureStepDoesNotBecomeApparentTranslation() {
        val world = world()
        assertPair(
            to = START.copy(y = START.y + FORWARD_M),
            world = world,
            secondWorld = world(PHONE.copy(exposure = 1.15)),
        )
    }

    @Test
    fun acceptedRotationDomainCancelsToTheResidualBound() {
        for (angle in listOf(-0.15, -0.10, -0.05, 0.05, 0.10, 0.15)) {
            assertPair(to = START.copy(yawRad = angle), rotationOnly = true)
            assertPair(to = START.copy(pitchRad = angle), rotationOnly = true)
        }
        assertPair(to = START.copy(yawRad = 0.10, pitchRad = -0.08), rotationOnly = true)
        assertPair(to = START.copy(yawRad = -0.12, pitchRad = 0.06), rotationOnly = true)
    }

    @Test
    fun wrongFocalLengthClearsOrKeepsPureRotationInsideTheBound() {
        val scene = world()
        for (scale in listOf(0.85, 1.15)) {
            for (yaw in listOf(-0.10, 0.10)) {
                val camera = intrinsics(scene.phone, scale)
                val to = START.copy(yawRad = yaw)
                val tracker = VisualFlow(width = SimWorld.LUMA_WIDTH, height = SimWorld.LUMA_HEIGHT)
                assertFalse(tracker.onFrame(scene.render(START, START_NS).luma, START_NS, camera))
                val produced = tracker.onFrame(
                    scene.render(to, START_NS + INTERVAL_NS).luma,
                    START_NS + INTERVAL_NS,
                    camera,
                    deltaYawRad = yaw,
                )
                if (!produced) {
                    assertEquals(0, tracker.flow.count)
                } else {
                    // A 15% focal error at 0.10 rad moves the rotational prediction by about
                    // 1–2 px. Matched intrinsics stay at 0.15 px RMS; this only bounds the miss.
                    assertMatchesOracle(
                        tracker.flow, scene, START, to, START_NS, START_NS + INTERVAL_NS, camera,
                        rotationOnly = true, minBlocks = 1, maxErrorPx = 2.5, maxRmsPx = 2.0,
                    )
                }
            }
        }
    }

    @Test
    fun recordAndNavigateSpeedsKeepCorrectBlocksOnNearAndFarWalls() {
        val scene = world()
        val camera = intrinsics(scene.phone)
        val cases = listOf(
            54.0 to (0.125 to 0.1125),
            54.0 to (0.125 to 0.1375),
            58.0 to (0.125 to 0.1125),
            58.0 to (0.125 to 0.1375),
            54.0 to (0.5 to 0.45),
            54.0 to (0.5 to 0.55),
            57.0 to (0.5 to 0.45),
            57.0 to (0.5 to 0.50),
            57.0 to (0.5 to 0.55),
        )
        val failures = mutableListOf<String>()
        for ((y, motion) in cases) {
            val (intervalSeconds, distance) = motion
            val from = START.copy(y = y)
            val to = from.copy(y = y + distance, yawRad = 0.02)
            val intervalNs = (intervalSeconds * 1_000_000_000.0).toLong()
            val tracker = VisualFlow(width = SimWorld.LUMA_WIDTH, height = SimWorld.LUMA_HEIGHT)
            assertFalse(tracker.onFrame(scene.render(from, START_NS).luma, START_NS, camera))
            val produced = tracker.onFrame(
                scene.render(to, START_NS + intervalNs).luma,
                START_NS + intervalNs,
                camera,
                deltaYawRad = 0.02,
            )
            val label = "y=$y distance=$distance dt=${intervalSeconds}s (count=${tracker.flow.count})"
            if (!produced) {
                failures += "$label: produced no blocks"
                continue
            }
            for (issue in oracleFailures(tracker.flow, scene, from, to, START_NS, START_NS + intervalNs, camera)) {
                failures += "$label: $issue"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun approximateFocalLengthsStillTrackTranslationDuringSmallYaw() {
        val world = world()
        for (scale in listOf(0.85, 1.15)) {
            assertPair(
                to = START.copy(y = START.y + FORWARD_M, yawRad = 0.003),
                world = world,
                intrinsics = intrinsics(world.phone, scale),
            )
        }
    }

    @Test
    fun blankHalfIsExcludedAndValidBlocksAreCompacted() {
        val world = world()
        val intrinsics = intrinsics(world.phone)
        val to = START.copy(y = START.y + FORWARD_M)
        val first = world.render(START, START_NS).luma
        val second = world.render(to, START_NS + INTERVAL_NS).luma
        for (y in 0 until SimWorld.LUMA_HEIGHT) {
            for (x in 0 until SimWorld.LUMA_WIDTH / 2) {
                first[y * SimWorld.LUMA_WIDTH + x] = 128.toByte()
                second[y * SimWorld.LUMA_WIDTH + x] = 128.toByte()
            }
        }
        val tracker = VisualFlow()
        assertFalse(tracker.onFrame(first, START_NS, intrinsics))
        assertTrue(tracker.onFrame(second, START_NS + INTERVAL_NS, intrinsics))
        assertEquals(6, tracker.flow.count)
        for (i in 0 until tracker.flow.count) {
            val fullX = intrinsics.cx + tracker.flow.x[i] * intrinsics.fPx
            assertTrue(fullX - BLOCK_SIZE_BASE >= SimWorld.LUMA_WIDTH / 2.0, "block $i overlaps the blank half")
        }
        assertMatchesOracle(tracker.flow, world, START, to, START_NS, START_NS + INTERVAL_NS, intrinsics)
    }

    @Test
    fun uniformFramesProduceNoFlowAndCannotSupplyATexturedReference() {
        val world = world()
        val intrinsics = intrinsics(world.phone)
        val tracker = VisualFlow()
        val blank = ByteArray(SimWorld.LUMA_WIDTH * SimWorld.LUMA_HEIGHT) { 128.toByte() }
        assertFalse(tracker.onFrame(blank, START_NS, intrinsics))
        assertFalse(tracker.onFrame(blank, START_NS + INTERVAL_NS, intrinsics))
        assertEquals(0, tracker.flow.count)

        val reseedNs = START_NS + 2 * INTERVAL_NS
        assertFalse(tracker.onFrame(world.render(START, reseedNs).luma, reseedNs, intrinsics))
        assertEquals(0, tracker.flow.count)
        val to = START.copy(y = START.y + FORWARD_M)
        assertTrue(tracker.onFrame(world.render(to, reseedNs + INTERVAL_NS).luma, reseedNs + INTERVAL_NS, intrinsics))
        assertMatchesOracle(tracker.flow, world, START, to, reseedNs, reseedNs + INTERVAL_NS, intrinsics)
    }

    @Test
    fun untrustedClockClearsOutputAndResetsReferenceBeforeResuming() {
        val world = world()
        val intrinsics = intrinsics(world.phone)
        val tracker = VisualFlow()
        val moved = START.copy(y = START.y + FORWARD_M)
        assertFalse(tracker.onFrame(world.render(START, START_NS).luma, START_NS, intrinsics))
        assertTrue(tracker.onFrame(world.render(moved, START_NS + INTERVAL_NS).luma, START_NS + INTERVAL_NS, intrinsics))
        assertTrue(tracker.flow.count >= MIN_BLOCKS)

        val untrustedNs = START_NS + 2 * INTERVAL_NS
        assertFalse(tracker.onFrame(world.render(moved, untrustedNs).luma, untrustedNs, intrinsics, clockAligned = false))
        assertEquals(0, tracker.flow.count)
        val reseedNs = untrustedNs + INTERVAL_NS
        val reseed = START.copy(y = START.y + 0.2)
        assertFalse(tracker.onFrame(world.render(reseed, reseedNs).luma, reseedNs, intrinsics))
        assertEquals(0, tracker.flow.count)
        val to = reseed.copy(y = reseed.y + FORWARD_M)
        assertTrue(tracker.onFrame(world.render(to, reseedNs + INTERVAL_NS).luma, reseedNs + INTERVAL_NS, intrinsics))
        assertMatchesOracle(tracker.flow, world, reseed, to, reseedNs, reseedNs + INTERVAL_NS, intrinsics)
    }

    private fun assertPair(
        to: SimPose,
        world: SimWorld = world(),
        secondWorld: SimWorld = world,
        intrinsics: CameraIntrinsics = intrinsics(world.phone),
        angularVelocity: Double = 0.0,
        rotationOnly: Boolean = false,
    ) {
        val tracker = VisualFlow(width = SimWorld.LUMA_WIDTH, height = SimWorld.LUMA_HEIGHT)
        val first = world.render(START, START_NS, angularVelocity)
        val second = secondWorld.render(to, START_NS + INTERVAL_NS, angularVelocity)
        assertFalse(tracker.onFrame(first.luma, START_NS, intrinsics))
        assertEquals(0, tracker.flow.count)
        assertTrue(
            tracker.onFrame(
                second.luma,
                START_NS + INTERVAL_NS,
                intrinsics,
                deltaYawRad = to.yawRad - START.yawRad,
                deltaPitchRad = to.pitchRad - START.pitchRad,
            ),
        )
        assertMatchesOracle(tracker.flow, world, START, to, START_NS, START_NS + INTERVAL_NS, intrinsics, rotationOnly)
    }

    private fun assertMatchesOracle(
        flow: BlockFlow,
        world: SimWorld,
        from: SimPose,
        to: SimPose,
        fromNs: Long,
        toNs: Long,
        intrinsics: CameraIntrinsics,
        rotationOnly: Boolean = false,
        minBlocks: Int = MIN_BLOCKS,
        maxErrorPx: Double = FLOW_ERROR_BASE_PX,
        maxRmsPx: Double = ROTATION_RMS_BASE_PX,
    ) {
        val issues = oracleFailures(flow, world, from, to, fromNs, toNs, intrinsics, rotationOnly, minBlocks, maxErrorPx, maxRmsPx)
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    private fun oracleFailures(
        flow: BlockFlow,
        world: SimWorld,
        from: SimPose,
        to: SimPose,
        fromNs: Long,
        toNs: Long,
        intrinsics: CameraIntrinsics,
        rotationOnly: Boolean = false,
        minBlocks: Int = MIN_BLOCKS,
        maxErrorPx: Double = FLOW_ERROR_BASE_PX,
        maxRmsPx: Double = ROTATION_RMS_BASE_PX,
    ): List<String> {
        val issues = mutableListOf<String>()
        if (flow.count !in minBlocks..BLOCK_CAPACITY) {
            issues += "need at least $minBlocks valid blocks, got ${flow.count}"
        }
        for (array in listOf(flow.x, flow.y, flow.dx, flow.dy, flow.texture, flow.quality)) {
            assertEquals(BLOCK_CAPACITY, array.size)
        }
        assertEquals((toNs - fromNs) / 1e9, flow.deltaSeconds, 1e-12)
        val fBase = intrinsics.fPx / 2.0
        assertEquals(fBase, flow.fPx, 1e-12)
        val rotated = from.copy(yawRad = to.yawRad, pitchRad = to.pitchRad)
        var residualSquared = 0.0
        var signalSquared = 0.0
        for (i in 0 until flow.count) {
            assertTrue(flow.x[i].isFinite() && flow.y[i].isFinite(), "non-finite block center $i")
            assertTrue(flow.dx[i].isFinite() && flow.dy[i].isFinite(), "non-finite flow $i")
            assertTrue(flow.texture[i].isFinite() && flow.texture[i] > 0.0, "untextured block $i")
            assertTrue(flow.quality[i].isFinite() && flow.quality[i] in 0.0..1.0, "invalid quality $i")
            val pixel = SimPixel(intrinsics.cx + flow.x[i] * intrinsics.fPx, intrinsics.cy + flow.y[i] * intrinsics.fPx)
            assertTrue(pixel.x in BLOCK_SIZE_BASE.toDouble()..(SimWorld.LUMA_WIDTH - BLOCK_SIZE_BASE).toDouble())
            assertTrue(pixel.y in BLOCK_SIZE_BASE.toDouble()..(SimWorld.LUMA_HEIGHT - BLOCK_SIZE_BASE).toDouble())
            val point = assertNotNull(world.visibleWorldPoint(pixel, from, fromNs))
            val projected = assertNotNull(world.projectWorldPoint(point, to))
            val visibleAfter = assertNotNull(world.visibleWorldPoint(projected, to, toNs))
            assertSameStaticPoint(point, visibleAfter, i)
            val total = assertNotNull(world.oracleFlow(point, from, to))
            val rotation = assertNotNull(world.oracleFlow(point, from, rotated))
            val expectedDx = (total.x - rotation.x) / intrinsics.fPx
            val expectedDy = (total.y - rotation.y) / intrinsics.fPx
            val errorX = (flow.dx[i] - expectedDx) * fBase
            val errorY = (flow.dy[i] - expectedDy) * fBase
            val errorPx = sqrt(errorX * errorX + errorY * errorY)
            if (errorPx > maxErrorPx) {
                issues += "block $i error $errorPx base px at $pixel; actual=(${flow.dx[i] * fBase},${flow.dy[i] * fBase}), expected=(${expectedDx * fBase},${expectedDy * fBase})"
            }
            residualSquared += (flow.dx[i] * flow.dx[i] + flow.dy[i] * flow.dy[i]) * fBase * fBase
            signalSquared += (total.x * total.x + total.y * total.y) / 4.0
        }
        if (flow.count > 0 && sqrt(signalSquared / flow.count) < 0.3) {
            issues += "fixture motion must be measurably nonzero"
        }
        if (rotationOnly && flow.count > 0) {
            val rms = sqrt(residualSquared / flow.count)
            if (rms > maxRmsPx) {
                issues += "pure rotation residual RMS $rms base px"
            }
        }
        return issues
    }

    private fun assertSameStaticPoint(before: SimPoint3, after: SimPoint3, block: Int) {
        val message = "block $block oracle ray hits a moving or occluded surface, not a static point"
        assertEquals(before.x, after.x, 1e-7, message)
        assertEquals(before.y, after.y, 1e-7, message)
        assertEquals(before.z, after.z, 1e-7, message)
    }

    private fun world(phone: SimPhone = PHONE): SimWorld = SimWorld(SimEnvironment.CORRIDOR, phone, TEST_SEED)

    private fun intrinsics(phone: SimPhone, scale: Double = 1.0): CameraIntrinsics = CameraIntrinsics(
        fPx = phone.focalLengthPx * scale,
        cx = SimWorld.LUMA_WIDTH / 2.0,
        cy = SimWorld.LUMA_HEIGHT / 2.0,
        source = if (scale == 1.0) IntrinsicsSource.CHARACTERISTICS else IntrinsicsSource.DEFAULT,
    )

    companion object {
        private const val TEST_SEED = 1_234_567L
        private const val START_NS = 1_000_000_000L
        private const val INTERVAL_NS = 125_000_000L
        private const val INTERVAL_SECONDS = 0.125
        private const val FORWARD_M = 0.04
        private const val ROTATION_RAD = 0.01
        private const val BLOCK_SIZE_BASE = 16
        private const val BLOCK_CAPACITY = 12
        private const val MIN_BLOCKS = 6
        private const val FLOW_ERROR_BASE_PX = 0.2
        private const val ROTATION_RMS_BASE_PX = 0.15
        private val PHONE = SimPhone()

        // The end wall is three metres ahead; the moving billboard is behind it.
        private val START = SimPose(x = 0.0, y = 57.0, z = 1.5, yawRad = 0.0)
    }
}
