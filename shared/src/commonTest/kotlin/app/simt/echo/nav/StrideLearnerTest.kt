package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.model.EchoConfig
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.StrideLearner
import app.simt.echo.platform.MotionUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W12-03 gate: the stride posterior as a pure unit — convergence on
 * fixture legs, clamp bounds, length weighting, persistence round-trip
 * and determinism — plus the additive Ghost `k`-prior hookup and the
 * Navigator's per-anchored-leg feed.
 */
class StrideLearnerTest {

    @Test
    fun convergesOnFixtureLegs() {
        // Follower odometer under-reads 10 %: every anchored leg yields
        // r = 1.1, so the posterior mean must settle near 1.1.
        val learner = StrideLearner()
        val legs = listOf(20.0, 15.0, 10.0)
        repeat(10) {
            for (length in legs) learner.observe(length, length / 1.1)
        }
        assertEquals(30, learner.legs)
        assertTrue(learner.meanK in 1.05..1.15, "meanK was ${learner.meanK}")
    }

    @Test
    fun clampsToPlausibleBand() {
        val high = StrideLearner()
        repeat(20) { high.observe(100.0, 1.0) }
        assertTrue(high.meanK <= 1.5, "meanK was ${high.meanK}")

        val low = StrideLearner()
        repeat(20) { low.observe(1.0, 100.0) }
        assertTrue(low.meanK >= 0.15, "meanK was ${low.meanK}")
    }

    @Test
    fun longLegMovesPosteriorMoreThanShortLeg() {
        val short = StrideLearner()
        short.observe(2.0, 2.0 / 1.2)
        val long = StrideLearner()
        long.observe(20.0, 20.0 / 1.2)
        assertEquals(1, short.legs)
        assertEquals(1, long.legs)
        assertTrue(
            long.meanK - 1.0 > short.meanK - 1.0,
            "long=${long.meanK} short=${short.meanK}",
        )
    }

    @Test
    fun ignoresDegenerateObservations() {
        val learner = StrideLearner()
        learner.observe(0.0, 5.0)
        learner.observe(10.0, 0.0)
        learner.observe(-4.0, 5.0)
        learner.observe(10.0, Double.NaN)
        learner.observe(Double.POSITIVE_INFINITY, 5.0)
        assertEquals(0, learner.legs)
        assertEquals(1.0, learner.meanK, 1e-9)
    }

    @Test
    fun persistenceRoundTrip() {
        val learner = StrideLearner()
        repeat(6) { learner.observe(20.0, 20.0 / 1.1) }
        val restored = StrideLearner.fromStored(learner.meanK, learner.legs)
        assertEquals(learner.legs, restored.legs)
        assertEquals(learner.meanK, restored.meanK, 1e-9)
        // Null (never learned) restores the unlearned prior.
        assertEquals(1.0, StrideLearner.fromStored(null, 0).meanK, 1e-9)
    }

    @Test
    fun identicalSequencesAreIdentical() {
        fun run(): Pair<Double, Int> {
            val learner = StrideLearner()
            repeat(5) {
                learner.observe(20.0, 18.0)
                learner.observe(15.0, 16.5)
            }
            return learner.meanK to learner.legs
        }
        assertEquals(run(), run())
    }

    @Test
    fun ghostPriorUsesLearnedK() {
        fun meanAfterWalk(kPrior: Double): Double {
            val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L, kPriorMean = kPrior)
            filter.reset(0.0)
            filter.predict(10.0)
            return filter.meanS
        }
        // Same seed, same gauss stream: the learned prior shifts the
        // whole cloud by ≈ 0.2 · 10 m.
        val plain = meanAfterWalk(1.0)
        val learned = meanAfterWalk(1.2)
        assertTrue(plain in 8.0..12.0, "plain meanS was $plain")
        assertTrue(learned in 10.0..14.0, "learned meanS was $learned")
        assertTrue(learned - plain > 1.0, "plain=$plain learned=$learned")
        // An implausible stored K falls back to the unlearned prior.
        assertEquals(plain, meanAfterWalk(48.0))
    }

    @Test
    fun navigatorFeedsOneObservationPerAnchoredLeg() {
        val nav = Navigator(Fixtures.L_ROUTE, EchoConfig())
        var t = 0L
        fun walk(toM: Double) {
            t += 1_000_000_000L
            nav.onInput(NavInput.Motion(MotionUpdate(t, toM, 0.0, 0, false, null, "test")))
        }
        walk(0.0)
        nav.onInput(NavInput.Aligned)
        // Follower odometer reads 10 m for the 20 m opening leg.
        walk(5.0)
        walk(10.0)
        nav.onInput(NavInput.SeeLandmark)
        assertEquals(1, nav.learnedStrideLegs)
        assertTrue(nav.learnedStrideK in 1.5..1.9, "learned K was ${nav.learnedStrideK}")
    }
}
