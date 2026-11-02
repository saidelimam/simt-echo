package app.simt.echo.wear

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Watch-side receiver for the phone's mirrors (plan W5-04 haptics, plan
 * W13-12 nav).
 *
 * The phone sends the `HapticPattern` name to `/echo/haptics` (see the
 * shared `WearHapticCodec`) and nav snapshots to `/echo/nav` (see the
 * shared `WearNavSync` and this module's [EchoNavCodec] mirror); this
 * service vibrates the §8.4 waveform, stores the latest nav snapshot for
 * [MainActivity], and ignores anything else. Best-effort throughout: a
 * missing vibrator or a malformed payload is logged, never thrown — the
 * watch must not crash on a background callback.
 */
class EchoHapticListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            MESSAGE_PATH_HAPTICS -> onHaptic(event.data)
            MESSAGE_PATH_NAV -> onNav(event.data)
            else -> return
        }
    }

    private fun onNav(data: ByteArray) {
        val state = try {
            EchoNavCodec.decode(data)
        } catch (e: Exception) {
            Log.d(TAG, "unreadable nav payload", e)
            return
        }
        if (state == null) {
            Log.d(TAG, "unknown nav payload")
            return
        }
        EchoNavStore.update(state)
    }

    private fun onHaptic(data: ByteArray) {
        val name = try {
            data.decodeToString()
        } catch (e: Exception) {
            Log.d(TAG, "unreadable haptic payload", e)
            return
        }
        val timings = EchoWearHaptics.timings(name)
        if (timings == null) {
            Log.d(TAG, "unknown haptic pattern: $name")
            return
        }
        vibrate(timings)
    }

    private fun vibrate(timings: LongArray) {
        try {
            @Suppress("DEPRECATION")
            val vibrator: Vibrator? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    getSystemService(VIBRATOR_SERVICE) as? Vibrator
                }
            val device = vibrator ?: return
            if (!device.hasVibrator()) return
            device.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (e: Exception) {
            Log.d(TAG, "haptic playback failed", e)
        }
    }

    private companion object {
        const val TAG = "EchoHapticMirror"
        const val MESSAGE_PATH_HAPTICS = "/echo/haptics"
        const val MESSAGE_PATH_NAV = "/echo/nav"
    }
}
