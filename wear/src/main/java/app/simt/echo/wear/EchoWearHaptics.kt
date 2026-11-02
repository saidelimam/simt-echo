package app.simt.echo.wear

/**
 * Watch-side haptic vocabulary (plan §8.4, verbatim).
 *
 * Pure mapping from the wire name ([EchoHapticListenerService] receives the
 * `HapticPattern` name the phone's `WearHapticCodec` encoded) to the
 * `VibrationEffect.createWaveform` timings. Unknown names yield null so a
 * newer phone never crashes an older watch. Enclosed ECHO code: the
 * vocabulary idea follows `simt-app`'s wear `WearHaptics`, the timings are
 * plan §8.4, and nothing is imported or linked.
 */
object EchoWearHaptics {

    /**
     * Waveform timings for [patternName], or null when the name is not a
     * known pattern.
     */
    fun timings(patternName: String): LongArray? = when (patternName) {
        "TICK" -> longArrayOf(0, 30)
        "CONFIRM" -> longArrayOf(0, 40, 80, 40)
        "LEFT" -> longArrayOf(0, 60, 120, 60)
        "RIGHT" -> longArrayOf(0, 60, 120, 60, 120, 60)
        "U_TURN" -> longArrayOf(0, 300, 150, 300)
        "WARN" -> longArrayOf(0, 400)
        "ARRIVE" -> longArrayOf(0, 200, 120, 60, 120, 200)
        else -> null
    }
}
