package app.simt.echo.sim

import app.simt.echo.model.NavigatorEngine
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MonteCarloGateTest {
    @Test
    fun nearestRankPercentileUsesExpectedBoundary() {
        assertEquals(19.0, MonteCarloGate.percentile((1..20).map(Int::toDouble), 0.95))
        assertEquals(2.0, MonteCarloGate.percentile(listOf(4.0, 1.0, 3.0, 2.0), 0.5))
        assertEquals(1.0, MonteCarloGate.percentile(listOf(4.0, 1.0, 3.0, 2.0), 0.0))
    }

    @Test
    fun summaryWeightsUncertaintyCoverageByObservedSamples() {
        val result = MonteCarloGate.summarize(
            SimEnvironment.CORRIDOR,
            NavigatorEngine.LINEAR,
            listOf(
                MonteCarloGate.WalkMetrics(
                    arrivalErrorM = 2.0,
                    sigmaCoveredSamples = 9,
                    sigmaSamples = 10,
                    headingDriftDeg = 1.0,
                ),
                MonteCarloGate.WalkMetrics(
                    arrivalErrorM = 4.0,
                    sigmaCoveredSamples = 0,
                    sigmaSamples = 1,
                    headingDriftDeg = 3.0,
                ),
            ),
        )

        assertEquals(2, result.walkCount)
        assertEquals(11, result.sigmaSamples)
        assertEquals(9.0 / 11.0, result.sigmaCoverage01)
        assertEquals(4.0, result.arrivalErrorP95M)
        assertEquals(3.0, result.headingDriftP95Deg)
        assertEquals(null, result.relockDistanceP95M)
    }

    @Test
    fun missedArrivalUsesEndpointResidualInsteadOfRouteEndClamp() {
        assertEquals(5.5, MonteCarloGate.arrivalErrorAtBoundary(39.5, 45.0))
        assertEquals(0.0, MonteCarloGate.arrivalErrorAtBoundary(45.0, 45.0))
    }

    @Test
    fun biasedEstimatorDoesNotScoreBetterThanPerfectEstimate() {
        val perfect = MonteCarloGate.arrivalErrorAtBoundary(45.0, 45.0)
        val biasedHigh = MonteCarloGate.arrivalErrorAtBoundary(50.5, 45.0)
        val biasedLow = MonteCarloGate.arrivalErrorAtBoundary(39.5, 45.0)

        assertTrue(biasedHigh > perfect)
        assertEquals(biasedHigh, biasedLow)
    }

    @Test
    fun identicalSeededOneWalkSuitesAreReproducible() {
        val first = MonteCarloGate.runSuite(listOf(1))
        val second = MonteCarloGate.runSuite(listOf(1))

        assertEquals(first, second)
        assertEquals(SimEnvironment.entries.size * NavigatorEngine.entries.size, first.getValue(1).size)
        assertTrue(first.getValue(1).all { it.sigmaSamples > 0 })
        assertTrue(
            first.getValue(1).filter { it.engine == NavigatorEngine.GHOST }.all { it.relockAttempts == 1 },
        )
        for (rows in first.getValue(1).groupBy { it.scenario }.values) {
            assertEquals(1, rows.map { it.headingDriftP95Deg }.toSet().size)
            assertEquals(1, rows.map { it.sigmaSamples }.toSet().size)
        }
    }

    @Test
    fun fivePercentRegressionBoundaryPassesAndLargerRegressionFails() {
        val baseline = baselineMetrics()
        val atBoundary = baseline.copy(arrivalErrorP95M = 10.5, headingDriftP95Deg = 2.1)
        val aboveBoundary = baseline.copy(arrivalErrorP95M = 10.51)

        assertTrue(MonteCarloGate.evaluate(atBoundary, baseline).passed)
        val report = MonteCarloGate.evaluate(aboveBoundary, baseline)
        assertFalse(report.passed)
        assertTrue(report.failures.any { it.metric == "arrival-error-p95" })
    }

    @Test
    fun headingSigmaAndRelockRegressionBoundariesAreEnforced() {
        val baseline = baselineMetrics().copy(
            engine = NavigatorEngine.GHOST,
            sigmaCoverage01 = 1.0,
            relockDistanceP95M = 6.0,
            relockAttempts = 100,
            relockSuccesses = 100,
        )
        val atBoundary = baseline.copy(
            arrivalErrorP95M = 10.5,
            sigmaCoverage01 = 0.95,
            headingDriftP95Deg = 2.1,
            relockDistanceP95M = 6.3,
            relockSuccesses = 100,
        )
        val aboveBoundary = baseline.copy(
            arrivalErrorP95M = 10.51,
            sigmaCoverage01 = 0.949,
            headingDriftP95Deg = 2.101,
            relockDistanceP95M = 6.31,
            relockSuccesses = 94,
        )

        assertTrue(MonteCarloGate.evaluate(atBoundary, baseline).passed)
        val failures = MonteCarloGate.evaluate(aboveBoundary, baseline).failures.map { it.metric }.toSet()
        assertTrue("sigma-coverage-2s" in failures)
        assertTrue("heading-drift-p95-deg" in failures)
        assertTrue("relock-distance-p95-m" in failures)
        assertTrue("relock-success-rate" in failures)
    }

    @Test
    fun disconnectedEstimatorFailsTheGateOnItsEmittedState() {
        val seeds = listOf(SimWalk.BASELINE_SEED)
        val real = MonteCarloGate.measure(SimEnvironment.CORRIDOR, seeds)
        val broken = MonteCarloGate.measure(SimEnvironment.CORRIDOR, seeds) { _, _ ->
            FixedWalkEstimator(estimatedDistanceM = 0.0, uncertaintyM = 0.05, headingRad = PI / 2.0)
        }

        assertEquals(MonteCarloGate.HEADING_METRIC_RAW_SENSOR, real.headingMetric)
        assertEquals(MonteCarloGate.HEADING_METRIC_RAW_SENSOR, broken.headingMetric)
        assertTrue(real.arrivalErrorP95M < broken.arrivalErrorP95M)
        val failures = MonteCarloGate.evaluate(broken, real).failures.map { it.metric }.toSet()
        assertTrue("arrival-error-p95" in failures)
        assertTrue("sigma-coverage-2s" in failures)
        assertTrue("heading-drift-p95-deg" in failures)
    }

    @Test
    fun fusedHeadingNumbersAreNotScoredAsTheRawSensorBaseline() {
        val raw = baselineMetrics()
        val fused = raw.copy(headingMetric = MonteCarloGate.HEADING_METRIC_FUSED)
        assertTrue(MonteCarloGate.evaluate(fused, raw).failures.any { it.metric == "heading-metric" })
    }

    @Test
    fun underCalibratedUncertaintyAndBrokenEstimatorFail() {
        val baseline = baselineMetrics()
        val underCalibrated = baseline.copy(sigmaCoverage01 = 0.949)
        val deliberatelyBrokenEstimator = baseline.copy(arrivalErrorP95M = baseline.arrivalErrorP95M + 5.0)

        assertTrue(MonteCarloGate.evaluate(underCalibrated, baseline).failures.any { it.metric == "sigma-coverage-2s" })
        assertTrue(
            MonteCarloGate.evaluate(deliberatelyBrokenEstimator, baseline)
                .failures.any { it.metric == "arrival-error-p95" },
        )
    }

    @Test
    fun invalidCountsNonFiniteValuesAndMissingRelockEvidenceAreRejected() {
        assertFailsWith<IllegalArgumentException> { MonteCarloGate.percentile(emptyList(), 0.95) }
        assertFailsWith<IllegalArgumentException> { MonteCarloGate.percentile(listOf(1.0), Double.NaN) }
        assertFailsWith<IllegalArgumentException> { MonteCarloGate.runSuite(listOf(0)) }

        val baseline = baselineMetrics().copy(
            engine = NavigatorEngine.GHOST,
            relockDistanceP95M = 6.0,
            relockAttempts = 100,
            relockSuccesses = 100,
        )
        val nonFinite = baseline.copy(arrivalErrorP95M = Double.NaN)
        val noRelockEvidence = baseline.copy(relockAttempts = 0, relockSuccesses = 0, relockDistanceP95M = null)

        assertFalse(MonteCarloGate.evaluate(nonFinite, baseline).passed)
        assertTrue(
            MonteCarloGate.evaluate(noRelockEvidence, baseline)
                .failures.any { it.metric.startsWith("relock-") },
        )
    }

    @Test
    fun anyFailedRelockAndNonFiniteRelockDistanceFailTheGate() {
        val baseline = baselineMetrics().copy(
            engine = NavigatorEngine.GHOST,
            relockDistanceP95M = 6.0,
            relockAttempts = 100,
            relockSuccesses = 100,
        )
        val oneFailedRelock = baseline.copy(relockSuccesses = 99)
        val nonFiniteRelock = baseline.copy(relockDistanceP95M = Double.NaN)

        assertTrue(
            MonteCarloGate.evaluate(oneFailedRelock, baseline)
                .failures.any { it.metric == "relock-success-rate" },
        )
        assertTrue(
            MonteCarloGate.evaluate(nonFiniteRelock, baseline)
                .failures.any { it.metric == "relock-evidence" },
        )
    }

    private fun baselineMetrics() = ScenarioMetrics(
        scenario = SimEnvironment.CORRIDOR,
        engine = NavigatorEngine.LINEAR,
        walkCount = 100,
        arrivalErrorP95M = 10.0,
        sigmaCoverage01 = 0.96,
        sigmaSamples = 1_000,
        headingDriftP95Deg = 2.0,
    )
}
