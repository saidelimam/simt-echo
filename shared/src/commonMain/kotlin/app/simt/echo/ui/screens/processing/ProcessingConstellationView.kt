package app.simt.echo.ui.screens.processing

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16
import kotlin.math.roundToInt

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
        val routeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        ConstellationLayout(
            stars = stars,
            routeColor = routeColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace16),
        ) { star -> StarItem(star = star, tier = tier) }
    }
}

/**
 * One star: glyph above its label and state word. Merged into a single
 * "label — State" node for TalkBack (the glyph and both Texts would
 * otherwise read the same fact three times); the glyph is decorative.
 * Sized by its content, so a larger font scale grows it instead of
 * clipping it.
 */
@Composable
private fun StarItem(star: ConstellationStar, tier: CosmosMotionTier) {
    val state = starStateLabel(star.state)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .testTag(ProcessingStarTagPrefix + star.nodeId)
            .semantics(mergeDescendants = true) { contentDescription = "${star.label} — $state" },
    ) {
        StarGlyph(state = star.state, tier = tier)
        Text(text = star.label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            text = state,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Lays the stars out by their unit-space positions with content-driven
 * sizing: each item wraps its label (capped at a share of the width, so
 * long labels wrap), and the panel is as tall as two tallest items — at
 * least [CONSTELLATION_MIN_HEIGHT] — so nothing clips at any font scale.
 * The route line is composed after measuring (SubcomposeLayout), through
 * the glyph centres the placement just computed, so the line and the
 * stars always share one layout.
 */
@Composable
private fun ConstellationLayout(
    stars: List<ConstellationStar>,
    routeColor: Color,
    modifier: Modifier = Modifier,
    item: @Composable (ConstellationStar) -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val itemLimit = Constraints(maxWidth = (width * STAR_MAX_WIDTH_FRACTION).roundToInt().coerceAtLeast(0))
        val placeables = subcompose(ConstellationSlot.Stars) { stars.forEach { item(it) } }
            .map { it.measure(itemLimit) }
        val itemH = placeables.maxOfOrNull { it.height } ?: 0
        // Crowded routes get more vertical room: two stars per row, and an
        // extra row for odd counts, so the separation pass has space to
        // work with instead of only clamping at the edges (review 0013 F7).
        val rows = ((stars.size + 1) / 2).coerceAtLeast(2)
        val height = maxOf(CONSTELLATION_MIN_HEIGHT.roundToPx(), itemH * rows)
        val edge = STAR_EDGE.roundToPx()
        val glyphHalf = STAR_GLYPH.roundToPx() / 2
        val spanY = height - itemH
        val origins = separateStarOrigins(
            origins = placeables.mapIndexed { i, placeable ->
                val star = stars[i]
                IntOffset(
                    x = placePx(star.x01, width - placeable.width, edge),
                    y = placePx(star.screenY01(), spanY, edge),
                )
            },
            widths = placeables.map { it.width },
            heights = placeables.map { it.height },
            width = width,
            height = height,
            edge = edge,
        )
        val centres = origins.mapIndexed { i, origin ->
            Offset((origin.x + placeables[i].width / 2).toFloat(), (origin.y + glyphHalf).toFloat())
        }
        val route = subcompose(ConstellationSlot.Route) { RouteLine(centres, routeColor) }
            .map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) {
            route.forEach { it.place(0, 0) }
            placeables.forEachIndexed { i, placeable -> placeable.place(origins[i]) }
        }
    }
}

private enum class ConstellationSlot { Stars, Route }

@Composable
private fun RouteLine(centres: List<Offset>, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (centres.size < 2) return@Canvas
        val line = Path().apply {
            moveTo(centres.first().x, centres.first().y)
            centres.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(line, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun StarGlyph(
    state: StarState,
    tier: CosmosMotionTier,
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
        Canvas(modifier = modifier.size(STAR_GLYPH)) {
            drawSparkle(primary.copy(alpha = phase))
        }
    } else {
        val alpha = when (state) {
            StarState.QUEUED -> 0.4f
            StarState.SCOUTING -> 0.7f
            StarState.GROUNDED -> 1f
            StarState.SKIPPED -> 0.6f
        }
        Canvas(modifier = modifier.size(STAR_GLYPH)) {
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

/** Glyph box; the route line runs through its centre. */
private val STAR_GLYPH = 48.dp

/** Panel content never shorter than this (at 1x font scale it is exactly this tall). */
private val CONSTELLATION_MIN_HEIGHT = 188.dp

/** A star's label wraps at this share of the panel width. */
private const val STAR_MAX_WIDTH_FRACTION = 0.4f

/** Clears this much panel on both sides so edge labels are never clipped. */
private val STAR_EDGE = 4.dp

/**
 * Screen-space vertical fraction (0 = top). The path's y grows forward
 * and the Review map draws it up (it plots -y), so the constellation
 * flips y01 to read the same way round as the map.
 */
internal fun ConstellationStar.screenY01(): Float = 1f - y01.coerceIn(0f, 1f)

/**
 * Top-left offset (px) for a unit fraction inside [span] (max top-left),
 * kept [edge] clear of both sides; centres the item when there is no room.
 */
private fun placePx(frac: Float, span: Int, edge: Int): Int {
    val hi = span - edge
    if (hi <= edge) return (span / 2).coerceAtLeast(0)
    return (span * frac.coerceIn(0f, 1f)).roundToInt().coerceIn(edge, hi)
}

/**
 * Pushes overlapping star origins apart along their smaller overlap axis,
 * a few passes, clamped inside the panel. Labels from nearby checkpoints
 * would otherwise sit on top of each other at large font scales.
 *
 * Pure and deterministic; returns [origins] unchanged when nothing
 * overlaps.
 */
internal fun separateStarOrigins(
    origins: List<IntOffset>,
    widths: List<Int>,
    heights: List<Int>,
    width: Int,
    height: Int,
    edge: Int,
    passes: Int = 6,
): List<IntOffset> {
    if (origins.size < 2) return origins
    val out = origins.toMutableList()
    repeat(passes) {
        var moved = false
        for (i in out.indices) {
            for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                val overlapX = minOf(a.x + widths[i], b.x + widths[j]) - maxOf(a.x, b.x)
                val overlapY = minOf(a.y + heights[i], b.y + heights[j]) - maxOf(a.y, b.y)
                if (overlapX <= 0 || overlapY <= 0) continue
                if (overlapX <= overlapY) {
                    val push = overlapX / 2 + 1
                    val maxA = (width - widths[i] - edge).coerceAtLeast(edge)
                    val maxB = (width - widths[j] - edge).coerceAtLeast(edge)
                    out[i] = if (b.x >= a.x) {
                        a.copy(x = (a.x - push).coerceIn(edge, maxA))
                    } else {
                        a.copy(x = (a.x + push).coerceIn(edge, maxA))
                    }
                    out[j] = if (b.x >= a.x) {
                        b.copy(x = (b.x + push).coerceIn(edge, maxB))
                    } else {
                        b.copy(x = (b.x - push).coerceIn(edge, maxB))
                    }
                } else {
                    val push = overlapY / 2 + 1
                    val maxA = (height - heights[i] - edge).coerceAtLeast(edge)
                    val maxB = (height - heights[j] - edge).coerceAtLeast(edge)
                    out[i] = if (b.y >= a.y) {
                        a.copy(y = (a.y - push).coerceIn(edge, maxA))
                    } else {
                        a.copy(y = (a.y + push).coerceIn(edge, maxA))
                    }
                    out[j] = if (b.y >= a.y) {
                        b.copy(y = (b.y + push).coerceIn(edge, maxB))
                    } else {
                        b.copy(y = (b.y - push).coerceIn(edge, maxB))
                    }
                }
                moved = true
            }
        }
        if (!moved) return out
    }
    return out
}
