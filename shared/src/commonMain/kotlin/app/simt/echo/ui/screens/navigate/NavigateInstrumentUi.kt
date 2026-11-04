package app.simt.echo.ui.screens.navigate

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosTertiaryButton
import app.simt.echo.ui.components.DefaultOverlayLandmarkCard
import app.simt.echo.ui.components.LandmarkCard
import app.simt.echo.ui.components.wrapPi
import app.simt.echo.ui.components.MaskedCropImage
import app.simt.echo.ui.motion.LuminousSprings
import app.simt.echo.ui.theme.EchoExtras
import kotlin.math.PI
import kotlin.math.roundToInt

const val NavigateMorphArrowTag = "NavigateMorphArrow"
const val NavigateSigmaAuraTag = "NavigateSigmaAura"
const val NavigateGlowTagPrefix = "NavigateGlow"
const val NavigateFingerprintChipTag = "NavigateFingerprintChip"
const val NavigateRelockRingTag = "NavigateRelockRing"
const val NavigateLookForThisTag = "NavigateLookForThis"
const val NavigateVisualLockTag = "NavigateVisualLock"
const val NavigateVisualNudgeTag = "NavigateVisualNudge"

/**
 * Morphing navigation arrow with its σ aura (plan W13-08).
 *
 * The chevron rotates with [arrowRad] (clockwise-positive, same wrap as
 * the navigator) and its tip bends toward the turning side by
 * [bendFraction01] — the rotate fallback the plan sanctions, since W13-02
 * ships no graphics-shapes Morph wrapper. U draws as a hook, ARRIVED as
 * a check. The aura circle behind it grows/softer with σ; UNCERTAIN
 * renders it dashed with the σ label as text (honest: mark + copy, never
 * hue-only). FULL animates rotation/bend on the spatial spring;
 * REDUCED/STATIC draw the same final geometry at once. The canvas is
 * decorative — the instruction text carries the semantics.
 *
 * @param dimChrome calm mode: aura and chrome fade; text is untouched
 * (contrast never reduced).
 */
@Composable
fun NavigateMorphArrow(
    shape: ArrowShape,
    bendFraction01: Float,
    arrowRad: Float,
    auraRadiusDp: Float,
    auraSoftness01: Float,
    auraDashed: Boolean,
    sigmaLabel: String,
    dimChrome: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    tier: CosmosMotionTier = cosmosMotionTier(),
    visualLockConfidence01: Float = 0f,
    visualNudge: String? = null,
) {
    val animated = remember { Animatable(arrowRad) }
    LaunchedEffect(arrowRad, tier) {
        if (tier == CosmosMotionTier.FULL) {
            val delta = wrapPi(arrowRad - animated.value)
            if (delta != 0f) {
                animated.animateTo(
                    animated.value + delta,
                    animationSpec = LuminousSprings.spatialDefault(),
                )
            }
        } else {
            animated.snapTo(arrowRad)
        }
    }
    val bend = if (tier == CosmosMotionTier.FULL) {
        remember { Animatable(bendFraction01) }.also { state ->
            LaunchedEffect(bendFraction01) {
                state.animateTo(bendFraction01, animationSpec = LuminousSprings.spatialDefault())
            }
        }.value
    } else {
        bendFraction01
    }
    val density = LocalDensity.current
    val auraRadiusPx = with(density) { auraRadiusDp.dp.toPx() }
    val lockColor = EchoExtras.cyan
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .size(AURA_CANVAS_DP.dp)
                .testTag(NavigateMorphArrowTag),
        ) {
            drawSigmaAura(
                color = color,
                radiusPx = auraRadiusPx,
                softness01 = auraSoftness01,
                dimChrome = dimChrome,
                dashed = auraDashed,
            )
            drawVisualLockRing(
                color = lockColor,
                auraRadiusPx = auraRadiusPx,
                confidence01 = visualLockConfidence01,
            )
            when (shape) {
                ArrowShape.U_TURN -> drawUTurn(color)
                ArrowShape.CHECK -> drawCheck(color)
                else -> {
                    val side = when (shape) {
                        ArrowShape.BEND_LEFT -> -1f
                        ArrowShape.BEND_RIGHT -> 1f
                        else -> 0f
                    }
                    rotate(degrees = animated.value * 180f / PI.toFloat()) {
                        drawBentChevron(color, side * bend)
                    }
                }
            }
        }
        if (auraDashed) {
            Text(
                text = sigmaLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag(NavigateSigmaAuraTag),
            )
        }
        if (visualLockConfidence01 >= 0.5f) {
            val pct = (visualLockConfidence01 * 100f).roundToInt()
            Text(
                text = "Visual lock $pct%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(NavigateVisualLockTag),
            )
        }
        if (visualNudge != null) {
            Text(
                text = visualNudge,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(NavigateVisualNudgeTag),
            )
        }
    }
}

/** Periphery glow: soft edge band on the turning side, strength ∝ 1 − remaining/8. */
@Composable
fun NavigatePeripheryGlow(
    side: GlowSide,
    intensity01: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (intensity01 <= 0f) return
    val alpha = intensity01.coerceIn(0f, 1f) * 0.45f
    val brush = when (side) {
        GlowSide.LEFT -> Brush.horizontalGradient(listOf(color.copy(alpha = alpha), Color.Transparent))
        GlowSide.RIGHT -> Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = alpha)))
    }
    val align = if (side == GlowSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd
    Box(modifier = modifier, contentAlignment = align) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(36.dp)
                .testTag("$NavigateGlowTagPrefix:${side.name}"),
        ) {
            drawRect(brush = brush)
        }
    }
}

/**
 * Fingerprint match chip (W12-05): asks "That's the X?" and offers
 * confirm + dismiss. Never asserts — confirmation still flows through
 * [app.simt.echo.nav.ghost.FingerprintMatch.isConfirmedBy].
 */
@Composable
fun FingerprintMatchChip(
    copy: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .testTag(NavigateFingerprintChipTag),
    ) {
        CosmosSecondaryButton(label = copy, onClick = onConfirm, modifier = Modifier.weight(1f))
        CosmosTertiaryButton(label = "Not it", onClick = onDismiss)
    }
}

/**
 * Relock progress ring (W12-06). Null [state] — the W12-06 slice has not
 * landed — renders nothing. Additive and null-safe by construction.
 */
@Composable
fun RelockRing(
    state: NavigateRelockState?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (state == null) return
    val progress = state.progress01.toFloat().coerceIn(0f, 1f)
    val percent = (progress * 100f).roundToInt()
    Canvas(
        modifier = modifier
            .size(48.dp)
            .testTag(NavigateRelockRingTag)
            .semantics { contentDescription = "Relocking, $percent percent" },
    ) {
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 6.dp.toPx()),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            style = Stroke(width = 6.dp.toPx()),
        )
    }
}

/** "Look for this" card: heading + traced-mask crop + landmark copy. */
@Composable
fun LookForThisCard(
    title: String,
    cue: String,
    stats: String?,
    cropBytes: ByteArray?,
    contentDescription: String?,
    maskSvgPath: String?,
    maskW: Int?,
    maskH: Int?,
    box: List<Int>?,
    frameW: Int?,
    frameH: Int?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.testTag(NavigateLookForThisTag),
    ) {
        Text(
            text = "Look for this",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        MaskedCropImage(
            bytes = cropBytes,
            contentDescription = contentDescription,
            maskSvgPath = maskSvgPath,
            maskW = maskW,
            maskH = maskH,
            box = box,
            frameW = frameW,
            frameH = frameH,
        )
        // The crop above already occupies the image slot.
        LandmarkCard(title = title, cue = cue, stats = stats, showImageSlot = false)
    }
}

/**
 * Shared arrow wiring for both Navigate bodies (plain + camera HUD):
 * morph arrow with σ aura over the presentation snapshot.
 */
@Composable
fun InstrumentArrow(
    instrument: NavigateInstrumentPresentation,
    arrowRad: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    NavigateMorphArrow(
        shape = instrument.arrowShape,
        bendFraction01 = instrument.bendFraction01,
        arrowRad = arrowRad.toFloat(),
        auraRadiusDp = instrument.auraRadiusDp,
        auraSoftness01 = instrument.auraSoftness01,
        auraDashed = instrument.auraDashed,
        sigmaLabel = instrument.sigmaLabel,
        dimChrome = instrument.calmMode,
        modifier = modifier,
        color = color,
        visualLockConfidence01 = instrument.visualLockConfidence01,
        visualNudge = instrument.visualNudge,
    )
}

/**
 * Shared below-distance wiring: fingerprint chip (asks, never asserts)
 * plus the W12-06 relock ring (null → renders nothing).
 */
@Composable
fun InstrumentBelowDistance(
    chipCopy: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chipCopy != null) {
        FingerprintMatchChip(
            copy = chipCopy,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            modifier = modifier,
        )
    }
    // W12-06 absent: renders nothing.
    RelockRing(state = null)
}

/**
 * Shared landmark wiring: "Look for this" with the traced mask on
 * approach/turning/confirm, else the standard masked card.
 */
@Composable
fun InstrumentLandmarkCard(
    showLookForThis: Boolean,
    title: String,
    cue: String,
    stats: String?,
    cropBytes: ByteArray?,
    contentDescription: String?,
    maskSvgPath: String?,
    maskW: Int?,
    maskH: Int?,
    box: List<Int>?,
    frameW: Int?,
    frameH: Int?,
    modifier: Modifier = Modifier,
) {
    if (showLookForThis) {
        LookForThisCard(
            title = title,
            cue = cue,
            stats = stats,
            cropBytes = cropBytes,
            contentDescription = contentDescription,
            maskSvgPath = maskSvgPath,
            maskW = maskW,
            maskH = maskH,
            box = box,
            frameW = frameW,
            frameH = frameH,
            modifier = modifier,
        )
    } else {
        DefaultOverlayLandmarkCard(
            landmarkTitle = title,
            landmarkCue = cue,
            landmarkStats = stats,
            cropBytes = cropBytes,
            cropDescription = contentDescription,
            maskSvgPath = maskSvgPath,
            maskW = maskW,
            maskH = maskH,
            box = box,
            frameW = frameW,
            frameH = frameH,
            modifier = modifier,
        )
    }
}

