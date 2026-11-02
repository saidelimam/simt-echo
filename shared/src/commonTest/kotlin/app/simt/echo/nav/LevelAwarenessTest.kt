package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.Observation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W12-08 gate: live level awareness on pressure fixtures — stairs, lift,
 * noise — plus the leg-baseline reset and the filter observation seam.
 *
 * Pressures are synthesised through the inverse barometric formula, so a
 * fixture named "+3 m" really climbs 3 m from whatever datum the leg
 * started on. Deterministic: no coroutines, no randomness.
 */
class LevelAwarenessTest {

    @Test
    fun stairsUpFiresOnceWithStairsMode() {
        val awareness = LevelAwareness()
        var fired: LevelAwareness.LevelEvent? = null
        var fires = 0
        rampDh(p0 = 1013.25, fromM = 0.0, toM = 3.0, samples = 12).forEachIndexed { i, p ->
            awareness.onSample(p, stepCount = i)?.let {
                fired = it
                fires++
            }
        }
        assertEquals(1, fires, "expected exactly one crossing event")
        val event = assertNotNull(fired)
        assertEquals(LevelAwareness.Mode.STAIRS, event.mode)
        assertTrue(event.deltaHM >= 2.2, "deltaHM was ${event.deltaHM}")
        // Latched: further climbing samples stay silent.
        assertNull(awareness.onSample(pressureForDh(1013.25, 3.3), stepCount = 99))
    }

    @Test
    fun liftDownFiresWithLiftMode() {
        val awareness = LevelAwareness()
        var fired: LevelAwareness.LevelEvent? = null
        rampDh(p0 = 1013.25, fromM = 0.0, toM = -3.0, samples = 12).forEach { p ->
            awareness.onSample(p, stepCount = 0)?.let { fired = it }
        }
        val event = assertNotNull(fired, "expected a crossing with no steps")
        assertEquals(LevelAwareness.Mode.LIFT, event.mode)
        assertTrue(event.deltaHM <= -2.2, "deltaHM was ${event.deltaHM}")
    }

    @Test
    fun noiseNeverFires() {
        val awareness = LevelAwareness()
        // ±0.4 m jitter with steps throughout: inside the deadband, never armed.
        val jitters = listOf(0.1, -0.3, 0.4, -0.1, 0.2, -0.4, 0.0, 0.3, -0.2, 0.1)
        jitters.forEachIndexed { i, dh ->
            assertNull(
                awareness.onSample(pressureForDh(1013.25, dh), stepCount = i),
                "jitter sample $i (${dh} m) fired",
            )
        }
    }

    @Test
    fun flatWalkBeforeLiftStillReadsLift() {
        val awareness = LevelAwareness()
        // Five flat metres with steps, then a stepless 3 m descent.
        repeat(5) { i ->
            assertNull(awareness.onSample(pressureForDh(1013.25, 0.0), stepCount = i))
        }
        var fired: LevelAwareness.LevelEvent? = null
        rampDh(p0 = 1013.25, fromM = 0.0, toM = -3.0, samples = 10).forEach { p ->
            awareness.onSample(p, stepCount = 4)?.let { fired = it }
        }
        // Steps before the arming deadband do not leak into the window.
        assertEquals(LevelAwareness.Mode.LIFT, assertNotNull(fired).mode)
    }

    @Test
    fun baselineResetsPerLegWithNoSeaLevelAssumption() {
        val awareness = LevelAwareness()
        rampDh(p0 = 1013.25, fromM = 0.0, toM = 3.0, samples = 8).forEachIndexed { i, p ->
            awareness.onSample(p, stepCount = i)
        }
        awareness.resetLeg()
        // New leg at mountain pressure: the first sample becomes the datum.
        val mountainP0 = 900.0
        assertNull(awareness.onSample(mountainP0, stepCount = 100))
        var fired: LevelAwareness.LevelEvent? = null
        rampDh(p0 = mountainP0, fromM = 0.0, toM = 3.0, samples = 8).forEachIndexed { i, p ->
            awareness.onSample(p, stepCount = 100 + i)?.let { fired = it }
        }
        val event = assertNotNull(fired, "expected a crossing from the new baseline")
        assertTrue(abs(event.deltaHM - 2.57) < 0.5, "deltaHM was ${event.deltaHM}")
        assertEquals(LevelAwareness.Mode.STAIRS, event.mode)
    }

    @Test
    fun nullPressureNeverFires() {
        val awareness = LevelAwareness()
        repeat(10) { i ->
            assertNull(awareness.onSample(null, stepCount = i))
        }
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

    /** Pressures climbing linearly from [fromM] to [toM] above datum [p0]. */
    private fun rampDh(p0: Double, fromM: Double, toM: Double, samples: Int): List<Double> =
        List(samples) { i ->
            val dh = fromM + (toM - fromM) * i / (samples - 1)
            pressureForDh(p0, dh)
        }
}
