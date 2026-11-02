package app.simt.echo.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier

// Luminous motion physics (plan W13-02, docs/research/luminous-freeze.md §3):
// spatial motion is springs, so interrupted motion retargets from its
// current velocity instead of jumping. Screens name a token; they never
// hard-code a spring.

object LuminousSprings {
    /** Chips, toggles, small controls. */
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.90f, stiffness = 1400f)

    /** Cards, sheets, record rows, deck. */
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 700f)

    /** Full-screen transitions, shared elements. */
    fun <T> spatialSlow(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 320f)

    /** Blooms, arrival, sigil reveal. */
    fun <T> spatialExpressive(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 420f)

    /** Opacity/colour of small elements. */
    fun <T> effectsFast(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** Opacity/colour of panels. */
    fun <T> effectsDefault(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
}

/** The shell's shared-transition scope; null outside the nav host (previews, tests). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalEchoSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The current destination's enter/exit scope; null outside a destination. */
val LocalEchoDestinationScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks this element as the same object across destinations (e.g. a
 * route's sigil on Home and on RouteDetail), so it flies between them on
 * [LuminousSprings.spatialSlow]. FULL tier only; elsewhere, and outside
 * the nav host, it is a no-op and the screens cross-fade as usual.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.echoSharedElement(key: Any): Modifier {
    val shared = LocalEchoSharedScope.current
    val destination = LocalEchoDestinationScope.current
    if (shared == null || destination == null || cosmosMotionTier() != CosmosMotionTier.FULL) return this
    return with(shared) {
        this@echoSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = destination,
            boundsTransform = { _, _ -> LuminousSprings.spatialSlow() },
        )
    }
}

/** Shared-element key for a route's sigil, used on every screen that shows it. */
fun sigilSharedKey(routeId: String): String = "sigil:$routeId"
