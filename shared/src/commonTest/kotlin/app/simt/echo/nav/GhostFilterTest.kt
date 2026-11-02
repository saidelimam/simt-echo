package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.nav.ghost.GhostFilter
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

    /**
     * Timing note for the ≤ 1 ms/update budget (plan §4): 256 particles,
     * predict + heading observe per update, measured with a monotonic
     * clock on the host. The assert is a 5× loose trip-wire against a
     * real regression — the recorded numbers below are the evidence, and
     * the on-device gate stays with W14-01 on named hardware.
     */
    @Test
    fun updateTimingNote() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 3L)
        filter.reset(0.0)
        repeat(50) {
            filter.predict(0.05)
            filter.observeHeading(0.0, 1.0)
        }
        val start = TimeSource.Monotonic.markNow()
        var maxNs = 0L
        val updates = 300
        repeat(updates) { i ->
            val tick = TimeSource.Monotonic.markNow()
            filter.predict(0.05)
            filter.observeHeading(if (i % 2 == 0) 0.0 else 0.01, 1.0)
            val took = tick.elapsedNow().inWholeNanoseconds
            if (took > maxNs) maxNs = took
        }
        val totalNs = start.elapsedNow().inWholeNanoseconds
        val avgUs = totalNs.toDouble() / updates / 1_000.0
        println(
            "W12-02 GhostFilter timing (256 particles, host JVM): " +
                "avg=${"%.1f".format(avgUs)} µs/update, " +
                "max=${"%.1f".format(maxNs / 1_000.0)} µs/update over $updates updates",
        )
        assertTrue(avgUs < 5_000.0, "avg update took $avgUs µs, budget trip-wire is 5000 µs")
    }
}
