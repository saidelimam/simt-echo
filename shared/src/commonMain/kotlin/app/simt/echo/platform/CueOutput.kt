package app.simt.echo.platform

import androidx.compose.runtime.Composable

/**
 * Non-haptic cue outputs for navigation (plan W13-09): the opt-in earcon
 * and the eyes-free announcement channel. Haptics stay on [Haptics]; this
 * seam is everything a walker hears.
 */
interface CueOutput {
    /**
     * True while a screen reader is active: the walker may not be looking
     * at the screen, so only decision points are announced
     * ([EyesFreeFilter]).
     */
    val eyesFree: Boolean

    /**
     * Plays the earcon for [pattern], panned by [arrowRad] ([StereoPanLaw]).
     * The implementation owns the [EarconGate]: it is opt-in, and silent
     * over a screen reader.
     */
    fun earcon(pattern: HapticPattern, arrowRad: Double)

    /** Speaks [text] on the platform announcement channel. */
    fun announce(text: String)
}

/** No sonic or spoken output: the default, and the iOS actual until it is ported. */
object NoCueOutput : CueOutput {
    override val eyesFree: Boolean = false
    override fun earcon(pattern: HapticPattern, arrowRad: Double) = Unit
    override fun announce(text: String) = Unit
}

/**
 * The platform's watch nav mirror sink (plan W13-12): hand each
 * [app.simt.echo.nav.NavWearState] to it; the sender owns the ≤ 1 Hz gate.
 * No-op where there is no watch (iOS).
 */
@Composable
expect fun rememberWearNavSink(): (app.simt.echo.nav.NavWearState) -> Unit

/** The platform's [CueOutput]; [earconsEnabled] is the Settings opt-in (default off). */
@Composable
expect fun rememberCueOutput(earconsEnabled: Boolean): CueOutput
