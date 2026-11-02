package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.nav.ghost.FixGate
import app.simt.echo.nav.ghost.GhostFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W12-04 review: a magnetic fix is a soft nudge, once per new piece of
 * evidence. A false fix must not act as a hard anchor, and the 0.5 m
 * matcher must not feed the same 95 %-overlapping window to the filter
 * twenty times.
 */
class FixGateTest {

    @Test
    fun falseMagneticFixBendsAWideBeliefButCannotSnapIt() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 4L)
        filter.respreadUniform()
        val sigmaBefore = filter.sigma
        // The walker is lost somewhere on the 45 m route; a wrong-but-
        // plausible fix claims s = 40. With the old 1e-4 floor that was a
        // 10^4 : 1 reweight and the cloud collapsed onto 40 m.
        filter.observeMagnetic(40.0)
        assertTrue(filter.meanS < 35.0, "false fix snapped the mean to ${filter.meanS}")
        assertTrue(
            filter.sigma > 0.6 * sigmaBefore,
            "false fix collapsed σ from $sigmaBefore to ${filter.sigma}",
        )
    }

    @Test
    fun theSameWindowIsNotCountedTwiceAsTheWalkerAdvancesHalfAMetre() {
        val gate = FixGate()
        assertTrue(gate.accept(odoM = 10.0, fixS = 10.0))
        // 19 further 0.5 m buckets re-find the same place (estimate
        // carried forward exactly): suppressed.
        for (k in 1..9) assertFalse(gate.accept(10.0 + 0.5 * k, 10.0 + 0.5 * k), "bucket $k")
        // 5 m on the window no longer overlaps: a new observation.
        assertTrue(gate.accept(odoM = 15.0, fixS = 15.0))
    }

    @Test
    fun aFixThatMovedTheEstimateIsNewInformationEvenWithinFiveMetres() {
        val gate = FixGate()
        assertTrue(gate.accept(odoM = 10.0, fixS = 10.0))
        // Walked 1 m, but the fix now says 13 m (carried forward: 11 m).
        assertTrue(gate.accept(odoM = 11.0, fixS = 13.0))
        // And a small 1 m disagreement stays suppressed.
        assertFalse(gate.accept(odoM = 12.0, fixS = 15.0))
    }

    @Test
    fun nonFiniteFixesAreRejectedAndDoNotArmTheGate() {
        val gate = FixGate()
        assertFalse(gate.accept(5.0, Double.NaN))
        assertFalse(gate.accept(Double.NaN, 5.0))
        assertTrue(gate.accept(5.0, 5.0))
        assertEquals(false, gate.accept(5.5, 5.5))
    }
}
