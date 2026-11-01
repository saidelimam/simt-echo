package app.simt.echo.platform

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

/**
 * Phone-side Data Layer sender for the haptic mirror (plan W5-04).
 *
 * Transport shape follows `simt-app`'s `data/wear` (`WearDataLayerTransport`:
 * capability query then directed `MessageClient` sends), re-implemented here
 * as enclosed ECHO code — no import, no dependency, no `simt-*` names.
 *
 * Best-effort by contract: every failure is swallowed (logged) so a missing
 * watch, missing Play Services, or wedged radio can never break or delay
 * phone haptics. All Play Services callbacks run off the caller thread.
 */
class GmsWearHapticSender(
    private val context: Context,
) {
    fun send(pattern: HapticPattern) {
        try {
            val payload = WearHapticCodec.encode(pattern)
            Wearable.getCapabilityClient(context)
                .getCapability(EchoWearPaths.CAPABILITY_WEAR_APP, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info ->
                    sendToNodes(info.nodes.map { it.id }, payload)
                }
                .addOnFailureListener { e ->
                    Log.d(TAG, "wear haptic capability query failed", e)
                }
        } catch (e: Exception) {
            Log.d(TAG, "wear haptic send skipped", e)
        }
    }

    private fun sendToNodes(nodeIds: List<String>, payload: ByteArray) {
        if (nodeIds.isEmpty()) return
        val client = try {
            Wearable.getMessageClient(context)
        } catch (e: Exception) {
            Log.d(TAG, "wear haptic client unavailable", e)
            return
        }
        for (id in nodeIds) {
            try {
                client.sendMessage(id, EchoWearPaths.MESSAGE_PATH_HAPTICS, payload)
                    .addOnFailureListener { e ->
                        Log.d(TAG, "wear haptic delivery failed", e)
                    }
            } catch (e: Exception) {
                Log.d(TAG, "wear haptic send to $id skipped", e)
            }
        }
    }

    private companion object {
        const val TAG = "EchoWearHaptics"
    }
}
