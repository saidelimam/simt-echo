package app.simt.echo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Foreground-scoped pairing for one destination (plan 0005 W21-03).
 *
 * Native NFC can only run while its screen is visible and resumed, so the
 * screen reports both transitions to its ViewModel, and disposal closes the
 * visit for good. Resuming re-arms acquisition; pausing stops it without
 * ending the visit, so a Home press does not lose the route.
 */
@Composable
fun PairingLifecycle(
    onResume: () -> Unit,
    onPause: () -> Unit,
    onDispose: () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            onDispose()
        }
    }
}
