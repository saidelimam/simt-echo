package app.simt.echo.platform

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
 * nothing until something actually changes.
 *
 * Best-effort by contract, like the haptic sender: every failure is
 * swallowed (logged) so a missing watch or wedged radio can never break
 * phone navigation. All Play Services callbacks run off the caller thread.
 */
class GmsWearNavSender(
    private val context: Context,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val gate = WearNavSendGate()

    fun send(state: NavWearState) {
        try {
            if (!gate.shouldSend(clockMs(), state)) return
            val payload = WearNavSync.encode(state)
            Wearable.getCapabilityClient(context)
                .getCapability(EchoWearPaths.CAPABILITY_WEAR_APP, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info ->
                    sendToNodes(info.nodes.map { it.id }, payload)
                }
                .addOnFailureListener { e ->
                    Log.d(TAG, "wear nav capability query failed", e)
                }
        } catch (e: Exception) {
            Log.d(TAG, "wear nav send skipped", e)
        }
    }

    private fun sendToNodes(nodeIds: List<String>, payload: ByteArray) {
        if (nodeIds.isEmpty()) return
        val client = try {
            Wearable.getMessageClient(context)
        } catch (e: Exception) {
            Log.d(TAG, "wear nav client unavailable", e)
            return
        }
        for (id in nodeIds) {
            try {
                client.sendMessage(id, WearNavSync.MESSAGE_PATH, payload)
                    .addOnFailureListener { e ->
                        Log.d(TAG, "wear nav delivery failed", e)
                    }
            } catch (e: Exception) {
                Log.d(TAG, "wear nav send to $id skipped", e)
            }
        }
    }

    private companion object {
        const val TAG = "EchoWearNav"
    }
}
