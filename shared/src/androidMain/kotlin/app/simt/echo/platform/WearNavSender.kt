package app.simt.echo.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.simt.echo.nav.NavWearState
import app.simt.echo.nav.WearNavSync
import app.simt.echo.nav.WearNavSendGate
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/**
 * Phone-side Data Layer sender for the nav mirror (plan W13-12).
 *
 * Follows the `GmsWearHapticSender` pattern (capability query, then
 * directed `MessageClient` sends to [EchoWearPaths.MESSAGE_PATH_NAV]) with
 * the slice's own rule on top: snapshots are change-driven at ≤ 1 Hz
 * ([WearNavSendGate]), so motion-rate [NavWearState] updates cost the radio
 * nothing until something actually changes. A change dropped by the rate
 * limit is sent as a trailing edge once the interval is up, so the last
 * change before a pause (the turn cue, arrival, the end of the walk) always
 * reaches the watch.
 *
 * Best-effort by contract, like the haptic sender: every failure is
 * swallowed so a missing watch or wedged radio can never break phone
 * navigation. All Play Services callbacks run off the caller thread.
 * [send] is called from the main thread (the ViewModel collector), which is
 * also where the trailing edge runs, so the gate needs no locking.
 */
class GmsWearNavSender(
    private val context: Context,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val gate = WearNavSendGate()
    private val handler = Handler(Looper.getMainLooper())
    private var trailingScheduled = false

    fun send(state: NavWearState) {
        try {
            if (gate.shouldSend(clockMs(), state)) {
                dispatch(state)
            } else {
                scheduleTrailing()
            }
        } catch (_: Exception) {
            // Best-effort: navigation never depends on the watch.
        }
    }

    private fun scheduleTrailing() {
        if (trailingScheduled) return
        val delayMs = gate.trailingDelayMs(clockMs()) ?: return
        trailingScheduled = true
        handler.postDelayed({ flushTrailing() }, delayMs)
    }

    private fun flushTrailing() {
        trailingScheduled = false
        try {
            gate.takeTrailing(clockMs())?.let { dispatch(it) } ?: scheduleTrailing()
        } catch (_: Exception) {
            // Best-effort, as above.
        }
    }

    private fun dispatch(state: NavWearState) {
        val payload = WearNavSync.encode(state)
        Wearable.getCapabilityClient(context)
            .getCapability(EchoWearPaths.CAPABILITY_WEAR_APP, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                sendToNodes(info.nodes.map { it.id }, payload)
            }
    }

    private fun sendToNodes(nodeIds: List<String>, payload: ByteArray) {
        if (nodeIds.isEmpty()) return
        val client = try {
            Wearable.getMessageClient(context)
        } catch (_: Exception) {
            return
        }
        for (id in nodeIds) {
            try {
                client.sendMessage(id, WearNavSync.MESSAGE_PATH, payload)
            } catch (_: Exception) {
                // One unreachable node must not stop the others.
            }
        }
    }
}
