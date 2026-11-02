package app.simt.echo.ui.brand

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier

// "Echo Step" brand mark (docs/research/luminous-freeze.md §2): one
// footprint whose outline ripples outward in up to three contour echoes.
// Geometry is the freeze's 108-unit grid, identical to the launcher source
// docs/design/echo-step.svg, so the in-app mark and the icon never drift.
//
// The contour echoes are bands cut out of thick strokes on an offscreen
// layer (stroke, then clear a thinner stroke), which follows the union of
// forefoot + heel exactly as the SVG mask does. The idle echo (FULL tier
// only) is a separate layer underneath: a scaled outline leaving the foot
// every [ECHO_IDLE_PERIOD_MS] and fading by [ECHO_IDLE_MAX_SCALE].

const val ECHO_IDLE_PERIOD_MS = 2_400
const val ECHO_IDLE_MAX_SCALE = 1.8f

/** Mark tilt: a step taken toward the upper right. */
private const val MARK_ROTATION_DEG = 18f

/** The mark fills 88 % of its box, as in the adaptive-icon safe zone. */
private const val MARK_SCALE = 0.88f
private const val GRID = 108f

private val SoleTop = Color(0xFFFFF8F2)
private val SoleBottom = Color(0xFFFFC766)

/** Field glow stops, same as `#fieldGlow` in docs/design/echo-step.svg. */
private val FieldStops = arrayOf(
    0f to Color(0xFF3B1A47),
    0.55f to Color(0xFF1D1220),
    1f to Color(0xFF120A14),
)

/**
 * One contour echo: a band between two outline offsets (mark units, as
 * full stroke widths) in a freeze colour at an alpha.
 */
data class EchoBand(
    val outerStroke: Float,
    val innerStroke: Float,
    val color: Color,
    val alpha: Float,
)

/**
 * The freeze §2 echo bands, innermost first. [count] is clamped to 0..3;
 * the top chrome draws 1, the launcher and hero draw 3.
 */
fun echoBands(count: Int): List<EchoBand> = listOf(
    EchoBand(outerStroke = 9f, innerStroke = 5f, color = Color(0xFFFF8C42), alpha = 1f),
    EchoBand(outerStroke = 19f, innerStroke = 14.5f, color = Color(0xFFFF7E8A), alpha = 0.75f),
    EchoBand(outerStroke = 30f, innerStroke = 25f, color = Color(0xFFC9A0FF), alpha = 0.3f),
).take(count.coerceIn(0, 3))

/** Forefoot + heel in mark space (centre 0,0), freeze §2. */
internal fun footPath(): Path = Path().apply {
    addOval(Rect(left = -8.6f, top = -21.5f, right = 8.6f, bottom = 3.5f))
    addOval(Rect(left = -7.4f, top = 6.3f, right = 5f, bottom = 20.7f))
}

/**
 * Draws the Echo Step mark. Decorative unless [contentDescription] is set.
 * [onDisc] puts it on its own void field, as the launcher icon does — the
 * mark never sits bare on Daylight (freeze §2). [idleEcho] adds the idle
 * echo loop, which runs only on the FULL tier (REDUCED/STATIC show the
 * same static mark — Cosmos C17 parity).
 */
@Composable
fun EchoMark(
    modifier: Modifier = Modifier,
    markSize: Dp = 48.dp,
    echoes: Int = 3,
    onDisc: Boolean = false,
    idleEcho: Boolean = false,
    contentDescription: String? = null,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    val foot = remember { footPath() }
    val bands = remember(echoes) { echoBands(echoes) }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Box(modifier = modifier.size(markSize).then(semanticsModifier)) {
        if (onDisc) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = minOf(size.width, size.height) / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = FieldStops,
                        center = Offset(center.x - r * 0.3f, center.y - r * 0.44f),
                        radius = r * 1.76f,
                    ),
                    radius = r,
                )
            }
        }
        if (idleEcho && tier == CosmosMotionTier.FULL) {
            val phase = rememberInfiniteTransition(label = "echoIdle").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(ECHO_IDLE_PERIOD_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "echoPhase",
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = phase.value
                markSpace {
                    val scale = 1f + (ECHO_IDLE_MAX_SCALE - 1f) * p
                    withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                        drawPath(
                            path = foot,
                            color = Color(0xFFFF7E8A).copy(alpha = 0.6f * (1f - p)),
                            style = Stroke(width = 1.6f / scale),
                        )
                    }
                }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            markSpace {
                for (band in bands.asReversed()) {
                    drawPath(foot, band.color.copy(alpha = band.alpha), style = Stroke(width = band.outerStroke))
                    drawPath(foot, Color.Black, style = Stroke(width = band.innerStroke), blendMode = BlendMode.Clear)
                }
                drawPath(
                    path = foot,
                    brush = Brush.verticalGradient(listOf(SoleTop, SoleBottom), startY = -21.5f, endY = 20.7f),
                )
            }
        }
    }
}

/** Runs [block] in mark space: origin at the box centre, freeze tilt, 108-unit grid. */
private inline fun DrawScope.markSpace(crossinline block: DrawScope.() -> Unit) {
    val unit = minOf(size.width, size.height) / GRID * MARK_SCALE
    withTransform({
        translate(left = size.width / 2f, top = size.height / 2f)
        rotate(MARK_ROTATION_DEG, pivot = Offset.Zero)
        scale(unit, unit, pivot = Offset.Zero)
    }) {
        block()
    }
}
