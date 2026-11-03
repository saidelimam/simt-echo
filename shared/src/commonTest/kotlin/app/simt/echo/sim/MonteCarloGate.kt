package app.simt.echo.sim

import app.simt.echo.Fixtures
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.Navigator
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil

@Serializable
data class AccuracyBaseline(
    val schemaVersion: Int = BASELINE_SCHEMA_VERSION,
    val evidenceType: String = "sim",
    val seedStart: Long = SimWalk.BASELINE_SEED,
    val navigationRouteLengthM: Double = MonteCarloGate.BASELINE_ROUTE_LENGTH_M,
    val arrivalCheckpointM: Double = MonteCarloGate.ARRIVAL_SCORE_DISTANCE_M,
    val fastWalksPerScenario: Int = SimWalk.DEFAULT_FAST_WALKS,
    val fullWalksPerScenario: Int = SimWalk.DEFAULT_FULL_WALKS,
    val fast: List<ScenarioMetrics>,
    val full: List<ScenarioMetrics>,
) {
    companion object {
        const val BASELINE_SCHEMA_VERSION = 1
    }
}

@Serializable
data class ScenarioMetrics(
    val scenario: SimEnvironment,
    val engine: NavigatorEngine,
    val walkCount: Int,
    val arrivalErrorP95M: Double,
    val sigmaCoverage01: Double,
    val sigmaSamples: Int,
    val headingDriftP95Deg: Double,
    /** Which heading quantity [headingDriftP95Deg] scores. The recorded baseline is raw sensor yaw. */
    val headingMetric: String = MonteCarloGate.HEADING_METRIC_RAW_SENSOR,
    val relockDistanceP95M: Double? = null,
    val relockAttempts: Int = 0,
    val relockSuccesses: Int = 0,
) {
    val relockSuccessRate01: Double?
        get() = relockAttempts.takeIf { it > 0 }?.let { relockSuccesses.toDouble() / it }
}

data class GateFailure(
    val metric: String,
    val baseline: Double?,
    val measured: Double?,
    val explanation: String,
)

data class GateReport(val failures: List<GateFailure>) {
    val passed: Boolean get() = failures.isEmpty()
}

/** Deterministic trial runner and regression gate for the current nav engines. */
object MonteCarloGate {
    fun runSuite(
        walkCounts: List<Int>,
        seedStart: Long = SimWalk.BASELINE_SEED,
    ): Map<Int, List<ScenarioMetrics>> {
        require(walkCounts.isNotEmpty() && walkCounts.all { it > 0 })
        val requestedCounts = walkCounts.toSet()
        val maximumCount = requestedCounts.maxOrNull() ?: error("walkCounts must not be empty")
        val measurements = requestedCounts.associateWith { mutableListOf<ScenarioMetrics>() }
        for (scenario in SimEnvironment.entries) {
            for (engine in NavigatorEngine.entries) {
                val trials = ArrayList<WalkMetrics>(maximumCount)
                for (trial in 0 until maximumCount) {
                    val seed = seedFor(seedStart, scenario, trial)
                    trials += runTrial(scenario, engine, seed)
                    val count = trial + 1
                    if (count in requestedCounts) {
                        measurements.getValue(count) += summarize(scenario, engine, trials)
                    }
                }
            }
        }
        return measurements.mapValues { (_, rows) -> rows.toList() }
    }

    fun summarize(
        scenario: SimEnvironment,
        engine: NavigatorEngine,
        trials: List<WalkMetrics>,
    ): ScenarioMetrics {
        require(trials.isNotEmpty())
        require(trials.all { it.sigmaSamples > 0 && it.sigmaCoveredSamples in 0..it.sigmaSamples })
        require(trials.all { it.arrivalErrorM.isFinite() && it.arrivalErrorM >= 0.0 })
        require(trials.all { it.headingDriftDeg.isFinite() && it.headingDriftDeg >= 0.0 })
        require(trials.all { it.headingMetric == trials.first().headingMetric && it.headingMetric.isNotEmpty() })
        require(trials.all { trial ->
            val distance = trial.relockDistanceM
            distance == null || (trial.relockAttempted && distance.isFinite() && distance >= 0.0)
        })
        val sigmaSamples = trials.sumOf { it.sigmaSamples }
        val coveredSamples = trials.sumOf { it.sigmaCoveredSamples }
        val relockTrials = trials.filter { it.relockAttempted }
        val successfulRelocks = relockTrials.mapNotNull { it.relockDistanceM }
        return ScenarioMetrics(
            scenario = scenario,
            engine = engine,
            walkCount = trials.size,
            arrivalErrorP95M = percentile(trials.map { it.arrivalErrorM }, P95),
            sigmaCoverage01 = coveredSamples.toDouble() / sigmaSamples,
            sigmaSamples = sigmaSamples,
            headingDriftP95Deg = percentile(trials.map { it.headingDriftDeg }, P95),
            headingMetric = trials.first().headingMetric,
            relockDistanceP95M = successfulRelocks.takeIf { it.isNotEmpty() }?.let { percentile(it, P95) },
            relockAttempts = relockTrials.size,
            relockSuccesses = successfulRelocks.size,
        )
    }

    fun percentile(values: List<Double>, quantile: Double): Double {
        require(values.isNotEmpty())
        require(quantile.isFinite() && quantile in 0.0..1.0)
        require(values.all { it.isFinite() })
        val sorted = values.sorted()
        val nearestRank = ceil(quantile * sorted.size).toInt().coerceAtLeast(1)
        return sorted[nearestRank - 1]
    }

    internal fun arrivalErrorAtBoundary(
        estimatedAtEndM: Double,
        trueAtEndM: Double,
    ): Double {
        require(estimatedAtEndM.isFinite() && trueAtEndM.isFinite())
        return abs(estimatedAtEndM - trueAtEndM)
    }

    fun evaluate(
        measured: ScenarioMetrics,
        baseline: ScenarioMetrics,
        maximumRegression01: Double = MAX_REGRESSION_01,
        minimumSigmaCoverage01: Double = MIN_SIGMA_COVERAGE_01,
    ): GateReport {
        require(maximumRegression01 in 0.0..1.0 && maximumRegression01.isFinite())
        require(minimumSigmaCoverage01 in 0.0..1.0 && minimumSigmaCoverage01.isFinite())
        val failures = mutableListOf<GateFailure>()
        if (measured.scenario != baseline.scenario || measured.engine != baseline.engine) {
            failures += GateFailure("identity", null, null, "scenario and engine must match the baseline")
        }
        if (measured.headingMetric != baseline.headingMetric) {
            failures += GateFailure(
                "heading-metric",
                null,
                null,
                "heading drift is ${baseline.headingMetric}; ${measured.headingMetric} is a different seam",
            )
        }
        if (measured.walkCount <= 0 || baseline.walkCount <= 0 || measured.walkCount != baseline.walkCount ||
            measured.sigmaSamples <= 0 || baseline.sigmaSamples <= 0
        ) {
            failures += GateFailure("sample-count", baseline.walkCount.toDouble(), measured.walkCount.toDouble(), "walk counts must match and all counts must be positive")
            return GateReport(failures)
        }
        if (!hasValidRelockEvidence(measured) || !hasValidRelockEvidence(baseline)) {
            failures += GateFailure("relock-evidence", null, null, "counts and distances must describe valid relock outcomes")
            return GateReport(failures)
        }
        if (!baseline.arrivalErrorP95M.isFinite() || baseline.arrivalErrorP95M < 0.0 ||
            !baseline.sigmaCoverage01.isFinite() || baseline.sigmaCoverage01 !in 0.0..1.0 ||
            !baseline.headingDriftP95Deg.isFinite() || baseline.headingDriftP95Deg < 0.0
        ) {
            failures += GateFailure("baseline", null, null, "all baseline metrics must be finite and in range")
            return GateReport(failures)
        }
        if (!measured.arrivalErrorP95M.isFinite() || measured.arrivalErrorP95M < 0.0) {
            failures += GateFailure("arrival-error-p95", baseline.arrivalErrorP95M, measured.arrivalErrorP95M, "must be finite and non-negative")
        } else if (regressed(measured.arrivalErrorP95M, baseline.arrivalErrorP95M, maximumRegression01)) {
            failures += GateFailure("arrival-error-p95", baseline.arrivalErrorP95M, measured.arrivalErrorP95M, "regressed by more than ${(maximumRegression01 * 100).toInt()}%")
        }
        if (!measured.sigmaCoverage01.isFinite() || measured.sigmaCoverage01 !in 0.0..1.0) {
            failures += GateFailure("sigma-coverage-2s", baseline.sigmaCoverage01, measured.sigmaCoverage01, "must be finite and between 0 and 1")
        } else {
            if (measured.sigmaCoverage01 < minimumSigmaCoverage01) {
                failures += GateFailure("sigma-coverage-2s", minimumSigmaCoverage01, measured.sigmaCoverage01, "uncertainty is under-calibrated")
            }
            if (measured.sigmaCoverage01 < baseline.sigmaCoverage01 * (1.0 - maximumRegression01)) {
                failures += GateFailure("sigma-coverage-2s", baseline.sigmaCoverage01, measured.sigmaCoverage01, "coverage regressed by more than ${(maximumRegression01 * 100).toInt()}%")
            }
        }
        if (!measured.headingDriftP95Deg.isFinite() || measured.headingDriftP95Deg < 0.0) {
            failures += GateFailure("heading-drift-p95-deg", baseline.headingDriftP95Deg, measured.headingDriftP95Deg, "must be finite and non-negative")
        } else if (regressed(measured.headingDriftP95Deg, baseline.headingDriftP95Deg, maximumRegression01)) {
            failures += GateFailure("heading-drift-p95-deg", baseline.headingDriftP95Deg, measured.headingDriftP95Deg, "regressed by more than ${(maximumRegression01 * 100).toInt()}%")
        }
        if (baseline.relockAttempts > 0) {
            val measuredRate = measured.relockSuccessRate01
            val baselineRate = baseline.relockSuccessRate01
            if (measuredRate == null || baselineRate == null) {
                failures += GateFailure("relock-success-rate", baselineRate, measuredRate, "relock evidence is missing")
            } else if (measuredRate < baselineRate * (1.0 - maximumRegression01)) {
                failures += GateFailure("relock-success-rate", baselineRate, measuredRate, "successful relocks regressed by more than ${(maximumRegression01 * 100).toInt()}%")
            }
            if (measured.relockSuccesses != measured.relockAttempts) {
                failures += GateFailure(
                    "relock-success-rate",
                    baselineRate,
                    measuredRate,
                    "one or more relock attempts failed",
                )
            }
            val measuredRelock = measured.relockDistanceP95M
            val baselineRelock = baseline.relockDistanceP95M
            if (measuredRelock == null || baselineRelock == null) {
                failures += GateFailure("relock-distance-p95-m", baselineRelock, measuredRelock, "relock distance evidence is missing")
            } else if (regressed(measuredRelock, baselineRelock, maximumRegression01)) {
                failures += GateFailure("relock-distance-p95-m", baselineRelock, measuredRelock, "regressed by more than ${(maximumRegression01 * 100).toInt()}%")
            }
        }
        return GateReport(failures)
    }

    /**
     * Scores seeds through [estimator]. The default reads the real [Navigator] and the
     * raw sensor yaw. Relock stays on [runSuite]; this path is the arrival and heading seam.
     */
    fun measure(
        scenario: SimEnvironment,
        seeds: List<Long>,
        engine: NavigatorEngine = NavigatorEngine.LINEAR,
        estimator: (route: EchoRoute, seed: Long) -> WalkEstimator = { route, seed ->
            NavigatorWalkEstimator(route, engine, seed)
        },
    ): ScenarioMetrics {
        require(seeds.isNotEmpty())
        val route = SimWalk.straightRoute(BASELINE_ROUTE_LENGTH_M)
        val trials = seeds.map { seed -> scoreWalk(scenario, seed, route, estimator(route, seed)) }
        return summarize(scenario, engine, trials)
    }

    private fun runTrial(scenario: SimEnvironment, engine: NavigatorEngine, seed: Long): WalkMetrics {
        val route = SimWalk.straightRoute(BASELINE_ROUTE_LENGTH_M)
        val scored = scoreWalk(scenario, seed, route, NavigatorWalkEstimator(route, engine, seed))
        val relock = if (engine == NavigatorEngine.GHOST) measureRelock(scenario, seed xor RELOCK_SEED_SALT) else null
        return scored.copy(
            relockAttempted = engine == NavigatorEngine.GHOST,
            relockDistanceM = relock,
        )
    }

    private fun scoreWalk(scenario: SimEnvironment, seed: Long, route: EchoRoute, estimator: WalkEstimator): WalkMetrics {
        val samples = SimWalk(scenario, seed).samples(route).iterator()
        check(samples.hasNext()) { "SimWalk must yield an initial sensor sample" }
        estimator.onSample(samples.next())
        var finalEstimatedDistanceM = 0.0
        var finalTrueDistanceM = 0.0
        var headingDriftDeg = 0.0
        var sigmaCoveredSamples = 0
        var sigmaSamples = 0
        while (samples.hasNext()) {
            val sample = samples.next()
            estimator.onSample(sample)
            val estimatedDistanceM = estimator.estimatedDistanceM
            val errorM = abs(estimatedDistanceM - sample.trueDistanceM)
            finalEstimatedDistanceM = estimatedDistanceM
            finalTrueDistanceM = sample.trueDistanceM
            val sigma = estimator.uncertaintyM
            if (sigma.isFinite() && sigma >= 0.0 && errorM <= 2.0 * sigma) sigmaCoveredSamples += 1
            sigmaSamples += 1
            headingDriftDeg = abs(wrapAngle(estimator.headingRad - sample.trueYawRad)) * 180.0 / PI
            if (sample.trueDistanceM >= ARRIVAL_SCORE_DISTANCE_M) break
        }
        check(sigmaSamples > 0)
        return WalkMetrics(
            arrivalErrorM = arrivalErrorAtBoundary(finalEstimatedDistanceM, finalTrueDistanceM),
            sigmaCoveredSamples = sigmaCoveredSamples,
            sigmaSamples = sigmaSamples,
            headingDriftDeg = headingDriftDeg,
            headingMetric = estimator.headingMetric,
        )
    }

    private fun measureRelock(scenario: SimEnvironment, seed: Long): Double? {
        val route = Fixtures.L_ROUTE
        val nav = Navigator(route, EchoConfig(navigatorEngine = NavigatorEngine.GHOST), ghostSeed = seed)
        val samples = SimWalk(scenario, seed).samples(route).iterator()
        check(samples.hasNext())
        nav.onInput(NavInput.Motion(samples.next().motion))
        nav.onInput(NavInput.Aligned)
        var lostAtM: Double? = null
        while (samples.hasNext()) {
            val sample = samples.next()
            nav.onInput(NavInput.Motion(sample.motion))
            if (lostAtM == null && sample.trueDistanceM >= RELOCK_START_M) {
                nav.onInput(NavInput.Lost)
                if (nav.ui.value.phase == NavPhase.RELOCKING) lostAtM = sample.trueDistanceM
            } else if (lostAtM != null && nav.ui.value.phase == NavPhase.WALK) {
                return (sample.trueDistanceM - lostAtM).coerceAtLeast(0.0)
            }
        }
        return null
    }

    private fun seedFor(start: Long, scenario: SimEnvironment, trial: Int): Long =
        start + scenario.ordinal * SCENARIO_SEED_STRIDE + trial

    private fun regressed(measured: Double, baseline: Double, tolerance: Double): Boolean =
        measured > baseline * (1.0 + tolerance) + METRIC_EPSILON

    private fun hasValidRelockEvidence(metrics: ScenarioMetrics): Boolean {
        if (metrics.relockAttempts < 0 || metrics.relockSuccesses !in 0..metrics.relockAttempts) return false
        val distance = metrics.relockDistanceP95M
        if (metrics.engine == NavigatorEngine.LINEAR) {
            return metrics.relockAttempts == 0 && metrics.relockSuccesses == 0 && distance == null
        }
        if (metrics.relockAttempts != metrics.walkCount) return false
        if (metrics.relockSuccesses == 0) return distance == null
        return distance != null && distance.isFinite() && distance >= 0.0
    }

    private fun wrapAngle(angle: Double): Double {
        var wrapped = (angle + PI) % (2.0 * PI)
        if (wrapped < 0.0) wrapped += 2.0 * PI
        return wrapped - PI
    }

    data class WalkMetrics(
        val arrivalErrorM: Double,
        val sigmaCoveredSamples: Int,
        val sigmaSamples: Int,
        val headingDriftDeg: Double,
        val headingMetric: String = HEADING_METRIC_RAW_SENSOR,
        val relockAttempted: Boolean = false,
        val relockDistanceM: Double? = null,
    )

    private const val P95 = 0.95
    private const val MAX_REGRESSION_01 = 0.05
    private const val MIN_SIGMA_COVERAGE_01 = 0.95
    internal const val BASELINE_ROUTE_LENGTH_M = 60.0
    internal const val ARRIVAL_SCORE_DISTANCE_M = 45.0
    private const val RELOCK_START_M = 15.0
    private const val SCENARIO_SEED_STRIDE = 1_000_000L
    internal const val GHOST_SEED_SALT = 0x6a09_e667_f3bc_c909L
    const val HEADING_METRIC_RAW_SENSOR = "raw-sensor-yaw"
    const val HEADING_METRIC_FUSED = "fused-heading"
    private const val RELOCK_SEED_SALT = -0x4498_517a_7b35_58c5L
    private const val METRIC_EPSILON = 1e-9
}
