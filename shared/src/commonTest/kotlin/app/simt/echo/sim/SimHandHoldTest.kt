package app.simt.echo.sim

import app.simt.echo.capture.PdrEngine
import app.simt.echo.capture.visual.GateScenarioRunner
import app.simt.echo.capture.visual.PoseMode
import app.simt.echo.capture.visual.VisualFramePose
import app.simt.echo.capture.visual.VisualImuHistory
import app.simt.echo.capture.visual.gateWorld
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimHandHoldTest {

    @Test
    fun stillAndBobOrientationsAreTheRealUsefulPoseWithFiniteRates() {
        for (motion in listOf(SimMotion.STILL_IN_HAND, SimMotion.BOB_IN_PLACE)) {
            val hand = SimHandHold(motion)
            val history = VisualImuHistory()
            val target = VisualFramePose()
            val startNs = 1_000_000_000L
            for (i in 0..(50 * 4)) {
                val tNs = startNs + i * 20_000_000L
                val tSec = i / 50.0
                history.onRotation(tNs, hand.orientationMatrixAt(tSec))
                val a = hand.accelerationMagnitudeAt(tSec)
                val g = hand.gravityUnitAt(tSec)
                history.onAccelerometer(tNs, a * g[0], a * g[1], a * g[2])
            }
            val atNs = startNs + 3_000_000_000L
            val atSec = 3.0
            history.onRotation(atNs, hand.orientationMatrixAt(atSec))
            assertTrue(history.readAt(atNs, 100.0, target), "$motion must be readable")
            assertEquals(PoseMode.HELD_UP, target.mode, "$motion must stay in the useful hold cone")
            assertTrue(target.angularRateRadPerSec.isFinite())
            assertTrue(abs(target.angularRateRadPerSec) < 2.0, "$motion rate ${target.angularRateRadPerSec}")
        }
    }

    @Test
    fun bobProducesStepScaleAccelerationWhileStillDoesNot() {
        val bob = SimHandHold(SimMotion.BOB_IN_PLACE)
        val still = SimHandHold(SimMotion.STILL_IN_HAND)
        val bobPeak = (0..200).maxOf { abs(bob.accelerationMagnitudeAt(it / 100.0) - 9.81) }
        val stillPeak = (0..200).maxOf { abs(still.accelerationMagnitudeAt(it / 100.0) - 9.81) }
        assertTrue(bobPeak > 0.9, "bob peak $bobPeak must cross the PDR candidate floor")
        assertTrue(stillPeak < 0.1, "still peak $stillPeak must not")
        assertTrue(bobPeak < 9.81, "acceleration must stay physical")
    }

    @Test
    fun delayedFramesStillReachTheAnalyzerAndAStillHoldCountsNoSteps() {
        val world = gateWorld(SimEnvironment.CORRIDOR)
        val engine = PdrEngine(visualAssist = true)
        try {
            val result = GateScenarioRunner(world, SimHandHold(SimMotion.STILL_IN_HAND), frameHz = 4, latencyMs = 120L)
                .run(engine, durationSec = 6.0)
            assertEquals(24L, result.framesProcessed, "every captured frame must be analysed after latency")
            assertTrue(
                result.hasUsableFrame,
                "the still corridor must yield a usable frame: reason=${result.lastReason} " +
                    "usability=${result.lastUsability01} confidence=${result.lastConfidence01}",
            )
            assertEquals(0, result.candidates, "a still hand must not produce a step candidate")
        } finally {
            engine.stop()
        }
    }
}
