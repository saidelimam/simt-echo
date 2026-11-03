package app.simt.echo.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// listReveal motif (Cosmos freeze §7, Luminous §3): records arrive one
// after another, rising a few dp on the spatialDefault spring. Signal: the
// screen's first composition — nothing replays while the user scrolls.

/** Stagger between consecutive records. */
const val REVEAL_STAGGER_MS = 45L

/** Later records start together, so long lists never wait. */
const val REVEAL_MAX_STAGGERED = 8

/** Delay before record [index] starts, capped so long lists never wait. */
fun revealDelayMs(index: Int): Long = REVEAL_STAGGER_MS * index.coerceIn(0, REVEAL_MAX_STAGGERED)

/**
 * Entrance for the [index]th item of a list: FULL rises 16 dp and fades on
 * springs, staggered; REDUCED fades in 150 ms; STATIC is already there.
 * Draw-phase only — the animation never recomposes the row.
 */
@Composable
fun Modifier.revealOnEnter(index: Int): Modifier {
    val tier = cosmosMotionTier()
    val progress = remember { Animatable(if (tier == CosmosMotionTier.STATIC) 1f else 0f) }
    LaunchedEffect(Unit) {
        when (tier) {
            CosmosMotionTier.STATIC -> progress.snapTo(1f)
            CosmosMotionTier.REDUCED -> progress.animateTo(1f, tween(CosmosMotionDurations.FEEDBACK_MS))
            CosmosMotionTier.FULL -> {
                delay(revealDelayMs(index).milliseconds)
                progress.animateTo(1f, LuminousSprings.spatialDefault())
            }
        }
    }
    val travels = tier == CosmosMotionTier.FULL
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = if (travels) (1f - progress.value) * 16.dp.toPx() else 0f
    }
}
