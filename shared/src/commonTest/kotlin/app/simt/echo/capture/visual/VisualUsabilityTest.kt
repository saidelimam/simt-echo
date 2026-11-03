package app.simt.echo.capture.visual

import app.simt.echo.capture.FrameQuality
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWorld
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualUsabilityTest {
    private val width = 240
    private val height = 180
    private fun texture(): ByteArray {
        val random = Random(23)
        return ByteArray(width * height) { random.nextInt(32, 224).toByte() }
    }

    @Test
    fun blankWallAndOneDimensionalEdgesHaveNoTrackableTexture() {
        val usability = VisualUsability()
        assertTrue(usability.evaluate(ByteArray(width * height) { 120 }, width, height, PoseMode.HELD_UP, 0.0) < 0.2)
        assertEquals(VisualReason.BLANK_WALL, usability.reason)
        val edge = ByteArray(width * height) { if (it % width < width / 2) 32 else 224.toByte() }
        assertEquals(0.0, StructureTensor.minEigenvalue(edge, width, height))
    }

    @Test
    fun texturedSimCorridorIsUsableAndFlatHoldNeverIs() {
        val frame = SimWorld(SimEnvironment.CORRIDOR, seed = 41L).render(SimPose(0.0, 5.0, 1.55, 0.0), 1_000_000_000L)
        val usability = VisualUsability()
        assertTrue(usability.evaluate(frame.luma, width, height, PoseMode.HELD_UP, 0.0) > 0.7, "score=${usability.value01}, texture=${usability.textureEigenvalue}")
        assertEquals(VisualReason.NONE, usability.reason)
        assertEquals(0.0, usability.evaluate(frame.luma, width, height, PoseMode.FLAT, 0.0))
        assertEquals(VisualReason.FLAT, usability.reason)
        for (pose in PoseMode.entries.filter { it != PoseMode.HELD_UP }) {
            assertEquals(0.0, usability.evaluate(frame.luma, width, height, pose, 0.0))
        }
    }

    @Test
    fun factorsReuseFrameQualityAndMultiplyWithoutHiddenBoosts() {
        val plane = texture()
        val usability = VisualUsability()
        val value = usability.evaluate(plane, width, height, PoseMode.HELD_UP, 0.1)
        assertEquals(FrameQuality.exposureFraction(plane), usability.exposureFraction01)
        assertEquals(FrameQuality.stability(0.1), usability.stabilityFactor01)
        assertEquals((FrameQuality.sharpness(plane, width, height) / 256.0).coerceIn(0.0, 1.0), usability.sharpnessFactor01)
        assertEquals(usability.sharpnessFactor01 * usability.exposureFraction01 * (usability.textureEigenvalue / 64.0).coerceIn(0.0, 1.0) * usability.stabilityFactor01, value)
        assertTrue(value > 0.7)
    }

    @Test
    fun darkGlareBlurAndMalformedInputsAreHonest() {
        val usability = VisualUsability()
        assertEquals(0.0, usability.evaluate(ByteArray(width * height), width, height, PoseMode.HELD_UP, 0.0))
        assertEquals(VisualReason.DARK, usability.reason)
        usability.evaluate(ByteArray(width * height) { -1 }, width, height, PoseMode.HELD_UP, 0.0)
        assertEquals(VisualReason.GLARE, usability.reason)
        assertTrue(usability.evaluate(texture(), width, height, PoseMode.HELD_UP, 6.0) < 0.2)
        assertEquals(VisualReason.BLUR, usability.reason)
        assertEquals(0.0, usability.evaluate(ByteArray(1), width, height, PoseMode.HELD_UP, 0.0))
        assertEquals(VisualReason.UNKNOWN, usability.reason)
        assertEquals(0.0, usability.evaluate(texture(), width, height, PoseMode.HELD_UP, Double.NaN))
    }
}
