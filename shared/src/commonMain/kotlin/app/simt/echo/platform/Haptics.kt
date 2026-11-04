package app.simt.echo.platform

/** Named haptic cues; the Android actual maps each to a waveform (plan §8). */
enum class HapticPattern {
    TICK,
    CONFIRM,
    LEFT,
    RIGHT,
    U_TURN,
    WARN,
    ARRIVE,
}

/** Device haptics seam. First consumed by navigation, slice W2-06. */
interface Haptics {
    fun play(pattern: HapticPattern)
}

/**
 * No cues at all: the honest default for a host with no cue surface
 * (previews, tests). Production wires the platform actual instead, so a
 * missing wire is never mistaken for a design choice.
 */
object SilentHaptics : Haptics {
    override fun play(pattern: HapticPattern) = Unit
}
