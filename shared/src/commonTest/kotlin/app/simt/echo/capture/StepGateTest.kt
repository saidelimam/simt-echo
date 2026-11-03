package app.simt.echo.capture

import app.simt.echo.Fixtures
import app.simt.echo.capture.visual.EgoMotionFit
import app.simt.echo.capture.visual.PoseMode
import app.simt.echo.capture.visual.VisualFlow
import app.simt.echo.capture.visual.VisualUsability
import app.simt.echo.capture.visual.egoMotionTestFlow
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.Navigator
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWorld
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W16-04 gates: phantom-step rejection on shake-in-place (≥ 95 %), true-step
 * preservation while walking (≤ 1 % false rejects), slow-shuffle
 * `MISSED_STEP_SUSPECT` logging + σ inflation without inventing distance, and
 * bit-identical behaviour when `visualAssist` is off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepGateTest {

    @Test
    fun shakeInPlaceRejectsNinetyFivePercentPhantomStepsAndWalkingFalseRejectsUnderOnePercent() = runTest {
        val world = SimWorld(SimEnvironment.CORRIDOR, seed = SEED, pixelSamplesPerAxis = 2)
        val intrinsics = CameraIntrinsics(
            fPx = world.phone.focalLengthPx,
            cx = SimWorld.LUMA_WIDTH / 2.0,
            cy = SimWorld.LUMA_HEIGHT / 2.0,
            source = IntrinsicsSource.CHARACTERISTICS,
        )
        val standingPose = SimPose(x = 0.0, y = 55.0, z = 1.5, yawRad = 0.0)
        val stillFrameA = world.render(standingPose, 0L)
        val stillFrameB = world.render(standingPose.copy(yawRad = 0.008), 250_000_000L)
        val usability = VisualUsability()
        val uStill = usability.evaluate(stillFrameB.luma, SimWorld.LUMA_WIDTH, SimWorld.LUMA_HEIGHT, PoseMode.HELD_UP, 0.03)
        val stillFlow = VisualFlow().also {
            it.onFrame(stillFrameA.luma, 0L, intrinsics, clockAligned = true)
            it.onFrame(stillFrameB.luma, 250_000_000L, intrinsics, deltaYawRad = 0.008, clockAligned = true)
        }
        val stillEgo = EgoMotionFit().fit(stillFlow.flow, uStill)
        assertTrue(stillEgo.confidence01 >= 0.5, "textured corridor still frame must be confident")
        assertTrue(kotlin.math.abs(stillEgo.expansionPerSec) < VisualStepGate.MOTION_EXPANSION_FLOOR_PER_SEC)

        // 1. Shake-in-place scenario (60 s at 50 Hz, 2 Hz shake).
        val unguardedShake = PdrEngine(visualAssist = false)
        val guardedShake = PdrEngine(visualAssist = true)
        unguardedShake.start()
        guardedShake.start()

        val hz = 50
        val durationSec = 60
        for (i in 0 until hz * durationSec) {
            val tNs = i * 20_000_000L
            val tSec = i.toDouble() / hz
            if (i % 12 == 0) { // visual update every 240 ms (within ±0.4 s window)
                guardedShake.onVisualEgo(stillEgo, tNs)
            }
            val az = 9.81 + 2.2 * sin(2.0 * PI * 2.0 * tSec)
            val sample = ImuSample(tNs = tNs, ax = 0.0, ay = 0.0, az = az)
            unguardedShake.onAccelerometer(sample)
            guardedShake.onAccelerometer(sample)
        }

        val baselinePhantomSteps = unguardedShake.stepGate.acceptedStepCount.let {
            // unguardedShake has visualAssist=false; read its emitted step count via stepGate or comparison
            guardedShake.stepGate.acceptedStepCount + guardedShake.stepGate.rejectedPhantomCount
        }
        assertTrue(baselinePhantomSteps >= 100, "shake fixture must generate >= 100 step candidates, got $baselinePhantomSteps")
        val rejectionRate = guardedShake.stepGate.rejectedPhantomCount.toDouble() / baselinePhantomSteps
        assertTrue(
            rejectionRate >= 0.95,
            "shake-in-place must reject >= 95% of phantom steps, rejected ${guardedShake.stepGate.rejectedPhantomCount}/$baselinePhantomSteps ($rejectionRate)",
        )

        // 2. True-step walking scenario (60 s at 50 Hz, 2 Hz gait, forward visual expansion).
        val movePoseB = standingPose.copy(y = standingPose.y + 0.25)
        val walkFrameB = world.render(movePoseB, 250_000_000L)
        val uWalk = usability.evaluate(walkFrameB.luma, SimWorld.LUMA_WIDTH, SimWorld.LUMA_HEIGHT, PoseMode.HELD_UP, 0.0)
        val walkFlow = VisualFlow().also {
            it.onFrame(stillFrameA.luma, 0L, intrinsics, clockAligned = true)
            it.onFrame(walkFrameB.luma, 250_000_000L, intrinsics, clockAligned = true)
        }
        val walkEgo = EgoMotionFit().fit(walkFlow.flow, uWalk)
        assertTrue(walkEgo.confidence01 >= 0.5)
        assertTrue(walkEgo.expansionPerSec >= VisualStepGate.MOTION_EXPANSION_FLOOR_PER_SEC)

        val guardedWalk = PdrEngine(visualAssist = true)
        guardedWalk.start()
        for (i in 0 until hz * durationSec) {
            val tNs = i * 20_000_000L
            val tSec = i.toDouble() / hz
            if (i % 12 == 0) {
                guardedWalk.onVisualEgo(walkEgo, tNs)
            }
            val az = 9.81 + 2.2 * sin(2.0 * PI * 2.0 * tSec)
            guardedWalk.onAccelerometer(ImuSample(tNs = tNs, ax = 0.0, ay = 0.0, az = az))
        }

        val totalTrueCandidates = guardedWalk.stepGate.acceptedStepCount + guardedWalk.stepGate.rejectedPhantomCount
        assertTrue(totalTrueCandidates >= 100, "walk fixture must generate >= 100 true steps")
        val falseRejectRate = guardedWalk.stepGate.rejectedPhantomCount.toDouble() / totalTrueCandidates
        assertTrue(
            falseRejectRate <= 0.01,
            "true-step false reject rate must be <= 1%, got $falseRejectRate",
        )
    }

    @Test
    fun stepCandidateAcceptedWhenVisualConfidenceLowOrOutsideFourHundredMsWindow() {
        val gate = VisualStepGate()
        // Low confidence (0.2) with zero expansion still accepts the step.
        gate.onVisual(expansionPerSec = 0.0, confidence01 = 0.2, tNs = 1_000_000_000L)
        assertEquals(StepGateVerdict.ACCEPTED, gate.evaluateStepCandidate(1_100_000_000L))

        // Confident zero expansion at t = 2.0 s rejects a step at t = 2.35 s (within 0.4 s)
        // but accepts a step at t = 2.50 s (outside 0.4 s).
        gate.onVisual(expansionPerSec = 0.0, confidence01 = 0.9, tNs = 2_000_000_000L)
        assertEquals(StepGateVerdict.REJECTED_PHANTOM, gate.evaluateStepCandidate(2_350_000_000L))
        assertEquals(StepGateVerdict.ACCEPTED, gate.evaluateStepCandidate(2_500_000_000L))
    }

    @Test
    fun slowShuffleFlagsMissedStepSuspectsLogsTimestampsRaisesSigmaAndNeverInventsDistance() = runTest {
        val engine = PdrEngine(visualAssist = true)
        engine.start()
        val updates = mutableListOf<MotionUpdate>()
        val job = launch { engine.updates.collect { updates += it } }
        runCurrent()

        val fitter = EgoMotionFit()
        val movingEgo = fitter.fit(egoMotionTestFlow(expansionPerSec = 0.14), usability01 = 0.9)
        assertTrue(movingEgo.confidence01 >= 0.5)

        val route = Fixtures.L_ROUTE
        val ghostNav = Navigator(route, EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true), ghostSeed = SEED)
        val linearNav = Navigator(route, EchoConfig(navigatorEngine = NavigatorEngine.LINEAR, visualAssist = true), ghostSeed = SEED)

        val initMotion = MotionUpdate(100_000_000L, 0.0, 0.0, 0, false, null, "test")
        ghostNav.onInput(NavInput.Motion(initMotion))
        ghostNav.onInput(NavInput.Aligned)
        linearNav.onInput(NavInput.Motion(initMotion))
        linearNav.onInput(NavInput.Aligned)

        val initialGhostSigma = ghostNav.ui.value.uncertaintyM
        val initialLinearSigma = linearNav.ui.value.uncertaintyM
        val initialGhostRemaining = ghostNav.ui.value.distanceToNextM

        // Slow shuffle for 4 s: weak accelerometer (amplitude 0.35 <= 0.9 threshold) + confident visual expansion.
        val hz = 50
        for (i in 1..(hz * 4)) {
            val tNs = 100_000_000L + i * 20_000_000L
            val tSec = i.toDouble() / hz
            val az = 9.81 + 0.35 * sin(2.0 * PI * 1.5 * tSec)
            engine.onAccelerometer(ImuSample(tNs, 0.0, 0.0, az))
            if (i % 10 == 0) {
                engine.onVisualEgo(movingEgo, tNs)
            }
        }
        runCurrent()
        job.cancel()

        val suspects = updates.filter { it.missedStepSuspect }
        assertTrue(suspects.isNotEmpty(), "slow shuffle must emit MISSED_STEP_SUSPECT updates")
        assertEquals(suspects.size, engine.stepGate.missedStepSuspectCount)
        assertEquals(suspects.map { it.tNs }, engine.stepGate.suspectTimestampsNs().toList())
        assertTrue(updates.all { !it.isStep && it.stepCount == 0 && it.distanceM == 0.0 }, "must never invent steps or distance")

        for (u in suspects) {
            ghostNav.onInput(NavInput.Motion(u))
            linearNav.onInput(NavInput.Motion(u))
        }
        assertEquals(suspects.size, ghostNav.missedStepSuspectCount)
        assertTrue(ghostNav.ui.value.uncertaintyM > initialGhostSigma + 0.2, "Ghost sigma must rise on slow shuffle")
        assertTrue(linearNav.ui.value.uncertaintyM > initialLinearSigma + 0.2, "Linear sigma must rise on slow shuffle")
        assertEquals(initialGhostRemaining, ghostNav.ui.value.distanceToNextM, 0.15, "must not invent forward progress")
    }

    @Test
    fun visualOffLeavesPdrEngineUnchanged() = runTest {
        val engine = PdrEngine(visualAssist = false)
        engine.start()
        val updates = mutableListOf<MotionUpdate>()
        val job = launch { engine.updates.collect { updates += it } }
        runCurrent()

        val stillEgo = EgoMotionFit().fit(egoMotionTestFlow(expansionPerSec = 0.0), usability01 = 1.0)
        val hz = 50
        for (i in 0 until hz * 10) {
            val tNs = i * 20_000_000L
            val tSec = i.toDouble() / hz
            if (i % 10 == 0) engine.onVisualEgo(stillEgo, tNs)
            val az = 9.81 + 2.0 * sin(2.0 * PI * 2.0 * tSec)
            engine.onAccelerometer(ImuSample(tNs, 0.0, 0.0, az))
        }
        runCurrent()
        job.cancel()

        val steps = updates.filter { it.isStep }
        assertTrue(steps.size in 19..21, "expected 20 ± 1 steps when visualAssist is false, got ${steps.size}")
        assertEquals(0, engine.stepGate.rejectedPhantomCount)
        assertFalse(updates.any { it.missedStepSuspect })
    }

    private companion object {
        const val SEED = 104_729L
    }
}
