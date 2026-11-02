package app.simt.echo.ui.atmosphere

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// ECHO-owned CosmosBackground, freeze §9.3 + plan §5 Atmosphere row.
// Adapted from the simt-app reference with the one mandated correction:
// disk-distributed placement became the spec's outer-20% edge band
// (centre 60%x60% clear, see CosmosStars.kt). Layers bottom -> top:
// void gradient, <=2 nebula fields (FULL tier + Home/Gate only),
// edge-band starfield (one cached layer), twinkle on bright stars
// (FULL tier only). Daylight Nebula renders a flat background with no
// stars/nebula (freeze §3 [P-R C27]).
//
// Performance contract (acceptance: zero per-frame allocation in the draw
// phase): star positions + brushes + palette are computed in remember
// blocks keyed on size/scheme/variant/tier; the draw phase reads floats,
// precomputed brushes, and one remembered scratch buffer. Animated values
// (twinkle/drift) and the parallax lambda are read in the draw phase only,
// so feeding them never recomposes. No infinite animation is started below
// FULL, and REDUCED/STATIC compose the identical Canvas hierarchy with
// static values.
//
// Accessibility: the Canvas carries no content description and emits no
// semantics children, so stars are absent from the accessibility tree. The
// test tag is invisible to accessibility services.

const val CosmosBackgroundTestTag = "CosmosBackground"

/** Nebula drift period [P-T starfield]: 90-160 s window; amplitude <=1.5% of the short edge. */
internal const val COSMOS_DRIFT_PERIOD_MS = 96_000
internal const val COSMOS_DRIFT_AMPLITUDE_FRACTION = 0.015f
internal const val COSMOS_TWINKLE_PERIOD_MS = 2_600

private const val DEG_TO_RAD = (kotlin.math.PI / 180.0).toFloat()

@Composable
fun CosmosBackground(
    modifier: Modifier = Modifier,
    variant: CosmosVariant = CosmosVariant.Panel,
    tier: CosmosMotionTier = cosmosMotionTier(),
    parallaxRotationDegrees: () -> Float = { 0f },
    frozen: Boolean = false,
    /** False draws the starfield only, over another field (W13-01 EchoField). */
    drawField: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val animated = tier == CosmosMotionTier.FULL && !frozen
    val isLight = remember(colorScheme.background) { colorScheme.background.luminance() > 0.5f }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = remember(density, maxWidth) { with(density) { maxWidth.toPx() } }
        val heightPx = remember(density, maxHeight) { with(density) { maxHeight.toPx() } }

        val stars = remember(variant, tier, widthPx, heightPx, isLight) {
            if (isLight || widthPx <= 0f || heightPx <= 0f) {
                emptyList()
            } else {
                generateCosmosStars(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    variant = variant,
                    lowPower = tier == CosmosMotionTier.STATIC,
                )
            }
        }

        val palette = remember(colorScheme, isLight) {
            CosmosBackgroundPalette(
                voidCenter = colorScheme.background,
                voidEdge = colorScheme.surfaceContainerLowest,
                topGlow = colorScheme.primary.copy(alpha = if (isLight) 0.07f else 0.05f),
                star = if (isLight) {
                    colorScheme.onBackground.copy(alpha = 0.55f)
                } else {
                    Color.White
                },
                accentA = colorScheme.primary,
                accentB = colorScheme.tertiary,
                nebulaA = colorScheme.primary,
                nebulaB = colorScheme.tertiary,
            )
        }

        // Brushes depend on size, so they are remembered per size: the draw
        // phase reuses them instead of building gradients every frame.
        val voidBrush = remember(palette, widthPx, heightPx) {
            Brush.radialGradient(
                colors = listOf(palette.voidCenter, palette.voidEdge),
                center = Offset(widthPx / 2f, heightPx / 2f),
                radius = maxOf(widthPx, heightPx) * 1.2f,
            )
        }
        val topGlowBrush = remember(palette, widthPx, heightPx) {
            Brush.radialGradient(
                colors = listOf(palette.topGlow, Color.Transparent),
                center = Offset(widthPx / 2f, 0f),
                radius = maxOf(widthPx, heightPx) * 0.9f,
            )
        }
        val nebulaBrushA = remember(palette, widthPx, heightPx) {
            Brush.radialGradient(
                colors = listOf(palette.nebulaA.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(widthPx * 0.3f, heightPx * 0.35f),
                radius = maxOf(widthPx, heightPx) * 0.55f,
            )
        }
        val nebulaBrushB = remember(palette, widthPx, heightPx) {
            Brush.radialGradient(
                colors = listOf(palette.nebulaB.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(widthPx * 0.7f, heightPx * 0.62f),
                radius = maxOf(widthPx, heightPx) * 0.45f,
            )
        }

        val transition = if (animated) rememberInfiniteTransition(label = "cosmos") else null
        val twinkle = transition?.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = COSMOS_TWINKLE_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "twinkle",
        )
        val drift = transition?.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = COSMOS_DRIFT_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "drift",
        )

        val showNebula = animated && (variant == CosmosVariant.Home || variant == CosmosVariant.Gate)
        // One remembered scratch buffer reused for every star every frame:
        // rotatedStarPosition writes here, so the draw loop allocates nothing.
        val scratch = remember { FloatArray(2) }

        Canvas(modifier = Modifier.fillMaxSize().testTag(CosmosBackgroundTestTag)) {
            if (isLight) {
                // Daylight Nebula: flat background, no starfield/nebula [P-R C27].
                if (drawField) drawRect(color = palette.voidCenter)
                return@Canvas
            }
            if (drawField) {
                drawRect(brush = voidBrush)
                drawRect(brush = topGlowBrush)
            }

            if (drawField && showNebula) {
                val driftRad = (drift?.value ?: 0f) * DEG_TO_RAD
                val shortEdge = minOf(size.width, size.height)
                val orbitRange = shortEdge * COSMOS_DRIFT_AMPLITUDE_FRACTION
                val orbitX = kotlin.math.cos(driftRad) * orbitRange
                val orbitY = kotlin.math.sin(driftRad) * orbitRange
                val maxRadius = maxOf(size.width, size.height)
                drawCircle(
                    brush = nebulaBrushA,
                    radius = maxRadius * 0.55f,
                    center = Offset(size.width * 0.3f + orbitX, size.height * 0.35f + orbitY),
                )
                drawCircle(
                    brush = nebulaBrushB,
                    radius = maxRadius * 0.45f,
                    center = Offset(size.width * 0.7f - orbitX, size.height * 0.62f - orbitY),
                )
            }

            val ambient = if (animated) (drift?.value ?: 0f) * 0.25f else 0f
            val rotationDeg = (if (tier.allowsParallax) parallaxRotationDegrees() else 0f) + ambient
            val angleRad = rotationDeg * DEG_TO_RAD
            val twinklePhase = twinkle?.value ?: 0.5f
            val dustRadius = 1.dp.toPx()
            for (index in stars.indices) {
                val star = stars[index]
                rotatedStarPosition(star, size.width, size.height, angleRad, scratch)
                val color = when (star.kind) {
                    2 -> palette.accentA
                    3 -> palette.accentB
                    else -> palette.star
                }
                val alpha = if (animated && star.kind >= 2) {
                    val phase = (twinklePhase + star.twinkleOffset) % 1f
                    star.alpha * (0.55f + 0.45f * phase)
                } else {
                    star.alpha
                }
                val radius = dustRadius * star.sizeFactor
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(scratch[0], scratch[1]),
                )
                if (star.spike) {
                    val glint = radius * 2.2f
                    drawLine(
                        color = color.copy(alpha = alpha * 0.6f),
                        start = Offset(scratch[0] - glint, scratch[1]),
                        end = Offset(scratch[0] + glint, scratch[1]),
                        strokeWidth = dustRadius * 0.5f,
                    )
                    drawLine(
                        color = color.copy(alpha = alpha * 0.6f),
                        start = Offset(scratch[0], scratch[1] - glint),
                        end = Offset(scratch[0], scratch[1] + glint),
                        strokeWidth = dustRadius * 0.5f,
                    )
                }
            }
        }
    }
}

private class CosmosBackgroundPalette(
    val voidCenter: Color,
    val voidEdge: Color,
    val topGlow: Color,
    val star: Color,
    val accentA: Color,
    val accentB: Color,
    val nebulaA: Color,
    val nebulaB: Color,
)
