package app.simt.echo.wear

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 * missing vibrator or a malformed payload is dropped silently, never
 * thrown — the watch must not crash on a background callback.
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
        // decode never throws; a payload that is not v1 nav is ignored.
        val state = EchoNavCodec.decode(data) ?: return
        EchoNavStore.update(state)
    }

    private fun onHaptic(data: ByteArray) {
        val name = try {
            data.decodeToString()
        } catch (_: Exception) {
            return
        }
        val timings = EchoWearHaptics.timings(name) ?: return
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
        } catch (_: Exception) {
            // Best-effort: a vibrator that refuses must not crash the service.
        }
    }

    private companion object {
        const val MESSAGE_PATH_HAPTICS = "/echo/haptics"
        const val MESSAGE_PATH_NAV = "/echo/nav"
    }
}
