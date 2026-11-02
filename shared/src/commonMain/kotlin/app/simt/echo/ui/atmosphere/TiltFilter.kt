package app.simt.echo.ui.atmosphere

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * Low-passes raw gravity tilt and publishes it only when it has moved enough
 * to be seen. A phone lying still still reports sensor noise every few
 * milliseconds; publishing each sample would redraw the full-screen field
 * (and re-transform the starfield) at sensor rate for no visible change.
 *
 * [publishStep] is in tilt units (-1..1). 0.02 moves the aurora under a pixel
 * on a 1080 px wide screen, so nothing visible is lost.
 */
internal class TiltFilter(
    private val alpha: Float = TILT_ALPHA,
    private val publishStep: Float = TILT_PUBLISH_STEP,
) {
    private var filteredX = 0f
    private var filteredY = 0f
    private var published = Offset.Zero

    /** Feeds one sample; returns the new tilt to publish, or null when it has not moved enough. */
    fun update(x: Float, y: Float): Offset? {
        filteredX += alpha * (x - filteredX)
        filteredY += alpha * (y - filteredY)
        if (abs(filteredX - published.x) < publishStep && abs(filteredY - published.y) < publishStep) return null
        published = Offset(filteredX, filteredY)
        return published
    }

    companion object {
        /** Low-pass factor, about 2 Hz at the UI sensor rate. */
        const val TILT_ALPHA = 0.12f

        /** Smallest published change, in tilt units. */
        const val TILT_PUBLISH_STEP = 0.02f
    }
}
