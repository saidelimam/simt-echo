package app.simt.echo.platform.nfc

import app.simt.echo.transfer.NfcOfferSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import app.simt.echo.platform.NfcEvent

/**
 * The one offer this process is presenting over NFC (plan 0005 §4.4).
 *
 * The APDU service and the screen's controller live in the same process, so
 * this is the only link between them: the screen publishes an immutable
 * snapshot and arms it; the service reads whatever is armed when a reader
 * arrives. Nothing here survives a process start, so a cold service or a
 * recreated process has no offer and answers `6985` — there is no disk
 * copy to resurrect.
 *
 * Publication is atomic with respect to arming: a snapshot written while
 * the app is backgrounded is never served, and a stop for a replaced
 * generation cannot clear a newer one.
 */
object ActiveNfcOfferRegistry {

    private val lock = Any()
    private var snapshot: NfcOfferSnapshot? = null
    private var armed: Boolean = false

    /** Publishes [offer] as the current snapshot; not yet visible. */
    fun publish(offer: NfcOfferSnapshot) {
        synchronized(lock) { snapshot = offer }
    }

    /** Makes the published snapshot visible to a reader. */
    fun arm() {
        synchronized(lock) { armed = true }
    }

    /** Hides the snapshot (paused, method switched, destination left). */
    fun disarm() {
        synchronized(lock) { armed = false }
    }

    /** The offer a reader may see right now, or null. */
    fun current(): NfcOfferSnapshot? = synchronized(lock) {
        if (armed) snapshot else null
    }

    /** Clears only [generation]; a stale clear leaves a newer offer intact. */
    fun clear(generation: Long) {
        synchronized(lock) {
            if (snapshot?.generation == generation) {
                snapshot = null
                armed = false
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            snapshot = null
            armed = false
        }
    }

    fun isArmed(): Boolean = synchronized(lock) { armed }

    fun generation(): Long = synchronized(lock) { snapshot?.generation ?: 0L }
}

/**
 * Process-scoped NFC events (plan 0005 §4.4).
 *
 * The APDU service observes a peer reading the invitation; the controller
 * forwards that to the screen. Carries typed events only — never bytes, so
 * a log or a crash report written from here cannot leak an invitation.
 */
object NfcEventBus {

    private val _events = MutableSharedFlow<NfcEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<NfcEvent> = _events

    fun emit(event: NfcEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Builds the presenter exactly as the hosted service does (plan 0005 §4.3).
 *
 * Kept here rather than inline in the service so the host gate drives the
 * production object: the policy under test is "what does the service
 * answer for this registry state", not a re-implementation of it.
 */
object EchoTapPresentation {

    fun presenter(
        nowMs: () -> Long,
        interactive: () -> Boolean,
        onInvitationRead: (Long) -> Unit = { generation ->
            NfcEventBus.emit(NfcEvent.InvitationRead(generation))
        },
    ): app.simt.echo.transfer.NfcApduPresenter = app.simt.echo.transfer.NfcApduPresenter(
        snapshot = { ActiveNfcOfferRegistry.current() },
        nowMs = nowMs,
        interactive = interactive,
        onInvitationRead = onInvitationRead,
    )
}
