package app.simt.echo.platform

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@Composable
actual fun rememberMotionSource(): MotionSource {
    val context = LocalContext.current.applicationContext
    return remember(context) { BuiltInPdrMotionSource(context) }
}

@Composable
actual fun rememberCameraRecorder(graph: CameraGraph): CameraRecorder {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    return remember(context, lifecycleOwner, graph) {
        CameraXRecorder(context.applicationContext, lifecycleOwner, graph)
    }
}

actual fun currentEpochMs(): Long = System.currentTimeMillis()

actual fun createPeerTransport(): PeerTransport = KtorLanTransport()

/** Polls for an active network every [pollMs]; best-effort, false on error. */
@Composable
actual fun rememberOnline(pollMs: Long): Boolean {
    val context = LocalContext.current.applicationContext
    var online by remember { mutableStateOf(true) }
    LaunchedEffect(context, pollMs) {
        while (true) {
            online = runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                manager.activeNetwork != null
            }.getOrDefault(false)
            delay(pollMs)
        }
    }
    return online
}

@Composable
actual fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
actual fun rememberHaptics(): Haptics {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val local = AndroidHaptics(context)
        val sender = GmsWearHapticSender(context)
        WearMirroringHaptics(local, sender::send)
    }
}

/** Waveforms from plan §8.4 (exact timings). */
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
        device.vibrate(VibrationEffect.createWaveform(timings(pattern), -1))
    }

    private fun timings(pattern: HapticPattern): LongArray = when (pattern) {
        HapticPattern.TICK -> longArrayOf(0, 30)
        HapticPattern.CONFIRM -> longArrayOf(0, 40, 80, 40)
        HapticPattern.LEFT -> longArrayOf(0, 60, 120, 60)
        HapticPattern.RIGHT -> longArrayOf(0, 60, 120, 60, 120, 60)
        HapticPattern.U_TURN -> longArrayOf(0, 300, 150, 300)
        HapticPattern.WARN -> longArrayOf(0, 400)
        HapticPattern.ARRIVE -> longArrayOf(0, 200, 120, 60, 120, 200)
    }
}
