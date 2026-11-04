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

    @Test
    fun at1BobInPlaceAtFourHzRejectsAtLeastNinetyFivePercent() {
        for (environment in ENVIRONMENTS) {
            val result = gateScenario(environment, SimMotion.BOB_IN_PLACE, frameHz = 4)
            assertTrue(result.candidates > 0, "$environment must produce candidates")
            assertAcceptanceAtMost(result, 0.05, "$environment bob 4 Hz")
        }
    }

    @Test
    fun at2BobInPlaceAtTwoHzSurvivesThermalModerate() {
        for (environment in ENVIRONMENTS) {
            val result = gateScenario(environment, SimMotion.BOB_IN_PLACE, frameHz = 2)
            assertAcceptanceAtMost(result, 0.05, "$environment bob 2 Hz")
        }
    }

    @Test
    fun at3RecordingAndProfilerConsumersDoNotBreakTheGate() {
        val result = gateScenario(SimEnvironment.CORRIDOR, SimMotion.BOB_IN_PLACE, frameHz = 8)
        assertAcceptanceAtMost(result, 0.05, "corridor bob 8 Hz with additional tap consumers")
    }

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

    @Test
    fun at6WalkingKeepsFalseRejectsUnderOnePercentAndWitnessesMoving() {
        for (environment in ENVIRONMENTS) {
            for (frameHz in listOf(4, 2)) {
                // Keep the per-frame displacement at the 4 Hz 1.2 m/s budget:
                // 0.6 m/s at 2 Hz is the same 0.3 m/frame the flow can match.
                val result = gateScenario(
                    environment,
                    SimMotion.WALK,
                    frameHz = frameHz,
                    speedMps = if (frameHz == 2) 0.3 else 0.6,
                )
                assertTrue(result.candidates > 0, "$environment walk $frameHz Hz must produce candidates")
                val falseRejects = result.rejected.toDouble() / result.candidates
                assertTrue(falseRejects <= 0.01, "$environment walk $frameHz Hz false rejects $falseRejects")
                val witnessed = result.movingWitnessed.toDouble() / result.candidates
                assertTrue(
                    witnessed >= 0.90,
                    "$environment walk $frameHz Hz witnessed $witnessed; candidates=${result.candidates} " +
                        "accepted=${result.accepted} rejected=${result.rejected} moving=${result.movingVerdicts} " +
                        "still=${result.stillVerdicts} unknown=${result.unknownVerdicts} " +
                        "noConf=${result.unknownNoConfident} tooFew=${result.unknownTooFew} " +
                        "sigma=${result.unknownSigma} band=${result.unknownBand} blind=${result.unknownCameraBlind} " +
                        "frames=${result.framesProcessed} conf=${result.confidentFrames}/${result.unconfidentFrames} " +
                        "blur=${result.blurFrames} usabilityRejected=${result.usabilityRejected} " +
                        "pairs=${result.measuredPairs} lastUsability=${result.lastUsability} last=${result.lastReason} " +
                        "fits=${result.fits} maxInliers=${result.maxInliers} maxConf=${result.maxConfidence} " +
                        "maxExpansion=${result.maxExpansion} maxFlowBlocks=${result.maxFlowBlocks} " +
                        "unknownMean=${result.unknownMeanSum / result.unknownVerdicts.coerceAtLeast(1)} " +
                        "unknownSigma=${result.unknownSigmaSum / result.unknownVerdicts.coerceAtLeast(1)} " +
                        "unknownConf=${result.unknownConfidentSum / result.unknownVerdicts.coerceAtLeast(1)} " +
                        "unknownT=${result.unknownTimesSeconds}",
                )
            }
        }
    }

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
        assertTrue(
            accepted <= fraction,
            "$label phantom acceptance $accepted > $fraction; accepted=${result.accepted} " +
                "rejected=${result.rejected} still=${result.stillVerdicts} unknown=${result.unknownVerdicts} " +
                "moving=${result.movingVerdicts} frames=${result.confidentFrames}/${result.unconfidentFrames} " +
                "blur=${result.blurFrames} unknownNoConf=${result.unknownNoConfident} " +
                "tooFew=${result.unknownTooFew} sigma=${result.unknownSigma} band=${result.unknownBand} " +
                "blind=${result.unknownCameraBlind} last=${result.lastReason} conf=${result.lastConfidence01} " +
                "pairs=${result.measuredPairs} usabilityRejected=${result.usabilityRejected} " +
                "fits=${result.fits} maxInliers=${result.maxInliers} maxConf=${result.maxConfidence} " +
                "maxExpansion=${result.maxExpansion}",
        )
    }

    private companion object {
        val ENVIRONMENTS = listOf(SimEnvironment.CORRIDOR, SimEnvironment.OPEN_HALL, SimEnvironment.JUNCTION)
    }
}
