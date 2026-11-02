package app.simt.echo.platform

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

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

/**
 * Polls whether the active network is unmetered (plan W11-09); unknown
 * or absent means metered so the walk uploader fails closed.
 */
@Composable
actual fun rememberUnmetered(pollMs: Long): Boolean {
    val context = LocalContext.current.applicationContext
    var unmetered by remember { mutableStateOf(false) }
    LaunchedEffect(context, pollMs) {
        while (true) {
            unmetered = runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                manager.activeNetwork != null && !manager.isActiveNetworkMetered
            }.getOrDefault(false)
            delay(pollMs)
        }
    }
    return unmetered
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

/** One [GmsWearNavSender] per screen; its gate holds the ≤ 1 Hz rule. */
@Composable
actual fun rememberWearNavSink(): (app.simt.echo.nav.NavWearState) -> Unit {
    val context = LocalContext.current.applicationContext
    return remember(context) { GmsWearNavSender(context)::send }
}

@Composable
actual fun rememberCueOutput(earconsEnabled: Boolean): CueOutput {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    // Owns every in-flight clip: cancelling it releases their AudioTracks.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    return remember(context, view, earconsEnabled, scope) {
        AndroidCueOutput(context, view, earconsEnabled, AudioTrackEarcons(scope))
    }
}

/**
 * Android [CueOutput]: announcements through the view, earcons through an
 * [AudioTrack][AudioTrackEarcons] behind the [EarconGate]. Earcons are
 * opt-in ([earconsEnabled] is the Settings switch, default off): the gate's
 * explicit opt-in is that switch, and headphones alone never turn sound on.
 */
private class AndroidCueOutput(
    private val context: Context,
    private val view: View,
    private val earconsEnabled: Boolean,
    private val player: AudioTrackEarcons,
) : CueOutput {
    override val eyesFree: Boolean get() = context.isScreenReaderActive()

    override fun earcon(pattern: HapticPattern, arrowRad: Double) {
        if (!earconsEnabled) return
        val allowed = EarconGate.shouldPlay(
            explicitOptIn = true,
            headphonesConnected = context.headphonesConnected(),
            talkBackSpeaking = context.isScreenReaderActive(),
        )
        if (!allowed) return
        player.play(EarconSynth.synthesize(pattern, arrowRad))
    }

    override fun announce(text: String) {
        view.announceForAccessibility(text)
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

/**
 * TalkBack-aware mute hook for the [EarconGate] (plan W13-09): true while a
 * screen reader is enabled — touch exploration on, or any accessibility
 * service with spoken feedback. This is deliberately conservative: Android
 * exposes whether a screen reader is *enabled*, not whether it is speaking
 * this instant, so the earcon player treats "enabled" as "may be speaking"
 * and stays silent rather than talk over it.
 */
fun Context.isScreenReaderActive(): Boolean {
    val manager = runCatching {
        getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }.getOrNull() ?: return false
    return manager.isTouchExplorationEnabled ||
        manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN,
        ).isNotEmpty()
}

/**
 * Phone vibrator (plan W13-09): freeze §7 primitive compositions via
 * `VibrationEffect.Composition` when every required primitive is supported
 * (API 31+), the V2 §8.4 waveform fallback from [HapticCueMap] otherwise.
 */
private class AndroidHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * `areAllPrimitivesSupported` is a binder call to the vibrator service,
     * and the answer cannot change for a given pattern: ask once per
     * pattern, not on every cue (and every heartbeat tick).
     */
    private val compositionSupport = ConcurrentHashMap<HapticPattern, Boolean>()

    override fun play(pattern: HapticPattern) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        val useComposition = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            compositionSupport.getOrPut(pattern) {
                runCatching {
                    device.areAllPrimitivesSupported(*primitiveIds(pattern))
                }.getOrDefault(false)
            }
        when (val effect = HapticCueMap.effectFor(pattern, useComposition)) {
            is HapticEffect.Composition -> device.vibrate(compose(effect))
            is HapticEffect.Waveform -> device.vibrate(VibrationEffect.createWaveform(effect.timings, -1))
        }
    }

    private fun primitiveIds(pattern: HapticPattern): IntArray =
        HapticCueMap.requiredPrimitives(pattern).map { primitiveId(it) }.toIntArray()

    private fun primitiveId(primitive: HapticPrimitive): Int = when (primitive) {
        HapticPrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
        HapticPrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        HapticPrimitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        HapticPrimitive.SLOW_RISE -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        HapticPrimitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
        HapticPrimitive.SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
    }

    private fun compose(effect: HapticEffect.Composition): VibrationEffect {
        val composition = VibrationEffect.startComposition()
        for (step in effect.steps) {
            composition.addPrimitive(primitiveId(step.primitive), step.scale, step.delayMs.toInt())
        }
        return composition.compose()
    }
}
