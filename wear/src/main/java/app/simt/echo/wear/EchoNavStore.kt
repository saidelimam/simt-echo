package app.simt.echo.wear

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Latest nav snapshot (plan W13-12), written by [EchoHapticListenerService]
 * on the background callback and observed by [MainActivity].
 *
 * Last-write-wins, plus what a screen needs to be honest about it:
 * - listeners, so a visible activity re-renders when a snapshot lands
 *   instead of only reading the store in `onResume`;
 * - the receive time, so a snapshot that stops updating can be shown as
 *   paused ([EchoNavPresentation.isPaused]);
 * - the phone's closing `ENDED` snapshot clears the store, so a finished
 *   walk never leaves an arrow on the watch.
 *
 * Callbacks arrive on whatever thread called [update]; listeners must hop
 * to the UI thread themselves.
 */
object EchoNavStore {

    /** Phase the phone sends when the walk ends without arriving. */
    const val PHASE_ENDED = "ENDED"

    fun interface Listener {
        fun onNavChanged()
    }

    private class Stamped(val state: EchoNavState, val receivedAtMs: Long)

    @Volatile
    private var stamped: Stamped? = null

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Latest snapshot, or null when nothing is active on the phone. */
    val latest: EchoNavState? get() = stamped?.state

    /** Monotonic ms ([nowMs] clock) when [latest] was received, or null. */
    val latestReceivedAtMs: Long? get() = stamped?.receivedAtMs

    fun update(state: EchoNavState, nowMs: Long = nowMs()) {
        stamped = if (state.phase == PHASE_ENDED) null else Stamped(state, nowMs)
        listeners.forEach { it.onNavChanged() }
    }

    fun clear() {
        stamped = null
        listeners.forEach { it.onNavChanged() }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Monotonic milliseconds, the clock for [latestReceivedAtMs]. */
    fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

/** The watch's labels for the phone's phase names (strings live in `res/`). */
enum class EchoNavPhaseLabel {
    ALIGNING,
    WALKING,
    APPROACHING,
    TURNING,
    CONFIRMING,
    UNSURE,
    OFF_ROUTE,
    ARRIVED,
    FINDING,
    PAUSED,
}

/** Pure rules for what the watch shows; no Android types, so they unit-test on the JVM. */
object EchoNavPresentation {

    /** A snapshot older than this, mid-walk, reads as paused. */
    const val STALE_AFTER_MS = 5_000L

    /**
     * Label for the phone's [phase] name, or null for a phase this watch
     * does not know (a newer phone): show the distance alone rather than
     * a raw enum name.
     */
    fun label(phase: String): EchoNavPhaseLabel? = when (phase) {
        "ALIGN" -> EchoNavPhaseLabel.ALIGNING
        "WALK" -> EchoNavPhaseLabel.WALKING
        "APPROACH" -> EchoNavPhaseLabel.APPROACHING
        "TURNING" -> EchoNavPhaseLabel.TURNING
        "CONFIRM" -> EchoNavPhaseLabel.CONFIRMING
        "UNCERTAIN" -> EchoNavPhaseLabel.UNSURE
        "OFF_ROUTE" -> EchoNavPhaseLabel.OFF_ROUTE
        "ARRIVED" -> EchoNavPhaseLabel.ARRIVED
        "RELOCKING" -> EchoNavPhaseLabel.FINDING
        else -> null
    }

    /**
     * True when the walk is on but the phone has gone quiet: the snapshot is
     * older than [STALE_AFTER_MS]. Arrival is terminal and never goes stale.
     */
    fun isPaused(state: EchoNavState, receivedAtMs: Long, nowMs: Long): Boolean =
        state.phase != "ARRIVED" && nowMs - receivedAtMs > STALE_AFTER_MS
}
