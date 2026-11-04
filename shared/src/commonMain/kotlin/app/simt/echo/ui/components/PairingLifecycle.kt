package app.simt.echo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The foreground transitions a pairing destination reacts to. */
enum class PairingLifecycleAction { RESUME, PAUSE }

/**
 * Maps lifecycle events to pairing actions (review 0014 R14-05).
 *
 * `addObserver` on an already-resumed owner dispatches `ON_RESUME`
 * synchronously, before the screen's own `init` has started acquisition.
 * That is a start, not a return: forwarding it armed the reader twice and
 * reset Share's export selection on mount. Only a resume that follows a
 * pause is a return to the foreground.
 */
class PairingLifecycleGate {

    private var leftForeground = false

    fun actionFor(event: Lifecycle.Event): PairingLifecycleAction? = when (event) {
        Lifecycle.Event.ON_PAUSE -> {
            leftForeground = true
            PairingLifecycleAction.PAUSE
        }

        Lifecycle.Event.ON_RESUME -> if (leftForeground) {
            leftForeground = false
            PairingLifecycleAction.RESUME
        } else {
            null
        }

        else -> null
    }
}

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
        val gate = PairingLifecycleGate()
        val observer = LifecycleEventObserver { _, event ->
            when (gate.actionFor(event)) {
                PairingLifecycleAction.RESUME -> onResume()
                PairingLifecycleAction.PAUSE -> onPause()
                null -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            onDispose()
        }
    }
}
