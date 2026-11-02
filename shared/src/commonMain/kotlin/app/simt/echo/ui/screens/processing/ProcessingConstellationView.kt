package app.simt.echo.ui.screens.processing

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16

const val ProcessingConstellationTag = "ProcessingConstellation"
const val ProcessingBudgetMeterTag = "ProcessingBudgetMeter"
const val ProcessingStarTagPrefix = "ProcessingStar_"

/**
 * Processing "Constellation" (plan W13-06): the route drawn as a
 * constellation, one star per checkpoint.
 *
 * Honest light: every star's shape + label comes straight from the last
 * `checkpoint` event for its node — nothing pulses, drifts or fills
 * without an event behind it. The twinkle on SCOUTING stars runs only on
 * the FULL tier; REDUCED/STATIC draw the same shapes and labels at a
 * fixed alpha (parity: no information lives in the animation). The ink
 * Canvas is decorative — each star carries its "label — State"
 * description and the state word is also plain text.
 *
 * V-frame bindings (device capture, owed — N/A in CI): queued dim pair,
 * scouting twinkle (FULL, mid-cycle), grounded bright + mask ring,
 * skipped hollow, budget meter at a nonzero usage, STATIC tier parity of
 * the same job, fontScale 2.0, dark + light. See
 * `docs/screenshots/w13-06/README.md`.
 */
@Composable
fun ConstellationHeader(
    stageText: String,
    budgetText: String,
    onCancel: () -> Unit,
    onContinueWithoutAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EchoSpace12),
    ) {
        Text(
            text = stageText,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = budgetText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ProcessingBudgetMeterTag),
        )
        CosmosSecondaryButton(label = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        CosmosSecondaryButton(
            label = "Continue without AI",
            onClick = onContinueWithoutAi,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ConstellationView(
    stars: List<ConstellationStar>,
    modifier: Modifier = Modifier,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    CanopyPanel(
        variant = CanopyVariant.Floating,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ProcessingConstellationTag),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(EchoSpace16),
        ) {
            val glyph = 48.dp
            val labelH = 44.dp
            val itemW = glyph + 64.dp
            val itemH = glyph + labelH
            val spanX = (maxWidth - itemW).coerceAtLeast(0.dp)
            val spanY = (maxHeight - itemH).coerceAtLeast(0.dp)
            // Star centres: the line and the glyphs share one layout, so
            // the route always runs through the stars it connects.
            val density = LocalDensity.current
            fun centerXPx(star: ConstellationStar): Float = with(density) {
                (place(star.x01, spanX, STAR_EDGE) + itemW / 2).toPx()
            }
            fun centerYPx(star: ConstellationStar): Float = with(density) {
                (place(star.y01, spanY, STAR_EDGE) + itemH / 2).toPx()
            }
            val routeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            Canvas(modifier = Modifier.matchParentSize()) {
                if (stars.size < 2) return@Canvas
                val line = Path().apply {
                    moveTo(centerXPx(stars.first()), centerYPx(stars.first()))
                    stars.drop(1).forEach { lineTo(centerXPx(it), centerYPx(it)) }
                }
                drawPath(line, color = routeColor, style = Stroke(width = 2.dp.toPx()))
            }
            stars.forEach { star ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .size(itemW, itemH)
                        .offset(x = place(star.x01, spanX, STAR_EDGE), y = place(star.y01, spanY, STAR_EDGE)),
                ) {
                    StarGlyph(
                        nodeId = star.nodeId,
                        state = star.state,
                        tier = tier,
                        description = "${star.label} — ${starStateLabel(star.state)}",
                    )
                    Text(text = star.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = starStateLabel(star.state),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StarGlyph(
    nodeId: String,
    state: StarState,
    tier: CosmosMotionTier,
    description: String,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val twinkle = state == StarState.SCOUTING && tier == CosmosMotionTier.FULL
    // Conditional by tier on purpose (EchoMark precedent): the infinite
    // transition must not exist off FULL, so REDUCED/STATIC can never
    // animate — parity is structural, not a flag someone can miss.
    if (twinkle) {
        val phase by rememberInfiniteTransition(label = "starTwinkle").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(STAR_TWINKLE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "starAlpha",
        )
        Canvas(
            modifier = modifier
                .size(48.dp)
                .testTag(ProcessingStarTagPrefix + nodeId)
                .semantics { contentDescription = description },
        ) {
            drawSparkle(primary.copy(alpha = phase))
        }
    } else {
        val alpha = when (state) {
            StarState.QUEUED -> 0.4f
            StarState.SCOUTING -> 0.7f
            StarState.GROUNDED -> 1f
            StarState.SKIPPED -> 0.6f
        }
        Canvas(
            modifier = modifier
                .size(48.dp)
                .testTag(ProcessingStarTagPrefix + nodeId)
                .semantics { contentDescription = description },
        ) {
            when (state) {
                StarState.QUEUED -> {
                    drawCircle(color = dim.copy(alpha = alpha), radius = 4.dp.toPx())
                }
                StarState.SCOUTING -> drawSparkle(primary.copy(alpha = alpha))
                StarState.GROUNDED -> {
                    drawCircle(color = primary.copy(alpha = alpha), radius = 7.dp.toPx())
                    drawCircle(
                        color = primary.copy(alpha = alpha),
                        radius = 11.dp.toPx(),
                        style = Stroke(width = 1.5f.dp.toPx()),
                    )
                }
                StarState.SKIPPED -> {
                    drawCircle(
                        color = dim.copy(alpha = alpha),
                        radius = 6.dp.toPx(),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkle(color: androidx.compose.ui.graphics.Color) {
    val r = 10.dp.toPx()
    drawLine(color, androidx.compose.ui.geometry.Offset(center.x - r, center.y), androidx.compose.ui.geometry.Offset(center.x + r, center.y), strokeWidth = 2.5f.dp.toPx())
    drawLine(color, androidx.compose.ui.geometry.Offset(center.x, center.y - r), androidx.compose.ui.geometry.Offset(center.x, center.y + r), strokeWidth = 2.5f.dp.toPx())
    drawCircle(color = color, radius = 2.5f.dp.toPx())
}

private const val STAR_TWINKLE_MS = 900

/** Clears this much panel on both sides so edge labels are never clipped. */
private val STAR_EDGE = 4.dp

/**
 * Top-left offset for a unit fraction inside [span] (max top-left), kept
 * `edge` clear of both sides; centres the item when there is no room.
 */
private fun place(frac: Float, span: Dp, edge: Dp): Dp {
    val hi = span - edge
    if (hi <= edge) return (span / 2).coerceAtLeast(0.dp)
    return (span * frac.coerceIn(0f, 1f)).coerceIn(edge, hi)
}
