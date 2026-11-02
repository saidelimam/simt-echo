package app.simt.echo.ui.atmosphere

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import kotlin.time.TimeSource

// Ripple motif (docs/research/luminous-freeze.md §4): a ring leaves the
// point of a real event (a primary-command press today; steps and
// confirmations in later slices) and crosses the echo field, bending the
// aurora where it passes. Honest light (§1.1): nothing emits a ripple
// without an event.
//
// The state is plain arrays read in the draw phase — emitting never
// recomposes; the field's frame clock picks it up on the next frame.

/** Ripple lifetime: origin → 0.6 × short edge (freeze §4). */
const val ECHO_RIPPLE_DURATION_MS = 900L
const val ECHO_RIPPLE_REACH_OF_SHORT_EDGE = 0.6f
const val ECHO_RIPPLE_START_ALPHA = 0.45f

/** At most this many ripples are alive; a new one replaces the oldest. */
const val ECHO_RIPPLE_SLOTS = 4

/** One ripple at one moment: ring radius (fraction of reach) and alpha. */
data class RippleFrame(val reach: Float, val alpha: Float)

/**
 * Ripple shape over its life: radius eases out (fast start, settling),
 * alpha fades linearly to zero. Null once the ripple has finished.
 */
fun rippleFrame(ageMs: Long): RippleFrame? {
    if (ageMs < 0 || ageMs >= ECHO_RIPPLE_DURATION_MS) return null
    val t = ageMs.toFloat() / ECHO_RIPPLE_DURATION_MS
    val eased = 1f - (1f - t) * (1f - t) * (1f - t)
    return RippleFrame(reach = eased, alpha = ECHO_RIPPLE_START_ALPHA * (1f - t))
}

/** Ripples alive on one echo field. Shared by the field and the emitters. */
class EchoRippleState(
    private val clock: () -> Long = defaultClock(),
) {
    private val xs = FloatArray(ECHO_RIPPLE_SLOTS)
    private val ys = FloatArray(ECHO_RIPPLE_SLOTS)
    private val startMs = LongArray(ECHO_RIPPLE_SLOTS) { Long.MIN_VALUE }
    private var next = 0

    /** Milliseconds on the ripple clock; the field's frame loop reads it too. */
    fun nowMs(): Long = clock()

    /** Starts a ripple at [center] (field = root coordinates). */
    fun emit(center: Offset) {
        xs[next] = center.x
        ys[next] = center.y
        startMs[next] = clock()
        next = (next + 1) % ECHO_RIPPLE_SLOTS
    }

    /** True while any ripple is still travelling at [nowMs]. */
    fun anyAlive(nowMs: Long): Boolean =
        (0 until ECHO_RIPPLE_SLOTS).any { rippleFrame(nowMs - startMs[it]) != null }

    /**
     * Writes the shader uniform layout into [out] (4 floats per slot:
     * x, y, radius px, alpha); finished slots get alpha 0.
     */
    fun writeUniforms(nowMs: Long, shortEdgePx: Float, out: FloatArray) {
        for (i in 0 until ECHO_RIPPLE_SLOTS) {
            val frame = rippleFrame(nowMs - startMs[i])
            out[i * 4] = xs[i]
            out[i * 4 + 1] = ys[i]
            out[i * 4 + 2] = (frame?.reach ?: 0f) * ECHO_RIPPLE_REACH_OF_SHORT_EDGE * shortEdgePx
            out[i * 4 + 3] = frame?.alpha ?: 0f
        }
    }
}

private fun defaultClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/** The shell's ripple state; null outside a shell (emitters then do nothing). */
val LocalEchoRipples = staticCompositionLocalOf<EchoRippleState?> { null }
