package app.simt.echo.capture.visual

import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimMotion
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review 0015 §3 acceptance matrix. These tests are deliberately red on the
 * harness-only HEAD and carry the written justification the review requires:
 * each is enabled by the slice named in its [Ignore] message.
 */
class StationaryGateAcceptanceTest {

    @Ignore("R15-01: red until R15-04 — HEAD 38e1baf accepts phantom steps under bob + latency")
    @Test
    fun at1BobInPlaceAtFourHzRejectsAtLeastNinetyFivePercent() {
        for (environment in ENVIRONMENTS) {
            val result = gateScenario(environment, SimMotion.BOB_IN_PLACE, frameHz = 4)
            assertTrue(result.candidates > 0, "$environment must produce candidates")
            assertAcceptanceAtMost(result, 0.05, "$environment bob 4 Hz")
        }
    }

    @Ignore("R15-01: red until R15-06 — HEAD 38e1baf halves the witness to 2 Hz at MODERATE")
    @Test
    fun at2BobInPlaceAtTwoHzSurvivesThermalModerate() {
        for (environment in ENVIRONMENTS) {
            val result = gateScenario(environment, SimMotion.BOB_IN_PLACE, frameHz = 2)
            assertAcceptanceAtMost(result, 0.05, "$environment bob 2 Hz")
        }
    }

    @Ignore("R15-01: red until R15-04 — HEAD 38e1baf has no deferred post-candidate evidence")
    @Test
    fun at3RecordingAndProfilerConsumersDoNotBreakTheGate() {
        val result = gateScenario(SimEnvironment.CORRIDOR, SimMotion.BOB_IN_PLACE, frameHz = 8)
        assertAcceptanceAtMost(result, 0.05, "corridor bob 8 Hz with additional tap consumers")
    }

    @Ignore("R15-01: red until R15-04 — HEAD 38e1baf cannot count unwitnessed accepts")
    @Test
    fun at4ShakeRejectsAtLeastEightyPercentAndCountsEveryAcceptAsUnwitnessed() {
        for (environment in ENVIRONMENTS) {
            val result = gateScenario(environment, SimMotion.SHAKE, frameHz = 4)
            assertAcceptanceAtMost(result, 0.20, "$environment shake")
            assertEquals(result.accepted, result.unwitnessed, "$environment shake accepts must be unwitnessed")
        }
    }

    @Ignore("R15-01: red until R15-07 — HEAD 38e1baf has no evidence-based suspect rule")
    @Test
    fun at5StillInHandForSixtySecondsProducesNoStepsAndNoSuspects() {
        for (environment in ENVIRONMENTS) {
            val result = gateScenario(environment, SimMotion.STILL_IN_HAND, frameHz = 4)
            assertEquals(0, result.candidates, "$environment still must not count a step")
            assertEquals(0, result.suspects, "$environment still must not flag a suspect")
        }
    }

    @Ignore("R15-01: red until R15-04 — HEAD 38e1baf accepts or abstains instead of witnessing walking")
    @Test
    fun at6WalkingKeepsFalseRejectsUnderOnePercentAndWitnessesMoving() {
        for (environment in ENVIRONMENTS) {
            for (frameHz in listOf(4, 2)) {
                val result = gateScenario(environment, SimMotion.WALK, frameHz = frameHz)
                assertTrue(result.candidates > 0, "$environment walk $frameHz Hz must produce candidates")
                val falseRejects = result.rejected.toDouble() / result.candidates
                assertTrue(falseRejects <= 0.01, "$environment walk $frameHz Hz false rejects $falseRejects")
                val witnessed = result.movingWitnessed.toDouble() / result.candidates
                assertTrue(witnessed >= 0.90, "$environment walk $frameHz Hz witnessed $witnessed")
            }
        }
    }

    @Ignore("R15-01: red until R15-04 — HEAD 38e1baf cannot distinguish slow walking from still")
    @Test
    fun at7SlowWalkingInOpenHallHasNoFalseRejects() {
        val result = gateScenario(SimEnvironment.OPEN_HALL, SimMotion.WALK, frameHz = 4, speedMps = 0.5)
        assertTrue(result.candidates > 0, "slow walk must produce candidates")
        val falseRejects = result.rejected.toDouble() / result.candidates
        assertTrue(falseRejects <= 0.01, "slow walk false rejects $falseRejects")
    }

    private fun assertAcceptanceAtMost(result: GateScenarioResult, fraction: Double, label: String) {
        assertTrue(result.candidates > 0, "$label must produce candidates")
        val accepted = result.accepted.toDouble() / result.candidates
        assertTrue(accepted <= fraction, "$label phantom acceptance $accepted > $fraction")
    }

    private companion object {
        val ENVIRONMENTS = listOf(SimEnvironment.CORRIDOR, SimEnvironment.OPEN_HALL, SimEnvironment.JUNCTION)
    }
}
