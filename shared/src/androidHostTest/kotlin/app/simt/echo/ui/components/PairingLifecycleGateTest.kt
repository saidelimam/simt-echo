package app.simt.echo.ui.components

import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R14-05 gate: a mount is a start, not a return.
 *
 * `addObserver` on an already-resumed owner dispatches `ON_RESUME` at once.
 * Forwarding that to the screen double-started acquisition on mount — the
 * reader armed generation 2 over generation 1, and Share reset the user's
 * export selection.
 */
class PairingLifecycleGateTest {

    @Test
    fun theSynchronousResumeOnMountIsNotAReturn() {
        val gate = PairingLifecycleGate()
        assertNull(gate.actionFor(Lifecycle.Event.ON_RESUME))
        assertNull(gate.actionFor(Lifecycle.Event.ON_START))
        assertNull(gate.actionFor(Lifecycle.Event.ON_CREATE))
    }

    @Test
    fun onlyAPauseThenResumePairIsForwarded() {
        val gate = PairingLifecycleGate()
        assertEquals(PairingLifecycleAction.PAUSE, gate.actionFor(Lifecycle.Event.ON_PAUSE))
        assertEquals(PairingLifecycleAction.RESUME, gate.actionFor(Lifecycle.Event.ON_RESUME))
        // One pause earns exactly one resume.
        assertNull(gate.actionFor(Lifecycle.Event.ON_RESUME))
    }

    @Test
    fun aPausedDestinationThatIsReattachedPausesAgain() {
        val gate = PairingLifecycleGate()
        gate.actionFor(Lifecycle.Event.ON_PAUSE)
        gate.actionFor(Lifecycle.Event.ON_RESUME)
        assertEquals(PairingLifecycleAction.PAUSE, gate.actionFor(Lifecycle.Event.ON_PAUSE))
        assertEquals(PairingLifecycleAction.RESUME, gate.actionFor(Lifecycle.Event.ON_RESUME))
    }
}
