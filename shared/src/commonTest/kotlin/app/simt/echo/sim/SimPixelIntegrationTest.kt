package app.simt.echo.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SimPixelIntegrationTest {
    @Test
    fun fractionalTextureEdgeCoverageMatchesAreaMixture() {
        val phone = SimPhone()
        val metresPerPixel = 3.0 / phone.focalLengthPx
        var checked = 0
        for (seed in 0L..9L) {
            val world = SimWorld(SimEnvironment.CORRIDOR, phone, seed)
            // End wall is 3 m ahead. The x=0 texture edge moves across this pixel;
            // its y footprint stays strictly within one texture row.
            fun at(phase: Double): Int = world.pixelAt(120, 90, SimPose(-phase * metresPerPixel, 57.0, 1.5, 0.0), 1_000_000_000L)
            val right = at(0.0)
            val left = at(1.0)
            if (abs(left - right) < 40) continue
            checked++
            for (phase in listOf(0.25, 0.5, 0.75)) {
                val expected = left * phase + right * (1.0 - phase)
                assertTrue(abs(at(phase) - expected) <= 2.0, "seed=$seed phase=$phase expected=$expected actual=${at(phase)}")
            }
        }
        assertTrue(checked > 0, "need at least one visibly contrasting texture edge")
    }

    @Test
    fun eightSampleEdgeCoverageConvergesAgainstSixteen() {
        val phone = SimPhone()
        val metresPerPixel = 3.0 / phone.focalLengthPx
        for (seed in 0L..9L) {
            val coarse = SimWorld(SimEnvironment.CORRIDOR, phone, seed, pixelSamplesPerAxis = 8)
            val fine = SimWorld(SimEnvironment.CORRIDOR, phone, seed, pixelSamplesPerAxis = 16)
            fun at(world: SimWorld, phase: Double): Int = world.pixelAt(120, 90, SimPose(-phase * metresPerPixel, 57.0, 1.5, 0.0), 1_000_000_000L)
            val contrast = abs(at(fine, 1.0) - at(fine, 0.0))
            for (phase in listOf(0.1, 1.0 / 3.0, 0.9)) {
                // Midpoint coverage grids differ by at most 1/16 of this edge's contrast.
                assertTrue(abs(at(coarse, phase) - at(fine, phase)) <= contrast / 16.0 + 2.0)
            }
        }
    }
}
