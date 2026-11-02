package app.simt.echo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W13-14 gate: the particle strip is a pure function of measured bytes —
 * no clock, no autonomous motion, so 0 bytes/s is structurally frozen.
 */
class TransferParticlesTest {

    @Test
    fun noBytesMeansNoParticles() {
        assertEquals(emptyList(), transferParticleOffsets(0f))
    }

    @Test
    fun leadSitsExactlyOnMeasuredFraction() {
        val offsets = transferParticleOffsets(0.5f)
        assertEquals(0.5f, offsets.first())
        assertTrue(offsets.size in 2..6)
    }

    @Test
    fun trailNeverRunsAheadOfLead() {
        val offsets = transferParticleOffsets(1f)
        assertEquals(1f, offsets.first())
        assertTrue(offsets.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(offsets.all { it in 0f..1f })
    }

    @Test
    fun sameBytesGiveSameStrip() {
        assertEquals(transferParticleOffsets(0.37f), transferParticleOffsets(0.37f))
    }
}
