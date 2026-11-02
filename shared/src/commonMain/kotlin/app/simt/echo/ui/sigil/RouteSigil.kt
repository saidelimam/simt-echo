package app.simt.echo.ui.sigil

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionEasings
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.theme.EchoExtraColors
import app.simt.echo.ui.theme.EchoExtras

// Route sigil view (plan W13-03, luminous-freeze §6): the route's own walk
// as a tapered ribbon — thin and faint at the start, full at the
// destination — on a "seal" disc, with a dot per turn and a bloom at the
// destination. Decorative: no semantics; the route's name always sits
// beside it (never the only identifier).
//
// [trace] plays the trace motif once on entry (freeze §4): FULL draws the
// ribbon along its length, REDUCED fades the whole sigil in, STATIC shows
// it at once.

/** Trace time scales with the drawn length, inside the freeze's 600–1200 ms. */
internal fun sigilTraceMs(geometry: SigilGeometry): Int {
    var length = 0f
    for (i in 1 until geometry.xs.size) {
        val dx = geometry.xs[i] - geometry.xs[i - 1]
        val dy = geometry.ys[i] - geometry.ys[i - 1]
        length += kotlin.math.sqrt(dx * dx + dy * dy)
    }
    return (600 + length * 150f).toInt().coerceIn(600, 1200)
}

fun SigilAccent.color(extras: EchoExtraColors): Color = when (this) {
    SigilAccent.EMBER -> extras.ember
    SigilAccent.ROSE -> extras.rose
    SigilAccent.VIOLET -> extras.violet
    SigilAccent.GOLD -> extras.gold
}

@Composable
fun RouteSigil(
    geometry: SigilGeometry,
    modifier: Modifier = Modifier,
    sigilSize: Dp = 56.dp,
    trace: Boolean = false,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    val extras = EchoExtras
    val accent = geometry.accent.color(extras)
    val disc = MaterialTheme.colorScheme.surfaceContainer
    val rim = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val animate = trace && tier != CosmosMotionTier.STATIC
    val progress = remember(geometry) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(geometry, animate) {
        if (!animate) {
            progress.snapTo(1f)
        } else if (tier == CosmosMotionTier.FULL) {
            progress.animateTo(1f, tween(sigilTraceMs(geometry), easing = CosmosMotionEasings.Transform))
        } else {
            progress.animateTo(1f, tween(CosmosMotionDurations.FEEDBACK_MS))
        }
    }
    val travels = tier == CosmosMotionTier.FULL
    Canvas(modifier = modifier.size(sigilSize)) {
        val r = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), disc),
                center = Offset(center.x, center.y - r * 0.35f),
                radius = r * 1.3f,
            ),
            radius = r,
        )
        drawCircle(color = rim, radius = r, style = Stroke(width = 1.dp.toPx()))
        val p = progress.value
        // Fade-only reveal below FULL: the whole glyph at alpha p.
        val reveal = if (travels) 1f else p
        val drawnUpTo = if (travels) p else 1f
        val inner = r * 0.66f
        val n = geometry.xs.size
        val thin = 1.dp.toPx()
        val thick = maxOf(2.6.dp.toPx(), r * 0.1f)
        if (n == 1) {
            drawCircle(color = accent.copy(alpha = reveal), radius = thick, center = center)
            return@Canvas
        }
        val lastSegment = ((n - 1) * drawnUpTo).toInt()
        for (i in 1..lastSegment) {
            val t = i.toFloat() / (n - 1)
            drawLine(
                color = accent.copy(alpha = reveal * (0.35f + 0.65f * t)),
                start = Offset(center.x + geometry.xs[i - 1] * inner, center.y + geometry.ys[i - 1] * inner),
                end = Offset(center.x + geometry.xs[i] * inner, center.y + geometry.ys[i] * inner),
                strokeWidth = thin + (thick - thin) * t,
                cap = StrokeCap.Round,
            )
        }
        // Start: a small hollow ring where the walk began.
        drawCircle(
            color = ink.copy(alpha = 0.7f * reveal),
            radius = thick * 0.9f,
            center = Offset(center.x + geometry.xs[0] * inner, center.y + geometry.ys[0] * inner),
            style = Stroke(width = thin),
        )
        if (drawnUpTo >= 1f) {
            for (mark in geometry.marks) {
                val at = Offset(center.x + mark.x * inner, center.y + mark.y * inner)
                if (mark.destination) {
                    drawCircle(color = accent.copy(alpha = 0.25f * reveal), radius = thick * 2.4f, center = at)
                    drawCircle(color = accent.copy(alpha = reveal), radius = thick * 1.2f, center = at)
                } else {
                    drawCircle(color = ink.copy(alpha = 0.85f * reveal), radius = thick * 0.6f, center = at)
                }
            }
        }
    }
}
