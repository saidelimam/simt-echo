package app.simt.echo.ui.screens.arrived

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionEasings
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.motion.LuminousSprings
import app.simt.echo.ui.theme.EchoExtras
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

// Arrival bloom (plan W13-11, luminous-freeze §4 "bloom"): the success
// state's mark. Signal: arriving. FULL blooms the disc on the expressive
// spring, sends eight short rays out and fading, then traces the check;
// REDUCED fades the final mark in; STATIC shows it. Decorative — the
// destination heading and the summary carry the meaning.

private const val RAYS = 8

@Composable
internal fun ArrivalBloom(
    modifier: Modifier = Modifier,
    bloomSize: Dp = 120.dp,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    val extras = EchoExtras
    val success = extras.success
    // The check sits on the success disc: the scheme background reads on
    // it in both schemes (void on mint, cream on forest).
    val ink = MaterialTheme.colorScheme.background
    val bloom = remember { Animatable(if (tier == CosmosMotionTier.FULL) 0f else 1f) }
    val rays = remember { Animatable(if (tier == CosmosMotionTier.FULL) 0f else 1f) }
    val check = remember { Animatable(if (tier == CosmosMotionTier.FULL) 0f else 1f) }
    val fade = remember { Animatable(if (tier == CosmosMotionTier.REDUCED) 0f else 1f) }
    LaunchedEffect(Unit) {
        when (tier) {
            CosmosMotionTier.FULL -> listOf(
                async { bloom.animateTo(1f, LuminousSprings.spatialExpressive()) },
                async { rays.animateTo(1f, tween(700, easing = CosmosMotionEasings.Enter)) },
                async {
                    delay(220.milliseconds)
                    check.animateTo(1f, tween(420, easing = CosmosMotionEasings.Transform))
                },
            ).awaitAll()
            CosmosMotionTier.REDUCED -> fade.animateTo(1f, tween(CosmosMotionDurations.FEEDBACK_MS))
            CosmosMotionTier.STATIC -> Unit
        }
    }
    val checkPath = remember { Path() }
    val checkSegment = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier = modifier.size(bloomSize)) {
        val r = size.minDimension / 2f
        val alpha = fade.value
        // Rays: short strokes leaving the disc and fading as they travel.
        val rayT = rays.value
        if (rayT in 0.001f..0.999f) {
            for (i in 0 until RAYS) {
                val a = (i * 2.0 * kotlin.math.PI / RAYS).toFloat()
                val inner = r * (0.55f + 0.4f * rayT)
                val outer = inner + r * 0.18f * (1f - rayT)
                drawLine(
                    color = success.copy(alpha = 0.8f * (1f - rayT)),
                    start = Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                    end = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        val discR = r * 0.62f * bloom.value
        drawCircle(
            brush = Brush.radialGradient(
                listOf(success.copy(alpha = 0.35f * alpha), success.copy(alpha = 0f)),
                center = center,
                radius = r,
            ),
            radius = r,
        )
        drawCircle(color = success.copy(alpha = alpha), radius = discR)
        // Check mark, traced along its own length.
        checkPath.rewind()
        checkPath.moveTo(center.x - r * 0.26f, center.y + r * 0.02f)
        checkPath.lineTo(center.x - r * 0.06f, center.y + r * 0.22f)
        checkPath.lineTo(center.x + r * 0.28f, center.y - r * 0.18f)
        measure.setPath(checkPath, false)
        checkSegment.rewind()
        measure.getSegment(0f, measure.length * check.value, checkSegment, true)
        drawPath(
            path = checkSegment,
            color = ink.copy(alpha = alpha),
            style = Stroke(width = r * 0.1f, cap = StrokeCap.Round),
        )
    }
}
