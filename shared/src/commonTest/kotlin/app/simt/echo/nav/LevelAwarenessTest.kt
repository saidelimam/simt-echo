package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.Observation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W12-08 gate: live level awareness on pressure fixtures — stairs, lift,
 * noise — plus the leg-baseline reset and the filter observation seam.
 *
 * Samples arrive at 5 Hz ([sampleNs]); the crossing must hold 1.5 s. Pressures are synthesised through the inverse barometric formula, so a
 * fixture named "+3 m" really climbs 3 m from whatever datum the leg
 * started on. Deterministic: no coroutines, no randomness.
 */
class LevelAwarenessTest {

    private val sampleNs = 200_000_000L

    /** Feeds [pressures] at 5 Hz from [t0Ns]; returns the events with their sample index. */
    private fun LevelAwareness.feed(
        pressures: List<Double?>,
        steps: (Int) -> Int = { 0 },
        t0Ns: Long = 0L,
    ): List<Pair<Int, LevelAwareness.LevelEvent>> =
        pressures.mapIndexedNotNull { i, p ->
            onSample(p, steps(i), t0Ns + i * sampleNs)?.let { i to it }
        }

    /** [n] samples at datum [p0] (the baseline), then a ramp to [toM] and a dwell there. */
    private fun climb(p0: Double, toM: Double, rampSamples: Int = 12, dwellSamples: Int = 12): List<Double> =
        List(5) { p0 } +
            List(rampSamples) { i -> pressureForDh(p0, toM * (i + 1) / rampSamples) } +
            List(dwellSamples) { pressureForDh(p0, toM) }

    @Test
    fun stairsUpFiresOnceWithStairsMode() {
        val awareness = LevelAwareness()
        val events = awareness.feed(climb(1013.25, toM = 3.0), steps = { it })
        assertEquals(1, events.size, "expected exactly one crossing event")
        val event = events.single().second
        assertEquals(LevelAwareness.Mode.STAIRS, event.mode)
        assertTrue(event.deltaHM >= 2.2, "deltaHM was ${event.deltaHM}")
        // Latched: further climbing samples stay silent.
        assertNull(awareness.onSample(pressureForDh(1013.25, 3.3), stepCount = 99, tNs = 99 * sampleNs))
    }

    @Test
    fun liftDownFiresWithLiftMode() {
        val awareness = LevelAwareness()
        val events = awareness.feed(climb(1013.25, toM = -3.0))
        val event = events.single().second
        assertEquals(LevelAwareness.Mode.LIFT, event.mode)
        assertTrue(event.deltaHM <= -2.2, "deltaHM was ${event.deltaHM}")
    }

    @Test
    fun noiseNeverFires() {
        val awareness = LevelAwareness()
        // ±0.4 m jitter with steps throughout: inside the deadband, never armed.
        val jitters = listOf(0.1, -0.3, 0.4, -0.1, 0.2, -0.4, 0.0, 0.3, -0.2, 0.1)
        val events = awareness.feed(List(5) { 1013.25 } + jitters.map { pressureForDh(1013.25, it) }, steps = { it })
        assertTrue(events.isEmpty(), "jitter fired: $events")
    }

    @Test
    fun flatWalkBeforeLiftStillReadsLift() {
        val awareness = LevelAwareness()
        // Ten flat samples with steps, then a stepless 3 m descent.
        val flat = List(10) { 1013.25 }
        val pressures = flat + climb(1013.25, toM = -3.0).drop(5)
        val events = awareness.feed(pressures, steps = { minOf(it, 9) })
        // Steps before the arming deadband do not leak into the window.
        assertEquals(LevelAwareness.Mode.LIFT, events.single().second.mode)
    }

    @Test
    fun baselineResetsPerLegWithNoSeaLevelAssumption() {
        val awareness = LevelAwareness()
        awareness.feed(climb(1013.25, toM = 3.0).take(10), steps = { it })
        awareness.resetLeg()
        // New leg at mountain pressure: its first readings become the datum.
        val mountainP0 = 900.0
        val events = awareness.feed(climb(mountainP0, toM = 3.0), steps = { 100 + it })
        val event = events.single().second
        assertTrue(abs(event.deltaHM - 2.57) < 0.7, "deltaHM was ${event.deltaHM}")
        assertEquals(LevelAwareness.Mode.STAIRS, event.mode)
    }

    @Test
    fun nullPressureNeverFires() {
        val awareness = LevelAwareness()
        assertTrue(awareness.feed(List(10) { null }).isEmpty())
    }

    @Test
    fun aSubSecondPressureTransientNeverFires() {
        val awareness = LevelAwareness()
        // A door slam: +0.4 hPa (~3.3 m) for three samples (0.6 s) on a
        // quiet baseline, then back. Previously this fired and hard-anchored.
        val transient = List(5) { 1013.25 } + List(10) { 1013.25 } +
            List(3) { 1013.25 + 0.4 } + List(20) { 1013.25 }
        assertTrue(awareness.feed(transient).isEmpty())
    }

    @Test
    fun theCrossingMustHoldForOneAndAHalfSeconds() {
        val awareness = LevelAwareness()
        val high = pressureForDh(1013.25, 3.0)
        val pressures = List(5) { 1013.25 } + List(12) { high }
        val events = awareness.feed(pressures)
        // First over-threshold sample is index 5; it holds from t = 5 * 0.2 s.
        // 1.5 s later (index 5 + 8 = 13) the event fires, not before.
        assertEquals(13, events.single().first)
    }

    @Test
    fun aFirstReadingGlitchDoesNotSkewTheBaseline() {
        val awareness = LevelAwareness()
        // First raw sample is 0.3 hPa (~2.5 m) off; the median ignores it.
        val pressures = listOf(1013.55) + List(4) { 1013.25 } + List(30) { 1013.25 }
        assertTrue(awareness.feed(pressures).isEmpty(), "a lone first sample moved the datum")
    }

    @Test
    fun implausiblePressuresAreIgnoredNotInfinite() {
        val awareness = LevelAwareness()
        val pressures = listOf(0.0, Double.NaN, -5.0, 5_000.0, Double.POSITIVE_INFINITY) +
            List(5) { 1013.25 } + listOf(0.0) + List(30) { 1013.25 }
        assertTrue(awareness.feed(pressures).isEmpty())
        // A zero reading mid-leg is skipped, not a divide-by-zero event.
        assertNull(awareness.onSample(0.0, 0, 100 * sampleNs))
    }

    @Test
    fun filterLevelObservationAnchorsAtNode() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L)
        filter.reset(0.0)
        filter.predict(12.0)
        filter.observe(Observation.Level(20.0))
        assertTrue(filter.meanS in 19.0..21.0, "meanS was ${filter.meanS}")
        assertTrue(filter.sigma <= 1.0, "sigma was ${filter.sigma}")
    }
}
