package app.simt.echo.platform

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan W13-09 acceptance: pan-law unit test (+ synth determinism and play gate). */
class PanLawTest {

    private fun assertNear(expected: Double, actual: Float, delta: Double = 1e-4) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= delta,
            "expected=$expected actual=$actual",
        )
    }

    @Test
    fun centreIsEqualPower() {
        val gains = StereoPanLaw.gains(0.0)
        assertNear(0.7071, gains.left)
        assertNear(0.7071, gains.right)
    }

    @Test
    fun hardRightPansFullyRight() {
        val gains = StereoPanLaw.gains(PI / 2)
        assertNear(0.0, gains.left)
        assertNear(1.0, gains.right)
    }

    @Test
    fun hardLeftPansFullyLeft() {
        val gains = StereoPanLaw.gains(-PI / 2)
        assertNear(1.0, gains.left)
        assertNear(0.0, gains.right)
    }

    @Test
    fun gainsStayBoundedAndPowerPreserving() {
        val angles = listOf(-PI, -2.0, -0.5, 0.3, 1.0, 2.5, PI)
        for (angle in angles) {
            val gains = StereoPanLaw.gains(angle)
            assertTrue(gains.left in 0f..1f, "left out of range at $angle")
            assertTrue(gains.right in 0f..1f, "right out of range at $angle")
            val power = gains.left * gains.left + gains.right * gains.right
            assertTrue(kotlin.math.abs(1.0 - power) < 1e-5, "power not preserved at $angle: $power")
        }
    }

    @Test
    fun synthRenders80msPerTone() {
        val pcm = EarconSynth.synthesize(HapticPattern.CONFIRM, arrowRadians = 0.0)
        val frames = 80 * EarconSynth.DEFAULT_SAMPLE_RATE_HZ / 1000
        assertEquals(frames, pcm.left.size)
        assertEquals(frames, pcm.right.size)
    }

    @Test
    fun arriveArpeggioIsThreeSixtyMs() {
        val pcm = EarconSynth.synthesize(HapticPattern.ARRIVE, arrowRadians = 0.0)
        val frames = 180 * EarconSynth.DEFAULT_SAMPLE_RATE_HZ / 1000
        assertEquals(frames, pcm.left.size)
        assertEquals(frames, pcm.right.size)
    }

    @Test
    fun synthIsDeterministic() {
        val first = EarconSynth.synthesize(HapticPattern.WARN, arrowRadians = 0.7)
        val second = EarconSynth.synthesize(HapticPattern.WARN, arrowRadians = 0.7)
        assertEquals(first, second)
    }

    @Test
    fun synthPansWithArrow() {
        val right = EarconSynth.synthesize(HapticPattern.CONFIRM, arrowRadians = PI / 2)
        assertTrue(right.left.all { it == 0.toShort() }, "hard right must silence left")
        assertTrue(right.right.any { it != 0.toShort() }, "hard right must sound right")
    }

    @Test
    fun interleavedIsTheStereoPcmBufferLayout() {
        val pcm = EarconPcm(
            left = shortArrayOf(1, 2, 3),
            right = shortArrayOf(-1, -2, -3),
            sampleRateHz = EarconSynth.DEFAULT_SAMPLE_RATE_HZ,
        )
        assertEquals(listOf<Short>(1, -1, 2, -2, 3, -3), pcm.interleaved().toList())
        val arrive = EarconSynth.synthesize(HapticPattern.ARRIVE, arrowRadians = 0.0)
        assertEquals(arrive.left.size * 2, arrive.interleaved().size)
    }

    @Test
    fun tickHasNoEarcon() {
        val pcm = EarconSynth.synthesize(HapticPattern.TICK, arrowRadians = 0.0)
        assertEquals(0, pcm.left.size)
        assertEquals(0, pcm.right.size)
    }

    @Test
    fun gateRequiresOptInOrHeadphonesAndSilence() {
        assertTrue(EarconGate.shouldPlay(explicitOptIn = true, headphonesConnected = false, talkBackSpeaking = false))
        assertTrue(EarconGate.shouldPlay(explicitOptIn = false, headphonesConnected = true, talkBackSpeaking = false))
        // Never over TalkBack speech, even opted in with headphones.
        assertTrue(!EarconGate.shouldPlay(explicitOptIn = true, headphonesConnected = true, talkBackSpeaking = true))
        // Neither opt-in nor headphones: silent.
        assertTrue(!EarconGate.shouldPlay(explicitOptIn = false, headphonesConnected = false, talkBackSpeaking = false))
    }
}
