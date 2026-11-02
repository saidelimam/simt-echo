package app.simt.echo.ui.atmosphere

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// Motion tiers + resolver, docs/research/cosmos-echo-freeze.md §7
// ([P-T motion], Android easings [P-T platformAdapters.android.easing],
// tiers Full/Reduced/Static [P-R C17]). Tiers can only ever be lowered by
// ambient signals (sync, power, thermal, visibility); nothing here raises
// a tier above what the resolver returns.

/**
 * Rendering tier for cosmetic motion. Reduced removes travel + ambient
 * loops (opacity changes settle in <=150 ms); Static settles immediately
 * with an identical outcome. The composed hierarchy is the same in every
 * tier — only animation runs or does not.
 */
enum class CosmosMotionTier {
    FULL,
    REDUCED,
    STATIC,
    ;

    val allowsInfiniteAnimations: Boolean get() = this == FULL
    val allowsParallax: Boolean get() = this != STATIC
    val allowsEntranceAnimations: Boolean get() = this != STATIC
}

/** Durations shared across parts [P-T motion]: feedback / state / context. */
object CosmosMotionDurations {
    const val FEEDBACK_MS = 150
    const val STATE_MS = 300
    const val CONTEXT_MS = 500
}

/** Android easings [P-T platformAdapters.android.easing]. */
object CosmosMotionEasings {
    val Standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.2f, 1.0f)
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val Exit: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val Transform: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}

/**
 * Pure tier resolver: accessibility reduce-motion, battery/power save, and
 * visibility can only lower the tier, never raise it [P-R C17].
 *
 * - Not [visible] (off-screen, backgrounded) -> STATIC: nothing animates
 *   for a surface nobody sees.
 * - Reduce-motion *and* power-save together -> STATIC: settle immediately.
 * - Either signal alone -> REDUCED: no ambient loops, no travel.
 * - Otherwise -> FULL.
 */
fun resolveCosmosMotionTier(
    reduceMotion: Boolean,
    powerSave: Boolean,
    visible: Boolean,
): CosmosMotionTier {
    if (!visible) return CosmosMotionTier.STATIC
    if (reduceMotion && powerSave) return CosmosMotionTier.STATIC
    if (reduceMotion || powerSave) return CosmosMotionTier.REDUCED
    return CosmosMotionTier.FULL
}

val LocalCosmosMotionTier = staticCompositionLocalOf { CosmosMotionTier.FULL }

/** Ambient motion tier. Defaults to FULL; ambient providers only lower it. */
@Composable
@ReadOnlyComposable
fun cosmosMotionTier(): CosmosMotionTier = LocalCosmosMotionTier.current

/** Overrides the ambient tier for a subtree (tests, previews, forced tiers). */
@Composable
fun ProvideCosmosMotionTier(
    tier: CosmosMotionTier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCosmosMotionTier provides tier, content = content)
}
