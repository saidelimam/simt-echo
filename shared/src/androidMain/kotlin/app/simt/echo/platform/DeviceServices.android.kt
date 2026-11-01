package app.simt.echo.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberMotionSource(): MotionSource {
    val context = LocalContext.current.applicationContext
    return remember(context) { BuiltInPdrMotionSource(context) }
}

@Composable
actual fun rememberCameraRecorder(): CameraRecorder {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    return remember(context, lifecycleOwner) {
        CameraXRecorder(context.applicationContext, lifecycleOwner)
    }
}

actual fun currentEpochMs(): Long = System.currentTimeMillis()

@Composable
actual fun rememberHaptics(): Haptics {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidHaptics(context) }
}

/** Waveforms from plan §8: TICK short, CONFIRM double, WARN long. */
private class AndroidHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun play(pattern: HapticPattern) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        device.vibrate(VibrationEffect.createOneShot(durationMs(pattern), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun durationMs(pattern: HapticPattern): Long = when (pattern) {
        HapticPattern.TICK -> 30L
        HapticPattern.CONFIRM -> 80L
        HapticPattern.LEFT, HapticPattern.RIGHT -> 120L
        HapticPattern.U_TURN -> 250L
        HapticPattern.WARN -> 400L
        HapticPattern.ARRIVE -> 500L
    }
}
