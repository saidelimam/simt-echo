package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.capture.MagProfiler
import app.simt.echo.capture.magProfileFromSamples
import app.simt.echo.capture.magneticMagnitude
import app.simt.echo.capture.magneticVertical
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.MagProfile
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.MagneticMatch
import app.simt.echo.nav.ghost.Observation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W12-04 gate: magnetic signature capture + subsequence-DTW offset
 * observation on deterministic synthetic corridors.
 *
 * - A steel-anomaly corridor recovers a drifted belief to ±1 m, and the
 *   same shape with a phone-like DC offset recovers identically
 *   (zero-mean normalisation ⇒ phone-independence).
 * - A flat corridor emits nothing (std gate); the std boundary is exact
 *   (3.0 µT ⇒ silence, 3.1 µT ⇒ fix).
 * - A corrupted observation and an ambiguous repeated anomaly emit
 *   nothing (cost gate, margin gate).
 * - Degenerate inputs (short query, empty profile, NaN) emit nothing.
 * - The profiler buckets |B| + gravity-aligned vertical, interpolates
 *   gaps, quantises µT×10, and returns null when empty.
 * - [Observation.Magnetic] pulls the Ghost posterior toward the fix.
 *
 * Device trace on one real corridor: owed (W14 field work) — no device
 * claim is made here; every assertion below is host-synthetic.
 */
class MagneticMatchTest {

    // Synthetic corridor: 60 m, 0.5 m buckets, background 48/40 µT with
    // Gaussian steel anomalies (centre m, |B| amp, vertical amp).
    private fun steelCorridor(
        anomalies: List<Triple<Double, Double, Double>> = listOf(
            Triple(18.0, 14.0, 7.0),
            Triple(26.0, 14.0, -9.0),
            Triple(44.0, -10.0, 8.0),
        ),
    ): MagProfile {
        val buckets = 120
        val mag = DoubleArray(buckets) { i -> fieldAt(i * 0.5, 48.0, anomalies) { it.second } }
        val vert = DoubleArray(buckets) { i -> fieldAt(i * 0.5, 40.0, anomalies) { it.third } }
        return MagProfile(
            magnitude = mag.map { (it * 10.0).roundToInt().toShort() },
            vertical = vert.map { (it * 10.0).roundToInt().toShort() },
        )
    }

    private fun fieldAt(
        s: Double,
        base: Double,
        anomalies: List<Triple<Double, Double, Double>>,
        amp: (Triple<Double, Double, Double>) -> Double,
    ): Double {
        var v = base
        for (a in anomalies) {
            val d = s - a.first
            v += amp(a) * exp(-d * d / (2.0 * 0.75 * 0.75))
        }
        return v
    }

    /** Deterministic sensor noise, ±[amp] µT. */
    private fun noise(i: Int, amp: Double, phase: Double = 1.7): Double =
        amp * sin(i * 12.9 + phase)

    /** Observed query: profile buckets [startBucket, startBucket+20) + noise + DC offset. */
    private fun observe(
        route: MagProfile,
        startBucket: Int,
        noiseAmp: Double = 0.4,
        dcMag: Double = 0.0,
        dcVert: Double = 0.0,
    ): Pair<DoubleArray, DoubleArray> {
        val mag = DoubleArray(20) { i ->
            route.magnitude[startBucket + i] / 10.0 + noise(i, noiseAmp) + dcMag
        }
        val vert = DoubleArray(20) { i ->
            route.vertical[startBucket + i] / 10.0 + noise(i, noiseAmp, phase = 4.2) + dcVert
        }
        return mag to vert
    }

    @Test
    fun steelAnomaliesRecoverOffsetWithinOneMetre() {
        val route = steelCorridor()
        // Walker truly at s ∈ [13, 23]; belief drifted +3 m with σ = 2 m.
        val (obsMag, obsVert) = observe(route, startBucket = 26)
        val fix = MagneticMatch.match(route, obsMag, obsVert, meanS = 26.0, sigma = 2.0)
        assertNotNull(fix, "expected a fix on the steel corridor")
        assertTrue(
            abs(fix.estimatedS - 23.0) <= 1.0,
            "offset recovered to ${fix.estimatedS}, true 23.0",
        )
    }

    @Test
    fun dcOffsetDoesNotMoveTheFix() {
        val route = steelCorridor()
        val (plainMag, plainVert) = observe(route, startBucket = 26)
        // A different phone's hard-iron bias: +15/-5 µT on both channels.
        val (biasedMag, biasedVert) = observe(route, startBucket = 26, dcMag = 15.0, dcVert = -5.0)
        val plain = assertNotNull(MagneticMatch.match(route, plainMag, plainVert, 26.0, 2.0))
        val biased = assertNotNull(MagneticMatch.match(route, biasedMag, biasedVert, 26.0, 2.0))
        assertEquals(plain.estimatedS, biased.estimatedS)
    }

    @Test
    fun flatCorridorEmitsNothing() {
        val flat = MagProfile(
            magnitude = List(120) { i -> ((48.0 + noise(i, 0.3)) * 10.0).roundToInt().toShort() },
            vertical = List(120) { i -> ((40.0 + noise(i, 0.3, 0.4)) * 10.0).roundToInt().toShort() },
        )
        val (obsMag, obsVert) = observe(flat, startBucket = 40)
        assertNull(
            MagneticMatch.match(flat, obsMag, obsVert, meanS = 23.0, sigma = 2.0),
            "flat corridor must stay silent",
        )
    }

    @Test
    fun stdGateBoundaryIsStrict() {
        // Population std of half-45/half-51 is exactly 3.0 µT.
        fun stepProfile(high: Double): MagProfile = MagProfile(
            magnitude = List(120) { i -> ((if (i < 60) 45.0 else high) * 10.0).roundToInt().toShort() },
            vertical = List(120) { 400.toShort() },
        )
        // Search window covers the whole profile: mean 30, σ 10.
        val atGate = stepProfile(51.0)
        val (obsAt, obsAtV) = observe(atGate, startBucket = 50)
        assertNull(
            MagneticMatch.match(atGate, obsAt, obsAtV, meanS = 30.0, sigma = 10.0),
            "std == 3.0 µT must stay silent (strict >)",
        )
        // Just above the gate the crossing step matches.
        val aboveGate = stepProfile(51.2)
        val (obsAbove, obsAboveV) = observe(aboveGate, startBucket = 50, noiseAmp = 0.2)
        val fix = MagneticMatch.match(aboveGate, obsAbove, obsAboveV, meanS = 30.0, sigma = 10.0)
        assertNotNull(fix, "std == 3.1 µT with a matching step must fix")
        assertTrue(abs(fix.estimatedS - 35.0) <= 1.0, "step fix at ${fix.estimatedS}, true 35.0")
    }

    @Test
    fun corruptedObservationBeatsNoCostGate() {
        val route = steelCorridor()
        // Deterministic pseudo-random corruption, ±8 µT: the true alignment
        // still wins the argmin, but its residual must fail the cost gate.
        var x = 12345L
        fun rand(): Double {
            x = (x * 1103515245L + 12345L) and 0x7fffffffL
            return (x.toDouble() / 0x7fffffff * 2.0 - 1.0) * 8.0
        }
        val mag = DoubleArray(20) { i -> route.magnitude[26 + i] / 10.0 + rand() }
        val vert = DoubleArray(20) { i -> route.vertical[26 + i] / 10.0 + rand() }
        assertNull(
            MagneticMatch.match(route, mag, vert, meanS = 26.0, sigma = 2.0),
            "corrupted observation must fail the cost gate",
        )
    }

    @Test
    fun repeatedAnomalyIsAmbiguous() {
        // Two identical steel doors: either alignment explains the query.
        val route = steelCorridor(
            anomalies = listOf(Triple(18.0, 14.0, 7.0), Triple(32.0, 14.0, 7.0)),
        )
        val (obsMag, obsVert) = observe(route, startBucket = 26)
        assertNull(
            MagneticMatch.match(route, obsMag, obsVert, meanS = 26.0, sigma = 4.0),
            "a repeated anomaly must stay silent, not guess",
        )
    }

    @Test
    fun degenerateInputsEmitNothing() {
        val route = steelCorridor()
        val (obsMag, obsVert) = observe(route, startBucket = 26)
        // Short query (not the full last 10 m).
        assertNull(MagneticMatch.match(route, obsMag.copyOf(19), obsVert.copyOf(19), 26.0, 2.0))
        // Empty route profile.
        assertNull(MagneticMatch.match(MagProfile(), obsMag, obsVert, 26.0, 2.0))
        // Non-finite sample.
        val nan = obsMag.copyOf().also { it[5] = Double.NaN }
        assertNull(MagneticMatch.match(route, nan, obsVert, 26.0, 2.0))
        // Search window shorter than the query (tiny profile).
        val tiny = MagProfile(
            magnitude = List(10) { 480.toShort() },
            vertical = List(10) { 400.toShort() },
        )
        assertNull(MagneticMatch.match(tiny, obsMag, obsVert, 2.0, 1.0))
    }

    @Test
    fun profilerBucketsMagnitudeAndVertical() {
        val profiler = MagProfiler()
        profiler.onSample(0.1, 48.0, 40.0)
        profiler.onSample(0.2, 50.0, 42.0)
        profiler.onSample(0.6, 60.0, 30.0)
        assertEquals(2, profiler.bucketCount)
        val (m0, v0) = assertNotNull(profiler.meanAt(0))
        assertEquals(49.0, m0, 1e-9)
        assertEquals(41.0, v0, 1e-9)
        val (m1, v1) = assertNotNull(profiler.meanAt(1))
        assertEquals(60.0, m1, 1e-9)
        assertEquals(30.0, v1, 1e-9)
    }

    @Test
    fun verticalProjectionFollowsTheRotationVector() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(40.0, magneticVertical(30.0, 20.0, 40.0, identity), 1e-9)
        // World-up reads device-x: turning the phone moves axes, not the projection.
        val upIsX = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        assertEquals(30.0, magneticVertical(30.0, 20.0, 40.0, upIsX), 1e-9)
        assertEquals(50.0, magneticMagnitude(30.0, 40.0, 0.0), 1e-9)
    }

    @Test
    fun profilerInterpolatesGapsAndQuantises() {
        val profiler = MagProfiler()
        profiler.onSample(0.1, 48.0, 40.0)
        profiler.onSample(1.1, 50.0, 44.0) // bucket 2; bucket 1 is a gap
        val profile = assertNotNull(profiler.toMagProfile())
        assertEquals(3, profile.magnitude.size)
        assertEquals(3, profile.vertical.size)
        assertEquals(480.toShort(), profile.magnitude[0])
        assertEquals(490.toShort(), profile.magnitude[1])
        assertEquals(500.toShort(), profile.magnitude[2])
        assertEquals(420.toShort(), profile.vertical[1])
    }

    @Test
    fun emptyProfilerAndMaglessSamplesYieldNull() {
        assertNull(MagProfiler().toMagProfile())
        val samples = listOf(
            CaptureSample(tNs = 1L, d = 0.0, yaw = 0.0, p = null),
            CaptureSample(tNs = 2L, d = 0.5, yaw = 0.0, p = 1013.0),
        )
        assertNull(magProfileFromSamples(samples))
        val withMag = samples.mapIndexed { i, s ->
            s.copy(mag = 48.0 + i, magV = 40.0 + i)
        }
        val profile = assertNotNull(magProfileFromSamples(withMag))
        assertEquals(2, profile.magnitude.size)
        assertEquals(480.toShort(), profile.magnitude[0])
        assertEquals(490.toShort(), profile.magnitude[1])
    }

    @Test
    fun magneticObservationPullsThePosteriorToTheFix() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L)
        filter.reset(0.0)
        filter.predict(10.0)
        val before = filter.meanS
        assertTrue(before in 8.0..12.0, "sanity: cloud near 10 m, was $before")
        filter.observe(Observation.Magnetic(20.0))
        val after = filter.meanS
        assertTrue(
            abs(after - 20.0) < abs(before - 20.0),
            "fix at 20 m must pull the mean (was $before, now $after)",
        )
        // Non-finite fixes are a no-op, never a collapse.
        filter.observe(Observation.Magnetic(Double.NaN))
        assertEquals(after, filter.meanS)
    }

    @Test
    fun dtwToleratesLocalWarp() {
        // Same anomaly shape walked 10% slower: DTW still aligns.
        val route = steelCorridor()
        val ref = DoubleArray(20) { i -> route.magnitude[26 + i] / 10.0 }
        val refV = DoubleArray(20) { i -> route.vertical[26 + i] / 10.0 }
        // Stretch by repeating every 10th sample, then truncate: a warp the
        // 10% band must absorb.
        val stretched = DoubleArray(20) { i -> ref[(i * 10 / 11).coerceAtMost(19)] }
        val stretchedV = DoubleArray(20) { i -> refV[(i * 10 / 11).coerceAtMost(19)] }
        val (obsMag, obsVert) = observe(route, startBucket = 26, noiseAmp = 0.2)
        val direct = MagneticMatch.dtwMeanCost(
            zeroMeanCopy(obsMag), zeroMeanCopy(obsVert),
            zeroMeanCopy(ref), zeroMeanCopy(refV),
        )
        val warped = MagneticMatch.dtwMeanCost(
            zeroMeanCopy(stretched), zeroMeanCopy(stretchedV),
            zeroMeanCopy(ref), zeroMeanCopy(refV),
        )
        assertTrue(warped < MagneticMatch.COST_GATE, "warped cost $warped must beat the gate")
        assertTrue(warped < 25.0 * direct + 1.0, "warp $warped vs direct $direct")
    }

    private fun zeroMeanCopy(series: DoubleArray): DoubleArray {
        var sum = 0.0
        for (v in series) sum += v
        val mean = sum / series.size
        return DoubleArray(series.size) { i -> series[i] - mean }
    }

    @Test
    fun windowStdHelperSanity() {
        // Guards the std-gate arithmetic the silence tests rely on: the
        // half-45/half-51 window really has std exactly 3.0 µT.
        val window = DoubleArray(120) { i -> if (i < 60) 45.0 else 51.0 }
        var mean = 0.0
        for (v in window) mean += v
        mean /= window.size
        var variance = 0.0
        for (v in window) variance += (v - mean) * (v - mean)
        assertEquals(3.0, sqrt(variance / window.size), 1e-9)
    }
}
