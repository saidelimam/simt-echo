package app.simt.echo.capture

import app.simt.echo.Fixtures
import app.simt.echo.capture.visual.EgoMotionFit
import app.simt.echo.capture.visual.PoseMode
import app.simt.echo.capture.visual.VisualFlow
import app.simt.echo.capture.visual.VisualUsability
import app.simt.echo. capture.visual.egoMotionTestFlow
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.Navigator
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPhone
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWalk
import app.simt.echo.sim.SimWorld
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W16-03 gates: three-witness heading triangulation (gyro + magnetically-gated
 * rotation vector + confidence-gated visual yaw residual and FOE travel offset).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeadingTriangulationTest {

    @Test
    fun simWorldTenMinuteWalkBoundsDriftToFiveDegreesWithVisualAndDriftsTwentyWithout() {
        val driftPerMinRad = 2.0 * PI / 180.0
        val holdYawRad = 8.0 * PI / 180.0
        val phone = SimPhone(holdYawOffsetRad = holdYawRad, holdPitchRad = -0.05)
        val route = SimWalk.straightRoute(lengthM = 600.0)
        val world = SimWorld(SimEnvironment.CORRIDOR, phone, seed = SEED, pixelSamplesPerAxis = 2)
        val intrinsics = CameraIntrinsics(
            fPx = phone.focalLengthPx,
            cx = SimWorld.LUMA_WIDTH / 2.0,
            cy = SimWorld.LUMA_HEIGHT / 2.0,
            source = IntrinsicsSource.CHARACTERISTICS,
        )

        val walk = SimWalk(
            environment = SimEnvironment.CORRIDOR,
            seed = SEED,
            phone = phone,
            yawWobbleRad = 0.02,
            pitchWobbleRad = 0.015,
            sensorNoise = false,
            driftPerMinuteRad = driftPerMinRad,
        )

        val visualHq = HeadingQuality()
        val baselineHq = HeadingQuality()
        val flow = VisualFlow()
        val fitter = EgoMotionFit()
        val usability = VisualUsability()

        var nextPairStartNs = 1_000_000_000L
        var pairFirstPose: SimPose? = null
        var pairFirstTNs = 0L
        val pairIntervalNs = 1_500_000_000L // 1 pair every 1.5 s (well inside 2 s visual hold window)

        var finalTrueYaw = 0.0
        var finalVisualFusedYaw = 0.0
        var finalBaselineYaw = 0.0
        var lastTNs = 0L

        for (sample in walk.samples(route, renderFrames = false)) {
            val tNs = sample.motion.tNs
            lastTNs = tNs
            // Walking accelerometer magnitude (~2 Hz gait) keeps isStill == false.
            val tSec = tNs / 1e9
            val magG = 1.0 + 0.22 * sin(2.0 * PI * 1.9 * tSec)
            visualHq.onAccelerometer(magG, tNs)
            baselineHq.onAccelerometer(magG, tNs)
            if (sample.motion.isStep) {
                visualHq.onStep(tNs)
                baselineHq.onStep(tNs)
            }

            sample.motion.magMag?.let { mag ->
                val vert = sample.motion.magVert ?: Double.NaN
                visualHq.onMagnetic(mag, vert, tNs)
                baselineHq.onMagnetic(mag, vert, tNs)
            }

            val corridorY = 6.0 + (sample.cameraPose.y % 48.0)
            val renderPose = sample.cameraPose.copy(y = corridorY)
            if (pairFirstPose == null && tNs >= nextPairStartNs && corridorY <= 52.0) {
                flow.reset()
                val frame = world.render(renderPose, tNs)
                flow.onFrame(frame.luma, tNs, intrinsics, clockAligned = true)
                pairFirstPose = renderPose
                pairFirstTNs = tNs
            } else if (pairFirstPose != null && tNs - pairFirstTNs >= 100_000_000L) {
                val prev = pairFirstPose!!
                pairFirstPose = null
                nextPairStartNs = tNs + pairIntervalNs
                if (renderPose.y > prev.y) {
                    val frame = world.render(renderPose, tNs)
                    val usable = usability.evaluate(frame.luma, SimWorld.LUMA_WIDTH, SimWorld.LUMA_HEIGHT, PoseMode.HELD_UP, 0.0)
                    val dYaw = wrapPi(renderPose.yawRad - prev.yawRad)
                    val dPitch = renderPose.pitchRad - prev.pitchRad
                    if (flow.onFrame(frame.luma, tNs, intrinsics, deltaYawRad = dYaw, deltaPitchRad = dPitch, clockAligned = true)) {
                        val ego = fitter.fit(flow.flow, usable)
                        visualHq.onVisualEgo(ego, tNs)
                    }
                }
            }

            visualHq.onGyro(sample.handset.measuredYawRateRadPerSec, tNs)
            val rvYaw = sample.handset.rotationVectorYawRad
            visualHq.onYaw(rvYaw, tNs, axisSwitched = false)
            baselineHq.onYaw(rvYaw, tNs, axisSwitched = false)

            finalTrueYaw = sample.trueYawRad
            finalVisualFusedYaw = visualHq.fusedHeading(rvYaw)
            finalBaselineYaw = rvYaw
        }

        val elapsedMinutes = (lastTNs - 1_000_000_000L) / 1e9 / 60.0
        assertTrue(elapsedMinutes >= 9.5, "walk must span ~10 minutes, got $elapsedMinutes min")
        assertFalse(visualHq.isStill, "walker is moving, never still")

        val baselineDriftDeg = abs(wrapPi(finalBaselineYaw - holdYawRad - finalTrueYaw)) * 180.0 / PI
        val visualDriftDeg = abs(wrapPi(finalVisualFusedYaw - finalTrueYaw)) * 180.0 / PI

        assertTrue(
            baselineDriftDeg in 18.0..23.0,
            "without visual, 10 min drift at 2°/min should be ~20°, got ${baselineDriftDeg}°",
        )
        assertTrue(
            visualDriftDeg <= 5.0,
            "with visual triangulation, 10 min heading error must be <= 5°, got ${visualDriftDeg}°",
        )
    }

    @Test
    fun visualGyroBiasEstimatorRecoversDriftWhileWalkingWithoutStillness() {
        val hq = HeadingQuality()
        val fitter = EgoMotionFit()
        val trueDriftRate = 1.5 * PI / 180.0 / 60.0 // 1.5°/min in rad/s
        val holdOffsetRad = 6.0 * PI / 180.0
        val foeX = tan(-holdOffsetRad)

        val hz = 20
        val durationSec = 600
        for (i in 0 until hz * durationSec) {
            val tNs = (i + 1) * 50_000_000L
            val tSec = tNs / 1e9
            // Walking accelerometer variance keeps isStill == false throughout.
            hq.onAccelerometer(1.0 + 0.25 * sin(2.0 * PI * 2.0 * tSec), tNs)
            if (i % 10 == 0) hq.onStep(tNs)

            // Visual ego frame every 0.5 s (2 Hz): residual yaw is -trueDriftRate.
            if (i % 10 == 0) {
                val flow = egoMotionTestFlow(
                    expansionPerSec = 0.18,
                    foeX = foeX,
                    foeY = 0.0,
                    yawResidualRadPerSec = -trueDriftRate,
                    deltaSeconds = 0.5,
                )
                val ego = fitter.fit(flow, usability01 = 0.9)
                hq.onVisualEgo(ego, tNs)
            }

            val rawYaw = wrapPi(holdOffsetRad + trueDriftRate * tSec)
            hq.onYaw(rawYaw, tNs, axisSwitched = false)
        }

        assertFalse(hq.isStill, "must remain in walking state")
        val tolerance = 0.25 * PI / 180.0 / 60.0
        assertEquals(
            trueDriftRate,
            hq.driftRateRadPerSec,
            tolerance,
            "walking visual bias estimator must recover 1.5°/min within 0.25°/min",
        )
        val endRawYaw = wrapPi(holdOffsetRad + trueDriftRate * durationSec)
        val fused = hq.fusedHeading(endRawYaw)
        val fusedErrorDeg = abs(wrapPi(fused)) * 180.0 / PI
        assertTrue(fusedErrorDeg <= 1.5, "fused heading after 10 min must stay within 1.5°, got $fusedErrorDeg°")
    }

    @Test
    fun steelAnomalyCorridorLowersQualityAndHoldsHeading() {
        val route = SimWalk.straightRoute(lengthM = 30.0)
        val walk = SimWalk(
            environment = SimEnvironment.STAIRWELL_LANDING,
            seed = SEED,
            phone = SimPhone(holdYawOffsetRad = 0.0, holdPitchRad = 0.0),
            yawWobbleRad = 0.0,
            pitchWobbleRad = 0.0,
            sensorNoise = false,
            driftPerMinuteRad = 0.0,
        )

        val hq = HeadingQuality()
        var cleanQuality = 1.0
        var minSteelQuality = 1.0
        var maxRawSteelErrorDeg = 0.0
        var maxCorrectedSteelErrorDeg = 0.0
        var postSteelErrorDeg = 0.0

        for (sample in walk.samples(route)) {
            val tNs = sample.motion.tNs
            val tSec = tNs / 1e9
            hq.onAccelerometer(1.0 + 0.2 * sin(2.0 * PI * 2.0 * tSec), tNs)
            if (sample.motion.isStep) hq.onStep(tNs)
            sample.motion.magMag?.let { mag ->
                hq.onMagnetic(mag, sample.motion.magVert ?: Double.NaN, tNs)
            }
            hq.onGyro(sample.handset.measuredYawRateRadPerSec, tNs)
            val rvYaw = sample.handset.rotationVectorYawRad
            hq.onYaw(rvYaw, tNs, axisSwitched = false)

            val corrected = hq.correctedYaw(rvYaw)
            val rawErrDeg = abs(wrapPi(rvYaw - sample.trueYawRad)) * 180.0 / PI
            val corrErrDeg = abs(wrapPi(corrected - sample.trueYawRad)) * 180.0 / PI

            when {
                sample.trueDistanceM in 10.0..18.0 -> cleanQuality = hq.quality
                sample.trueDistanceM in 20.5..23.5 -> {
                    minSteelQuality = minOf(minSteelQuality, hq.quality)
                    maxRawSteelErrorDeg = maxOf(maxRawSteelErrorDeg, rawErrDeg)
                    maxCorrectedSteelErrorDeg = maxOf(maxCorrectedSteelErrorDeg, corrErrDeg)
                }
                sample.trueDistanceM >= 27.0 -> postSteelErrorDeg = corrErrDeg
            }
        }

        assertTrue(cleanQuality > 0.85, "clean corridor quality should be high, got $cleanQuality")
        assertTrue(minSteelQuality < 0.5, "steel anomaly must lower q below 0.5, got $minSteelQuality")
        assertTrue(maxRawSteelErrorDeg > 10.0, "raw rotation vector is pulled > 10° by steel, got $maxRawSteelErrorDeg°")
        assertTrue(
            maxCorrectedSteelErrorDeg <= 1.5,
            "triangulated heading must hold <= 1.5° through steel anomaly, got $maxCorrectedSteelErrorDeg°",
        )
        assertTrue(
            postSteelErrorDeg <= 1.5,
            "heading after exiting steel zone must stay <= 1.5°, got $postSteelErrorDeg°",
        )
    }

    @Test
    fun blankWallWalkKeepsVisualSilentAndMatchesBaselineBitIdentically() {
        val route = SimWalk.straightRoute(lengthM = 25.0)
        val samples = SimWalk(SimEnvironment.CORRIDOR, SEED).samples(route).toList()

        val blankLuma = ByteArray(SimWorld.LUMA_WIDTH * SimWorld.LUMA_HEIGHT) { 120 }
        val usability = VisualUsability()
        val fitter = EgoMotionFit()
        val flow = VisualFlow()
        val intrinsics = CameraIntrinsics.defaultFor(SimWorld.LUMA_WIDTH, SimWorld.LUMA_HEIGHT)

        val baselineHq = HeadingQuality()
        val blankVisualHq = HeadingQuality()

        val baseNav = Navigator(route, EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = false), ghostSeed = SEED)
        val visualNav = Navigator(route, EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true), ghostSeed = SEED)

        samples.forEachIndexed { idx, sample ->
            val tNs = sample.motion.tNs
            baselineHq.onAccelerometer(1.15, tNs)
            blankVisualHq.onAccelerometer(1.15, tNs)
            if (sample.motion.isStep) {
                baselineHq.onStep(tNs)
                blankVisualHq.onStep(tNs)
            }

            // Feed blank-wall frame on every step: usability < 0.2, confidence == 0.0.
            if (sample.motion.isStep) {
                val u = usability.evaluate(blankLuma, SimWorld.LUMA_WIDTH, SimWorld.LUMA_HEIGHT, PoseMode.HELD_UP, 0.0)
                assertTrue(u < 0.2, "blank wall usability must be < 0.2, got $u")
                flow.onFrame(blankLuma, tNs, intrinsics, clockAligned = true)
                val ego = fitter.fit(flow.flow, u)
                assertEquals(0.0, ego.confidence01)
                blankVisualHq.onVisualEgo(ego, tNs)
                visualNav.onVisualEgo(ego)
            }

            baselineHq.onGyro(sample.handset.measuredYawRateRadPerSec, tNs)
            blankVisualHq.onGyro(sample.handset.measuredYawRateRadPerSec, tNs)

            baselineHq.onYaw(sample.motion.yawRad, tNs, axisSwitched = false)
            blankVisualHq.onYaw(sample.motion.yawRad, tNs, axisSwitched = false)

            assertEquals(0.0, blankVisualHq.visualConfidence01)
            assertEquals(0.0, blankVisualHq.travelOffsetRad)
            assertEquals(baselineHq.driftRateRadPerSec, blankVisualHq.driftRateRadPerSec)
            assertEquals(baselineHq.correctedYaw(sample.motion.yawRad), blankVisualHq.correctedYaw(sample.motion.yawRad))
            assertEquals(baselineHq.quality, blankVisualHq.quality)

            baseNav.onInput(NavInput.Motion(sample.motion))
            visualNav.onInput(NavInput.Motion(sample.motion))
            if (idx == 0) {
                baseNav.onInput(NavInput.Aligned)
                visualNav.onInput(NavInput.Aligned)
            }
            assertEquals(baseNav.ui.value, visualNav.ui.value)
        }
    }

    @Test
    fun travelDirectionOffsetFromFoeAlignsNavigatorAndGhostHeading() {
        val route = Fixtures.L_ROUTE
        val nav = Navigator(
            route = route,
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            ghostSeed = SEED,
        )
        // User aligns pointing straight along leg 0 (0°).
        nav.onInput(NavInput.Motion(MotionUpdate(1_000_000_000L, 0.0, 0.0, 0, false, null, "test")))
        nav.onInput(NavInput.Aligned)

        // User then holds the phone 10° to the right while walking straight along leg 0 (0°).
        val holdRad = 10.0 * PI / 180.0
        val fitter = EgoMotionFit()
        val ego = fitter.fit(
            egoMotionTestFlow(expansionPerSec = 0.16, foeX = tan(-holdRad), foeY = 0.0),
            usability01 = 1.0,
        )
        assertTrue(ego.hasFoe)
        assertEquals(-holdRad, ego.travelOffsetRad, 1e-6)

        for (step in 1..15) {
            val tNs = 1_000_000_000L + step * 500_000_000L
            nav.onVisualEgo(ego)
            nav.onInput(NavInput.Motion(MotionUpdate(tNs, step * 0.7, holdRad, step, true, null, "test", 0.95)))
        }

        // Travel offset converges to -10°, cancelling the +10° camera hold in the arrow and Ghost heading.
        assertEquals(-holdRad, nav.travelOffsetRad, 0.02)
        assertTrue(abs(nav.ui.value.arrowRad) < 0.02, "arrow should point straight ahead, got ${nav.ui.value.arrowRad}")
    }

    @Test
    fun defaultOffGoldenTraceIsBitIdentical() = runTest {
        val engineDefault = PdrEngine(k = 0.48)
        val engineWithIgnoredVisual = PdrEngine(k = 0.48, visualAssist = false)
        engineDefault.start()
        engineWithIgnoredVisual.start()
        val traceA = mutableListOf<MotionUpdate>()
        val traceB = mutableListOf<MotionUpdate>()
        val jobA = launch { engineDefault.updates.collect { traceA += it } }
        val jobB = launch { engineWithIgnoredVisual.updates.collect { traceB += it } }
        runCurrent()

        val ego = EgoMotionFit().fit(
            egoMotionTestFlow(expansionPerSec = 0.2, foeX = 0.15, yawResidualRadPerSec = 0.0005),
            usability01 = 1.0,
        )
        val hz = 50
        for (i in 0 until hz * 6) {
            val tNs = (i + 1) * 20_000_000L
            val tSec = i.toDouble() / hz
            val az = 9.81 + 2.0 * sin(2.0 * PI * 2.0 * tSec)
            val yaw = 0.05 * sin(tSec)
            val c = cos(yaw).toFloat()
            val s = sin(yaw).toFloat()
            val rot = floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
            val imu = ImuSample(tNs, 0.0, 0.0, az)
            val rotSample = RotSample(tNs, rot)
            engineDefault.onAccelerometer(imu)
            engineDefault.onRotation(rotSample)
            engineWithIgnoredVisual.onVisualEgo(ego, tNs)
            engineWithIgnoredVisual.onAccelerometer(imu)
            engineWithIgnoredVisual.onRotation(rotSample)
        }
        runCurrent()
        jobA.cancel()
        jobB.cancel()
        assertTrue(traceA.isNotEmpty())
        assertEquals(traceA, traceB, "default visualAssist = false must be bit-identical")
    }

    private companion object {
        const val SEED = 104_729L
    }
}
