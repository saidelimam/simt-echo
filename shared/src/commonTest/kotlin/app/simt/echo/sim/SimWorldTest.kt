package app.simt.echo.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimWorldTest {
    @Test
    fun rendersEveryBoundedEnvironmentAsFrameTapLuma() {
        for (environment in SimEnvironment.entries) {
            val frame = SimWorld(environment, seed = TEST_SEED).render(CAMERA_POSE, FRAME_TIME_NS)

            assertEquals(SimWorld.FULL_WIDTH, frame.width)
            assertEquals(SimWorld.FULL_HEIGHT, frame.height)
            assertEquals(SimWorld.DECIMATION, frame.decimation)
            assertEquals(SimWorld.LUMA_WIDTH * SimWorld.LUMA_HEIGHT, frame.luma.size)
            assertTrue(frame.luma.map { it.toInt() and 0xff }.toSet().size > MIN_TEXTURE_VALUES, "$environment should be textured")
        }
    }

    @Test
    fun identicalSeedPoseAndTimeProduceIdenticalFrameBytes() {
        val first = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED).render(CAMERA_POSE, FRAME_TIME_NS)
        val second = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED).render(CAMERA_POSE, FRAME_TIME_NS)
        val otherSeed = SimWorld(SimEnvironment.CORRIDOR, seed = TEST_SEED + 1).render(CAMERA_POSE, FRAME_TIME_NS)

        assertEquals(first, second)
        assertNotEquals(first.luma.toList(), otherSeed.luma.toList())
    }

    @Test
    fun projectionOracleRecoversKnownForwardTranslationAndYaw() {
        val world = SimWorld(SimEnvironment.CORRIDOR, SimPhone(horizontalFovDeg = 62.0))
        val point = SimPoint3(x = 1.0, y = 12.0, z = CAMERA_HEIGHT_M)
        val start = SimPose(x = 0.0, y = 0.0, z = CAMERA_HEIGHT_M, yawRad = 0.0)
        val moved = start.copy(y = KNOWN_TRANSLATION_M)
        val firstPixel = assertNotNull(world.projectWorldPoint(point, start))
        val translatedPixel = assertNotNull(world.projectWorldPoint(point, moved))
        val observedDepthAfter = world.phone.focalLengthPx * point.x /
            (translatedPixel.x - SimWorld.LUMA_WIDTH / 2.0)
        val recoveredTranslation = point.y - observedDepthAfter
        assertEquals(KNOWN_TRANSLATION_M, recoveredTranslation, PROJECTION_TOLERANCE_M)

        val centerPoint = SimPoint3(x = 0.0, y = 10.0, z = CAMERA_HEIGHT_M)
        val rotated = world.oracleFlow(centerPoint, start, start.copy(yawRad = KNOWN_YAW_RAD))
        assertNotNull(rotated)
        val recoveredYaw = atan(-rotated.x / world.phone.focalLengthPx)
        assertEquals(KNOWN_YAW_RAD, recoveredYaw, PROJECTION_TOLERANCE_RAD)
        assertTrue(translatedPixel.x > firstPixel.x)
    }

    @Test
    fun movingOccluderChangesTheRenderedView() {
        val world = SimWorld(SimEnvironment.JUNCTION, seed = TEST_SEED)
        val early = world.render(CAMERA_POSE, FRAME_TIME_NS)
        val later = world.render(CAMERA_POSE, FRAME_TIME_NS + MOVING_OCCLUDER_INTERVAL_NS)

        assertNotEquals(early.luma.toList(), later.luma.toList())
    }

    @Test
    fun exposureAndFovChangeLumaAndProjection() {
        val dim = SimWorld(SimEnvironment.CORRIDOR, SimPhone(exposure = 0.55), TEST_SEED)
            .render(CAMERA_POSE, FRAME_TIME_NS)
        val bright = SimWorld(SimEnvironment.CORRIDOR, SimPhone(exposure = 1.45), TEST_SEED)
            .render(CAMERA_POSE, FRAME_TIME_NS)
        assertTrue(meanLuma(bright.luma) > meanLuma(dim.luma))

        val feature = SimPoint3(x = 1.0, y = 10.0, z = CAMERA_HEIGHT_M)
        val wide = SimWorld(SimEnvironment.CORRIDOR, SimPhone(horizontalFovDeg = 90.0))
            .projectWorldPoint(feature, CAMERA_POSE)
        val narrow = SimWorld(SimEnvironment.CORRIDOR, SimPhone(horizontalFovDeg = 50.0))
            .projectWorldPoint(feature, CAMERA_POSE)
        assertNotNull(wide)
        assertNotNull(narrow)
        assertTrue(abs(narrow.x - SimWorld.LUMA_WIDTH / 2.0) > abs(wide.x - SimWorld.LUMA_WIDTH / 2.0))
    }

    @Test
    fun fastRotationBlurSoftensFrameEdges() {
        val phone = SimPhone(exposureSeconds = 0.08)
        val world = SimWorld(SimEnvironment.CORRIDOR, phone, TEST_SEED)
        val sharp = world.render(CAMERA_POSE, FRAME_TIME_NS)
        val blurred = world.render(CAMERA_POSE, FRAME_TIME_NS, angularVelocityRadPerSec = FAST_ROTATION_RAD_PER_SEC)

        assertTrue(edgeEnergy(blurred.luma) < edgeEnergy(sharp.luma), "motion blur should reduce edge contrast")
    }

    @Test
    fun seededGaitAndSensorsRepeatIncludingFrameTapSamples() {
        val route = SimWalk.straightRoute(STAIRWELL_TEST_ROUTE_M)
        val first = SimWalk(SimEnvironment.STAIRWELL_LANDING, TEST_SEED)
            .samples(route)
            .toList()
        val second = SimWalk(SimEnvironment.STAIRWELL_LANDING, TEST_SEED)
            .samples(route)
            .toList()
        val frames = SimWalk(SimEnvironment.STAIRWELL_LANDING, TEST_SEED)
            .samples(
                SimWalk.straightRoute(SHORT_ROUTE_M),
                renderFrames = true,
                frameIntervalNs = TEST_FRAME_INTERVAL_NS,
            )
            .toList()

        assertEquals(first, second)
        assertTrue(first.any { it.motion.isStep })
        val totalSteps = assertNotNull(first.maxOfOrNull { it.motion.stepCount })
        assertTrue(totalSteps in MIN_EXPECTED_STEPS..MAX_EXPECTED_STEPS)
        assertTrue(first.any { it.motion.headingQuality < 0.5 }, "steel anomaly should lower heading quality")
        assertTrue(first.any { it.motion.magMag != null && it.motion.magVert != null })
        assertTrue(frames.any { it.frame != null }, "walk should emit frames on the requested cadence")
    }

    @Test
    fun projectionRejectsFeaturesBehindCamera() {
        val world = SimWorld(SimEnvironment.CORRIDOR)
        assertNull(world.projectWorldPoint(SimPoint3(0.0, -1.0, CAMERA_HEIGHT_M), CAMERA_POSE))
    }

    @Test
    fun raycastOracleMatchesProjectionAndStairwellFloorHeight() {
        val world = SimWorld(SimEnvironment.STAIRWELL_LANDING, seed = TEST_SEED)
        val pose = SimPose(0.0, 22.0, world.floorHeightM(22.0) + CAMERA_HEIGHT_M, 0.0, -0.4)
        val pixel = SimPixel(SimWorld.LUMA_WIDTH / 2.0 + 0.5, SimWorld.LUMA_HEIGHT - 10.5)
        val hit = assertNotNull(world.visibleWorldPoint(pixel, pose, FRAME_TIME_NS))
        val projected = assertNotNull(world.projectWorldPoint(hit, pose))

        assertEquals(world.floorHeightM(hit.y), hit.z, PROJECTION_TOLERANCE_M)
        assertEquals(pixel.x, projected.x, PROJECTION_TOLERANCE_M)
        assertEquals(pixel.y, projected.y, PROJECTION_TOLERANCE_M)
    }

    private fun meanLuma(bytes: ByteArray): Double =
        bytes.sumOf { it.toInt() and 0xff }.toDouble() / bytes.size

    private fun edgeEnergy(bytes: ByteArray): Double {
        var energy = 0L
        for (y in 0 until SimWorld.LUMA_HEIGHT) {
            for (x in 0 until SimWorld.LUMA_WIDTH - 1) {
                val left = bytes[y * SimWorld.LUMA_WIDTH + x].toInt() and 0xff
                val right = bytes[y * SimWorld.LUMA_WIDTH + x + 1].toInt() and 0xff
                energy += abs(left - right)
            }
        }
        return energy.toDouble() / (SimWorld.LUMA_HEIGHT * (SimWorld.LUMA_WIDTH - 1))
    }

    companion object {
        private const val TEST_SEED = 1_234_567L
        private const val FRAME_TIME_NS = 1_000_000_000L
        private const val MOVING_OCCLUDER_INTERVAL_NS = 5_000_000_000L
        private const val TEST_FRAME_INTERVAL_NS = 1_000_000_000L
        private const val SHORT_ROUTE_M = 5.0
        private const val STAIRWELL_TEST_ROUTE_M = 25.0
        private const val CAMERA_HEIGHT_M = 1.55
        private const val MIN_TEXTURE_VALUES = 20
        private const val KNOWN_TRANSLATION_M = 1.5
        private const val KNOWN_YAW_RAD = 7.0 * PI / 180.0
        private const val PROJECTION_TOLERANCE_M = 1e-9
        private const val PROJECTION_TOLERANCE_RAD = 1e-9
        private const val FAST_ROTATION_RAD_PER_SEC = 4.0
        private const val MIN_EXPECTED_STEPS = 5
        private const val MAX_EXPECTED_STEPS = 60

        private val CAMERA_POSE = SimPose(0.0, 0.0, CAMERA_HEIGHT_M, 0.0)
    }
}
