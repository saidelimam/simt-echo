package app.simt.echo.ui.screens.record

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.ECHO_RIPPLE_REACH_OF_SHORT_EDGE
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.atmosphere.rippleFrame
import app.simt.echo.ui.theme.EchoExtras

const val StepRipplesTag = "EchoWalkRipples"

// Step ripples (freeze §4 ripple, Record variant): one ring per PdrEngine
// step event, leaving from alternating edge-band origins (footsteps) and
// travelling to 0.6 × short edge in 900 ms. Origins live in the outer
// 20 % bands — the centre 60 % (copy + instrument) is never touched.
//
// Draw-phase only like the echo field: the frame loop never recomposes,
// and below FULL tier the motif is absent entirely (the step chip carries
// the signal). Decorative to TalkBack — Canvas has no semantics.

/** True while any ripple in [ripples] is still travelling at [nowMs]. */
fun stepRipplesAlive(ripples: List<StepRipple>, nowMs: Long): Boolean =
    ripples.any { rippleFrame(nowMs - it.startMs) != null }

@Composable
fun StepRipples(
    ripples: List<StepRipple>,
    modifier: Modifier = Modifier,
    tier: CosmosMotionTier = cosmosMotionTier(),
    nowMs: () -> Long = { currentEpochMs() },
) {
    if (tier != CosmosMotionTier.FULL || ripples.isEmpty()) return
    val ember = EchoExtras.ember
    val frameMs = remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(ripples) {
        while (true) {
            withInfiniteAnimationFrameNanos { }
            val now = nowMs()
            frameMs.longValue = now
            if (!stepRipplesAlive(ripples, now)) break
        }
    }
    Canvas(modifier = modifier.fillMaxSize().testTag(StepRipplesTag)) {
        val now = frameMs.longValue
        val shortEdge = minOf(size.width, size.height)
        for (ripple in ripples) {
            val frame = rippleFrame(now - ripple.startMs) ?: continue
            drawCircle(
                color = ember.copy(alpha = frame.alpha),
                radius = frame.reach * ECHO_RIPPLE_REACH_OF_SHORT_EDGE * shortEdge,
                center = Offset(
                    x = size.width * ripple.xFraction(),
                    y = size.height * ripple.yFraction(),
                ),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
