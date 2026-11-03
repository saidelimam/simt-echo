package app.simt.echo.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimHandsetTest {
    @Test
    fun quietGyroIntegratesBackToTheRenderedCamera() {
        val phone = SimPhone(holdYawOffsetRad = HOLD_YAW, holdPitchRad = HOLD_PITCH)
        val samples = SimWalk(
            SimEnvironment.CORRIDOR,
            SEED,
            phone,
            yawWobbleRad = 0.04,
            pitchWobbleRad = 0.03,
            sensorNoise = false,
        ).samples(SimWalk.straightRoute(ROUTE_M)).toList()

        var yaw = samples.first().handset.cameraYawRad
        var pitch = samples.first().handset.cameraPitchRad
        for (index in 1 until samples.size) {
            val handset = samples[index].handset
            yaw = wrap(yaw + handset.measuredYawRateRadPerSec * SAMPLE_S)
            pitch = wrap(pitch + handset.measuredPitchRateRadPerSec * SAMPLE_S)
            assertEquals(handset.cameraYawRad, yaw, 1e-9)
            assertEquals(handset.cameraPitchRad, pitch, 1e-9)
            assertEquals(handset.idealYawRateRadPerSec, handset.measuredYawRateRadPerSec, 1e-12)
            assertTrue(abs(handset.cameraYawRad - handset.travelYawRad) > 0.1)
            assertEquals(handset.travelYawRad, samples[index].motion.yawRad, 1e-12)
            assertEquals(cos(handset.cameraPitchRad), handset.gravityDownG, 1e-12)
            assertEquals(-sin(handset.cameraPitchRad), handset.gravityForwardG, 1e-12)
        }
        assertTrue(samples.any { abs(it.handset.gravityForwardG) > 0.05 })
    }

    @Test
    fun wobbleMovesTheCameraAndTheGyroWithoutTouchingLegacyYaw() {
        val still = walk(yawWobbleRad = 0.0)
        val moving = walk(yawWobbleRad = 0.05)
        assertEquals(still.map { it.motion.yawRad }, moving.map { it.motion.yawRad })
        assertNotEquals(still.map { it.handset.cameraYawRad }, moving.map { it.handset.cameraYawRad })
        assertNotEquals(
            still.map { it.handset.measuredYawRateRadPerSec },
            moving.map { it.handset.measuredYawRateRadPerSec },
        )
    }

    @Test
    fun knownDriftAndSteelAnomalyAreAppliedAfterTheCameraPose() {
        val drifted = SimWalk(
            SimEnvironment.CORRIDOR,
            SEED,
            SimPhone(),
            yawWobbleRad = 0.0,
            pitchWobbleRad = 0.0,
            sensorNoise = false,
            driftPerMinuteRad = DRIFT_PER_MINUTE,
        ).samples(SimWalk.straightRoute(ROUTE_M)).toList()
        val last = drifted.last()
        val elapsedSeconds = (last.motion.tNs - START_NS) / 1_000_000_000.0
        val expected = wrap(last.handset.cameraYawRad + DRIFT_PER_MINUTE * elapsedSeconds / 60.0)
        assertEquals(expected, last.handset.rotationVectorYawRad, 1e-9)
        assertTrue(abs(last.handset.rotationVectorYawRad - last.handset.cameraYawRad) > 1e-4)

        val steel = SimWalk(
            SimEnvironment.STAIRWELL_LANDING,
            SEED,
            SimPhone(),
            yawWobbleRad = 0.0,
            pitchWobbleRad = 0.0,
            sensorNoise = false,
        ).samples(SimWalk.straightRoute(STEEL_ROUTE_M)).toList()
        assertTrue(
            steel.any {
                it.trueDistanceM in 20.0..24.0 &&
                    abs(wrap(it.handset.rotationVectorYawRad - it.handset.cameraYawRad)) > 0.05
            },
        )
    }

    private fun walk(yawWobbleRad: Double): List<SimWalkSample> = SimWalk(
        SimEnvironment.CORRIDOR,
        SEED,
        SimPhone(),
        yawWobbleRad = yawWobbleRad,
        pitchWobbleRad = 0.0,
        sensorNoise = false,
    ).samples(SimWalk.straightRoute(ROUTE_M)).toList()

    private fun wrap(angle: Double): Double {
        var wrapped = (angle + PI) % (2.0 * PI)
        if (wrapped < 0.0) wrapped += 2.0 * PI
        return wrapped - PI
    }

    private companion object {
        const val SEED = 104_729L
        const val ROUTE_M = 5.0
        const val STEEL_ROUTE_M = 25.0
        const val SAMPLE_S = 0.05
        const val START_NS = 1_000_000_000L
        const val HOLD_YAW = 0.2
        const val HOLD_PITCH = -0.1
        const val DRIFT_PER_MINUTE = 2.0 * PI / 180.0
    }
}
