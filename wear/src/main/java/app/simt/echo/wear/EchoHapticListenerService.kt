package app.simt.echo.wear

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Watch-side receiver for the phone's haptic mirror (plan W5-04).
 *
 * The phone sends the `HapticPattern` name to `/echo/haptics` (see the
 * shared `WearHapticCodec`); this service vibrates the §8.4 waveform and
 * ignores anything else. Best-effort throughout: a missing vibrator or a
 * malformed payload is logged, never thrown — the watch must not crash on
 * a background callback.
 */
class EchoHapticListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != MESSAGE_PATH_HAPTICS) return
        val name = try {
            event.data.decodeToString()
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
    }
}
