package app.simt.echo.nav.ghost

import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.model.ParallaxProfile
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W17-04 gates: DepthSignatureMatch recovers along-route offset within ±1 m on
 * a distinctive corridor (junctions/openings) and emits nothing on a uniform
 * corridor or repeated decoy.
 */
class DepthSignatureTest {

    private fun buildProfile(rhos: DoubleArray): ParallaxProfile {
        val depthBytes = ByteArray(rhos.size) { i -> ParallaxProfiler.quantiseRho(rhos[i]) }
        val trackBytes = ByteArray(rhos.size) { 220.toByte() }
        return ParallaxProfile(
            inverseDepth = Base64.encode(depthBytes),
            trackability = Base64.encode(trackBytes),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
    }

    /** Distinctive 40 m corridor (80 buckets at 0.5 m): narrow corridor with a wide junction opening around 18..22 m. */
    private fun distinctiveCorridorRhos(): DoubleArray = DoubleArray(80) { b ->
        val s = b * ParallaxProfile.STEP_M
        val openingDrop = -0.16 * exp(-((s - 19.5) * (s - 19.5)) / 4.5)
        val alcoveSpike = 0.14 * exp(-((s - 23.0) * (s - 23.0)) / 2.0)
        (0.25 + openingDrop + alcoveSpike).coerceAtLeast(0.05)
    }

    @Test
    fun distinctiveCorridorRecoversOffsetWithinOneMetre() {
        val rhos = distinctiveCorridorRhos()
        val profile = buildProfile(rhos)

        // Follower walked the 10 m window ending at s = 25.0 m (buckets 30..50).
        val trueEndBucket = 50 // 25.0 m
        val m = 20 // 10 m
        val observed = DoubleArray(m) { i ->
            // Scale by 1.08 to verify zero-mean log2 scale invariance.
            rhos[trueEndBucket - m + i] * 1.08
        }

        // Filter belief is off by +2.5 m (meanS = 27.5 m, sigma = 3.0 m).
        val fix = assertNotNull(
            DepthSignatureMatch.match(profile, observed, meanS = 27.5, sigma = 3.0),
            "distinctive corridor must emit a depth signature fix",
        )
        assertTrue(
            abs(fix.estimatedS - 25.0) <= 1.0,
            "expected fix within +-1.0 m of 25.0 m, got ${fix.estimatedS} m",
        )
    }

    @Test
    fun uniformCorridorAndRepeatedDecoyEmitNothing() {
        val uniformRhos = DoubleArray(80) { 0.25 }
        val uniformProfile = buildProfile(uniformRhos)
        val uniformObs = DoubleArray(20) { 0.25 }

        assertNull(
            DepthSignatureMatch.match(uniformProfile, uniformObs, meanS = 20.0, sigma = 3.0),
            "uniform corridor must emit nothing",
        )

        // Repeated identical junction openings at 15 m and 30 m inside a wide search window.
        val decoyRhos = DoubleArray(90) { b ->
            val s = b * 0.5
            val drop1 = -0.15 * exp(-((s - 15.0) * (s - 15.0)) / 3.0)
            val drop2 = -0.15 * exp(-((s - 30.0) * (s - 30.0)) / 3.0)
            (0.25 + drop1 + drop2).coerceAtLeast(0.05)
        }
        val decoyProfile = buildProfile(decoyRhos)
        val decoyObs = DoubleArray(20) { i -> decoyRhos[20 + i] }
        assertNull(
            DepthSignatureMatch.match(decoyProfile, decoyObs, meanS = 25.0, sigma = 8.0),
            "repeated identical depth openings must be rejected by the margin gate",
        )
    }
}
