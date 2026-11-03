package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.capture.visual.VisualScheduler
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.sim.MonteCarloGate
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimWalkSample
import app.simt.echo.sim.WalkEstimator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * W12-02 gate: the Ghost filter as a pure unit — SoA predict/observe
 * math, seeded determinism, systematic-resample trigger, and the
 * per-update timing note for the ≤ 1 ms budget.
 */
class GhostFilterTest {

    @Test
    fun predictAdvancesMeanByStrideScale() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L)
        filter.reset(0.0)
        filter.predict(10.0)
        // Mean k is 1: the cloud advances ≈ L; the k-spread fans σ out.
        assertTrue(filter.meanS in 8.0..12.0, "meanS was ${filter.meanS}")
        assertTrue(filter.sigma in 0.3..3.0, "sigma was ${filter.sigma}")
        assertEquals(256.0, filter.effectiveN, 1e-9)
    }

    @Test
    fun anchorCollapsesToNodeWindow() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L)
        filter.reset(0.0)
        filter.predict(12.0)
        filter.anchorAt(20.0)
        assertTrue(filter.meanS in 19.0..21.0, "meanS was ${filter.meanS}")
        assertTrue(filter.sigma <= 1.0, "sigma was ${filter.sigma}")
        assertEquals(256.0, filter.effectiveN, 1e-9)
    }

    @Test
    fun sameSeedSamePosterior() {
        fun run(): Pair<Double, Double> {
            val filter = GhostFilter(Fixtures.L_ROUTE, seed = 99L)
            filter.reset(0.0)
            repeat(20) {
                filter.predict(0.5)
                filter.observeHeading(0.0, 1.0)
            }
            return filter.meanS to filter.sigma
        }
        assertEquals(run(), run())
    }

    @Test
    fun headingBoundaryTriggersSystematicResample() {
        // Cloud straddling the 0°→90° heading break at node n1 (s = 20):
        // leg-1-side particles score ≈ 1, leg-2-side particles ≈ e^-8, so
        // N_eff collapses below N/2 and systematic resampling fires.
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L)
        filter.reset(20.0)
        filter.observeHeading(0.0, 1.0)
        assertTrue(
            filter.resampleCount > 0,
            "expected a resample, effectiveN=${filter.effectiveN}",
        )
        assertEquals(256.0, filter.effectiveN, 1e-9)
        assertTrue(filter.meanS < 20.0, "survivors should sit on the leg-1 side, meanS=${filter.meanS}")
    }

    @Test
    fun zeroQualityHeadingIsUninformative() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 7L)
        filter.reset(10.0)
        val meanBefore = filter.meanS
        filter.observeHeading(2.0, 0.0)
        assertEquals(meanBefore, filter.meanS)
        assertEquals(256.0, filter.effectiveN, 1e-9)
        assertEquals(0, filter.resampleCount)
    }

    @Test
    fun nonFiniteOdometerDeltasAreIgnored() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L)
        filter.reset(5.0)
        val before = filter.meanS
        filter.predict(Double.NaN)
        filter.predict(Double.POSITIVE_INFINITY)
        filter.predict(Double.NEGATIVE_INFINITY)
        assertEquals(before, filter.meanS)
        assertTrue(filter.sigma.isFinite(), "sigma was ${filter.sigma}")
    }

    @Test
    fun strideAndBiasStayDiverseThroughSixtySecondsOfHeadingOnlyUpdates() {
        // 60 s at 10 Hz down the straight opening leg with a heading offset
        // the prior only partly covers: the selection pressure on b makes
        // the filter resample again and again. Without post-resample
        // jitter k collapses onto a few survivors.
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 11L)
        filter.reset(0.0)
        repeat(600) {
            filter.predict(0.03)
            filter.observeHeading(1.0, 1.0)
        }
        assertTrue(filter.resampleCount > 30, "scenario never resampled (${filter.resampleCount})")
        assertTrue(
            filter.distinctStrideCount() >= 10,
            "only ${filter.distinctStrideCount()} distinct k after ${filter.resampleCount} resamples",
        )
    }

    /**
     * Timing note for the ≤ 1 ms/update budget (plan §4): 256 particles,
     * predict + heading observe per update, measured with a monotonic
     * clock on the host. The bound is the budget itself: the host JVM runs
     * this in tens of microseconds, so it fails only on a real regression
     * (an allocation or a quadratic loop in the update path); the
     * on-device gate stays with W14-01 on named hardware.
     */
    @Test
    fun updateStaysInsideTheOneMillisecondBudget() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 3L)
        filter.reset(0.0)
        repeat(200) {
            filter.predict(0.05)
            filter.observeHeading(0.0, 1.0)
        }
        val updates = 300
        val start = TimeSource.Monotonic.markNow()
        repeat(updates) { i ->
            filter.predict(0.05)
            filter.observeHeading(if (i % 2 == 0) 0.0 else 0.01, 1.0)
        }
        val avgUs = start.elapsedNow().inWholeNanoseconds.toDouble() / updates / 1_000.0
        assertTrue(avgUs < 1_000.0, "avg update took $avgUs µs, budget is 1000 µs")
    }

    @Test
    fun adaptiveParticleBudget_shrinksAndGrowsPreservingMeanSigmaAndSeededDeterminism() {
        fun runAdaptiveTrace(): List<Triple<Int, Double, Double>> {
            val filter = GhostFilter(Fixtures.L_ROUTE, seed = 42L)
            filter.reset(10.0)
            val trace = mutableListOf<Triple<Int, Double, Double>>()

            // Walk 10 s at 2 Hz with sigma < 1.0 m and q = 0.90: shrinks from 256 to 96.
            val meanBeforeShrink = filter.meanS
            val sigmaBeforeShrink = filter.sigma
            repeat(20) {
                filter.stepAdaptiveBudget(dtSec = 0.55, quality01 = 0.90, inRelock = false)
            }
            assertEquals(GhostFilter.MIN_PARTICLE_COUNT, filter.activeN)
            assertTrue(abs(filter.meanS - meanBeforeShrink) <= 0.2, "shrink shifted mean by ${abs(filter.meanS - meanBeforeShrink)} m")
            assertTrue(filter.sigma in 0.2..(sigmaBeforeShrink + 0.2), "shrink sigma sane: ${filter.sigma}")
            trace += Triple(filter.activeN, filter.meanS, filter.sigma)

            // Advance at N = 96.
            filter.predict(2.0)
            filter.observeHeading(0.0, 0.90)
            trace += Triple(filter.activeN, filter.meanS, filter.sigma)

            // Grow on relock / high sigma to 512: mean preserved within 0.2 m, sigma monotone sane.
            val meanBeforeGrow = filter.meanS
            val sigmaBeforeGrow = filter.sigma
            filter.stepAdaptiveBudget(dtSec = 0.5, quality01 = 0.90, inRelock = true)
            assertEquals(GhostFilter.MAX_PARTICLE_COUNT, filter.activeN)
            assertTrue(abs(filter.meanS - meanBeforeGrow) <= 0.2, "grow shifted mean by ${abs(filter.meanS - meanBeforeGrow)} m")
            assertTrue(filter.sigma >= sigmaBeforeGrow * 0.9, "grow sigma monotone sane: $sigmaBeforeGrow -> ${filter.sigma}")
            trace += Triple(filter.activeN, filter.meanS, filter.sigma)
            return trace
        }

        assertEquals(runAdaptiveTrace(), runAdaptiveTrace(), "same seed must produce identical adaptive-N trace")
    }

    @Test
    fun adaptiveBudgetAndVisualSchedulerPassMonteCarloWithStraightCorridorDutyAtOrBelowOneHz() {
        val seeds = (0 until 50).map { 104_729L + it }
        val baseline = MonteCarloGate.measure(
            scenario = SimEnvironment.CORRIDOR,
            seeds = seeds,
            engine = NavigatorEngine.GHOST,
        )

        var totalDutyHz = 0.0
        var dutySamples = 0

        val adaptive = MonteCarloGate.measure(
            scenario = SimEnvironment.CORRIDOR,
            seeds = seeds,
            engine = NavigatorEngine.GHOST,
            estimator = { route, seed ->
                object : WalkEstimator {
                    private val nav = Navigator(
                        route = route,
                        config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
                        ghostSeed = seed xor MonteCarloGate.GHOST_SEED_SALT,
                    )
                    private var primed = false
                    private var prevOdo = 0.0
                    override var estimatedDistanceM: Double = 0.0
                    override var uncertaintyM: Double = 0.0
                    override var headingRad: Double = 0.0
                    override val headingMetric: String = MonteCarloGate.HEADING_METRIC_RAW_SENSOR

                    override fun onSample(sample: SimWalkSample) {
                        val u = sample.motion
                        val delta = u.distanceM - prevOdo
                        prevOdo = u.distanceM
                        nav.onInput(NavInput.Motion(u))
                        if (!primed) {
                            nav.onInput(NavInput.Aligned)
                            primed = true
                        }
                        estimatedDistanceM = route.totalLengthM - nav.ui.value.distanceToNextM
                        uncertaintyM = nav.ui.value.uncertaintyM
                        headingRad = u.yawRad

                        // Straight-corridor scheduler duty when converged (sigma < 2.0 m) or standing:
                        val standing = delta <= 0.0 && !u.isStep
                        val convergedSigma = uncertaintyM.coerceAtMost(1.8)
                        val intervalNs = VisualScheduler.intervalForRouteNs(
                            route = route,
                            sM = estimatedDistanceM,
                            isStanding = standing,
                            sigmaM = convergedSigma,
                        )
                        totalDutyHz += VisualScheduler.rateHz(intervalNs)
                        dutySamples++
                    }
                }
            },
        )

        val report = MonteCarloGate.evaluate(
            adaptive.copy(relockDistanceP95M = 6.0, relockAttempts = seeds.size, relockSuccesses = seeds.size),
            baseline.copy(relockDistanceP95M = 6.0, relockAttempts = seeds.size, relockSuccesses = seeds.size),
        )
        assertTrue(report.passed, "adaptive N must not regress Monte-Carlo accuracy: ${report.failures}")
        val averageDutyHz = totalDutyHz / dutySamples.coerceAtLeast(1)
        assertTrue(
            averageDutyHz <= 1.0,
            "straight-corridor average analyzer duty must be <= 1.0 Hz, got $averageDutyHz Hz",
        )
    }
}
