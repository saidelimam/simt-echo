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
