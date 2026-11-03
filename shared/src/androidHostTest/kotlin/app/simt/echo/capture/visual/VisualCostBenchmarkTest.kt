package app.simt.echo.capture.visual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualCostBenchmarkTest {
    @Test
    fun reportsMedianHostCostOfFlowOnlyVersusCombinedPipelineOnIdenticalInputs() {
        val fixture = VisualPipelineHostFixture(WARM_FRAMES + ROUNDS * FRAMES_PER_ROUND)
        try {
            var warmFlow = 0
            var warmCombined = 0
            for (i in 0 until WARM_FRAMES) {
                if (fixture.flowOnly(i)) warmFlow++
                if (fixture.dispatch(i)) warmCombined++
            }
            assertEquals(WARM_FRAMES - 1, warmFlow)
            assertEquals(WARM_FRAMES - 1, warmCombined)
            val flowNs = LongArray(ROUNDS)
            val combinedNs = LongArray(ROUNDS)
            val ratios = DoubleArray(ROUNDS)
            val before = fixture.consumer.framesProcessed
            val ledgerBefore = fixture.ledgerFrames
            for (round in 0 until ROUNDS) {
                val start = WARM_FRAMES + round * FRAMES_PER_ROUND
                if (round % 2 == 0) {
                    flowNs[round] = measureFlow(fixture, start)
                    combinedNs[round] = measureCombined(fixture, start)
                } else {
                    combinedNs[round] = measureCombined(fixture, start)
                    flowNs[round] = measureFlow(fixture, start)
                }
                assertTrue(flowNs[round] > 0L && combinedNs[round] > 0L)
                ratios[round] = combinedNs[round].toDouble() / flowNs[round]
            }
            assertEquals((ROUNDS * FRAMES_PER_ROUND).toLong(), fixture.consumer.framesProcessed - before)
            assertEquals((ROUNDS * FRAMES_PER_ROUND).toLong(), fixture.ledgerFrames - ledgerBefore)
            assertEquals(0L, fixture.mux.consumerFailures)
            assertEquals(0L, fixture.consumer.ring.dropped)
            assertTrue(fixture.checksum.isFinite())
            val medianFlow = flowNs.sortedArray()[ROUNDS / 2].toDouble() / FRAMES_PER_ROUND
            val medianCombined = combinedNs.sortedArray()[ROUNDS / 2].toDouble() / FRAMES_PER_ROUND
            val medianRatio = ratios.sortedArray()[ROUNDS / 2]
            assertTrue(medianRatio.isFinite() && medianRatio > 0.0)
            println("VisualMotion host cost: flow-only median=$medianFlow ns/frame; combined median=$medianCombined ns/frame; median paired ratio=$medianRatio")
            println("VisualMotion host batches ($FRAMES_PER_ROUND frames): flowNs=${flowNs.joinToString()}; combinedNs=${combinedNs.joinToString()}")
            // Host-relative evidence only. Phase 19 owns the absolute 0.8 ms phone gate.
        } finally {
            fixture.close()
        }
    }

    private fun measureFlow(fixture: VisualPipelineHostFixture, start: Int): Long {
        var usable = 0
        val before = System.nanoTime()
        for (i in start until start + FRAMES_PER_ROUND) if (fixture.flowOnly(i)) usable++
        val elapsed = System.nanoTime() - before
        assertEquals(FRAMES_PER_ROUND, usable, "flow-only timing must not include skipped frames")
        return elapsed
    }

    private fun measureCombined(fixture: VisualPipelineHostFixture, start: Int): Long {
        var usable = 0
        val before = System.nanoTime()
        for (i in start until start + FRAMES_PER_ROUND) if (fixture.dispatch(i)) usable++
        val elapsed = System.nanoTime() - before
        assertEquals(FRAMES_PER_ROUND, usable, "combined timing must include fresh usable drained frames")
        return elapsed
    }

    companion object {
        private const val WARM_FRAMES = 5_000
        private const val FRAMES_PER_ROUND = 512
        private const val ROUNDS = 9
    }
}