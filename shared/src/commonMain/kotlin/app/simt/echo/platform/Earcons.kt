package app.simt.echo.platform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sonic language (plan W13-09, Luminous freeze §8): opt-in earcons only.
 *
 * Rules, enforced by the caller through [EarconGate]:
 * - 80 ms synthesised sine tones, no assets; confirm C6, turn E6, warn A4,
 *   arrive C6–E6–G6 arpeggio (60 ms each).
 * - Stereo pan = sin(arrow angle).
 * - Only with headphones or explicit opt-in; never over TalkBack speech;
 *   volume follows the media stream; silent while walking straight (the
 *   caller synthesises on cue events only — there is no ambient loop).
 *
 * Everything here is pure and deterministic: synthesis writes PCM arrays,
 * never touches audio hardware, so it runs in commonTest.
 */
object StereoPanLaw {
    data class Gains(val left: Float, val right: Float)

    /**
     * Equal-power gains for [arrowRadians] (arrow angle, 0 = ahead).
     * pan = sin(angle): −1 hard left, 0 centre, +1 hard right.
     * Power is preserved: left² + right² = 1.
     */
    fun gains(arrowRadians: Double): Gains {
        val pan = sin(arrowRadians).coerceIn(-1.0, 1.0)
        val theta = (pan + 1.0) * PI / 4.0
        return Gains(left = cos(theta).toFloat(), right = sin(theta).toFloat())
    }
}

/** Freeze §8 pitches. */
object EarconPitch {
    const val CONFIRM_C6 = 1046.50
    const val TURN_E6 = 1318.51
    const val WARN_A4 = 440.0
    const val ARRIVE_C6 = 1046.50
    const val ARRIVE_E6 = 1318.51
    const val ARRIVE_G6 = 1567.98

    /** Cue → tone sequence; arrive is a 3-note arpeggio, 60 ms each. */
    fun sequence(pattern: HapticPattern): List<Tone> = when (pattern) {
        HapticPattern.CONFIRM -> listOf(Tone(CONFIRM_C6, 80))
        HapticPattern.LEFT,
        HapticPattern.RIGHT,
        HapticPattern.U_TURN,
        -> listOf(Tone(TURN_E6, 80))
        HapticPattern.WARN -> listOf(Tone(WARN_A4, 80))
        HapticPattern.ARRIVE -> listOf(
            Tone(ARRIVE_C6, 60),
            Tone(ARRIVE_E6, 60),
            Tone(ARRIVE_G6, 60),
        )
        HapticPattern.TICK -> emptyList()
    }

    data class Tone(val frequencyHz: Double, val durationMs: Int)
}

/** Stereo PCM result; 16-bit mono-per-channel samples at [sampleRateHz]. */
data class EarconPcm(
    val left: ShortArray,
    val right: ShortArray,
    val sampleRateHz: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is EarconPcm &&
            sampleRateHz == other.sampleRateHz &&
            left.contentEquals(other.left) &&
            right.contentEquals(other.right)
    override fun hashCode(): Int =
        31 * (31 * sampleRateHz + left.contentHashCode()) + right.contentHashCode()
}

/**
 * Deterministic sine synth stub. Renders [EarconPitch.sequence] for
 * [pattern] panned by [StereoPanLaw.gains] for [arrowRadians]. Peak
 * amplitude is 0.5 full-scale with 5 ms linear edge fades to avoid clicks.
 */
object EarconSynth {
    const val DEFAULT_SAMPLE_RATE_HZ = 22050

    fun synthesize(
        pattern: HapticPattern,
        arrowRadians: Double,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    ): EarconPcm {
        val gains = StereoPanLaw.gains(arrowRadians)
        val left = mutableListOf<Short>()
        val right = mutableListOf<Short>()
        for (tone in EarconPitch.sequence(pattern)) {
            val frames = (tone.durationMs * sampleRateHz / 1000)
            val fadeFrames = (5 * sampleRateHz / 1000).coerceAtMost(frames / 2)
            for (i in 0 until frames) {
                val edge = minOf(i + 1, frames - i, fadeFrames).toFloat() / fadeFrames
                val s = (sin(2.0 * PI * tone.frequencyHz * i / sampleRateHz) * 0.5 * edge)
                left += (s * gains.left * Short.MAX_VALUE).toInt().toShort()
                right += (s * gains.right * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return EarconPcm(left.toShortArray(), right.toShortArray(), sampleRateHz)
    }
}

/**
 * TalkBack-aware play gate. The platform supplies [talkBackSpeaking] from
 * its accessibility hook (Android: `AccessibilityManager` speech state —
 * see `isScreenReaderActive`); the gate itself stays pure so the rule
 * "never over TalkBack speech" is unit-tested, not eyeballed.
 */
object EarconGate {
    /**
     * True only when output is allowed: headphones plugged or the walker
     * explicitly opted in, AND no screen-reader speech in progress.
     */
    fun shouldPlay(
        explicitOptIn: Boolean,
        headphonesConnected: Boolean,
        talkBackSpeaking: Boolean,
    ): Boolean = (explicitOptIn || headphonesConnected) && !talkBackSpeaking
}
