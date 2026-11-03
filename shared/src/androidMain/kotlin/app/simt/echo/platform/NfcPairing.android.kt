package app.simt.echo.platform

// W20-04 installs the real foreground presenter: publishing writes an
// immutable snapshot into the process registry, selects the HCE service as
// the *foreground* preferred service, and reports ready only after the
// platform accepted it. The reader side follows in W20-05.
//
// Devices without the NFC radio (or without HCE) get the unavailable
// controller, which reports no capabilities and never renders ready.

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.simt.echo.platform.nfc.AndroidNfcPairingController

@Composable
actual fun rememberNfcPairingController(): NfcPairingController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) { AndroidNfcPairingController.create(context) }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResumed()
                Lifecycle.Event.ON_PAUSE -> controller.onPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        controller.start()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            controller.onResumed()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    return controller
}

// Plan 0005 §2: the adapter is the user's switch, never ours. Open the
// narrow NFC screen and fall back to the wireless settings that contain
// it; failing both leaves the app silent rather than crashing.
@Composable
actual fun rememberNfcSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            val opened = runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            if (opened.isFailure) {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
            Unit
        }
    }
}
