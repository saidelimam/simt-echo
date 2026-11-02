package app.simt.echo.ui.screens.record

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.motion.LuminousSprings
import app.simt.echo.ui.theme.EchoExtras
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

const val TrailRibbonTag = "EchoWalkTrail"
const val TrailNodeTagPrefix = "EchoWalkNode"

/** Ribbon height: a bottom band that never reaches the copy zone. */
val TrailRibbonHeight: Dp = 148.dp

/** Perspective compression per metre behind the head. */
internal const val TRAIL_PERSPECTIVE_PER_M = 0.12f

/** Node bloom hit area (fixed; the dot itself is 7 dp radius). */
internal val NodeBloomSize: Dp = 36.dp

/** Node bloom rays (freeze §4 bloom: 6 short rays, fading). */
private const val NODE_RAYS = 6

// Bottom trail ribbon: the recent odometer trail in pseudo-perspective,
// heading-up (the head faces screen-up, the past falls below). The path
// is state — it grows as samples land; only the TURN/MARK/START nodes
// bloom, bound to their signals. Canvas carries no semantics; the
// container announces "Walked trail: N turns, M spots".

@Composable
fun TrailRibbon(
    trail: List<TrailPoint>,
    headYaw: Double?,
    modifier: Modifier = Modifier,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    if (trail.isEmpty()) return
    val yaw = headYaw ?: trail.last().yawRad
    val turns = trail.count { it.node == TrailNodeKind.TURN }
    val marks = trail.count { it.node == TrailNodeKind.MARK }
    val path = MaterialTheme.colorScheme.onSurface
    // Yaw-independent: re-rotated at draw/layout, so a motion sample re-projects nothing.
    val offsets = remember(trail) { trailOffsetsFromHead(trail) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TrailRibbonHeight)
            .testTag(TrailRibbonTag)
            .semantics { contentDescription = "Walked trail: $turns turns, $marks spots" },
    ) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = with(density) { TrailRibbonHeight.toPx() }
        fun pointAt(i: Int): Offset = trailToPx(offsets[i].lateralM(yaw), offsets[i].behindM(yaw), wPx, hPx)
        Canvas(modifier = Modifier.fillMaxWidth().height(TrailRibbonHeight)) {
            val n = offsets.size
            for (i in 1 until n) {
                val t = i.toFloat() / (n - 1).coerceAtLeast(1)
                drawLine(
                    color = path.copy(alpha = 0.25f + 0.5f * t),
                    start = pointAt(i - 1),
                    end = pointAt(i),
                    strokeWidth = (1.dp.toPx() + 3.dp.toPx() * t),
                    cap = StrokeCap.Round,
                )
            }
        }
        trail.forEachIndexed { index, point ->
            val node = point.node ?: return@forEachIndexed
            // Keyed by id: a trimmed trail shifts indices, and an index-keyed
            // bloom would replay for a node that already bloomed.
            key(point.id) {
                NodeBloom(
                    kind = node,
                    tier = tier,
                    modifier = Modifier
                        .testTag("$TrailNodeTagPrefix:${node.name}:${point.id}")
                        .offset {
                            val pos = pointAt(index)
                            val half = (NodeBloomSize / 2).roundToPx()
                            IntOffset((pos.x - half).roundToInt(), (pos.y - half).roundToInt())
                        },
                )
            }
        }
    }
}

/** Head-frame metres → ribbon px: head at bottom-centre, perspective squeeze with depth. */
internal fun trailToPx(lateralM: Double, behindM: Double, widthPx: Float, heightPx: Float): Offset {
    val scale = minOf(widthPx, heightPx) / ECHO_WALK_TRAIL_WINDOW_M.toFloat()
    val depth = behindM.toFloat().coerceIn(0f, ECHO_WALK_TRAIL_WINDOW_M.toFloat())
    val squeeze = 1f / (1f + depth * TRAIL_PERSPECTIVE_PER_M)
    return Offset(
        x = widthPx / 2f + lateralM.toFloat() * scale * squeeze,
        y = heightPx * 0.94f - depth * scale * squeeze,
    )
}

/**
 * One annotated node: FULL scales 0→1 on the expressive spring with six
 * short rays fading (freeze bloom); REDUCED fades in; STATIC is final.
 * Draw-local animation state only — the ribbon never recomposes for it.
 */
@Composable
internal fun NodeBloom(
    kind: TrailNodeKind,
    tier: CosmosMotionTier,
    modifier: Modifier = Modifier,
) {
    val extras = EchoExtras
    val color = when (kind) {
        TrailNodeKind.TURN -> extras.ember
        TrailNodeKind.MARK -> extras.gold
        TrailNodeKind.START -> MaterialTheme.colorScheme.onSurface
    }
    val bloom = remember(kind) { Animatable(if (tier == CosmosMotionTier.FULL) 0f else 1f) }
    val rays = remember(kind) { Animatable(if (tier == CosmosMotionTier.FULL) 0f else 1f) }
    val fade = remember(kind) { Animatable(if (tier == CosmosMotionTier.REDUCED) 0f else 1f) }
    LaunchedEffect(kind, tier) {
        when (tier) {
            CosmosMotionTier.FULL -> {
                bloom.animateTo(1f, LuminousSprings.spatialExpressive())
                rays.animateTo(1f, tween(CosmosMotionDurations.CONTEXT_MS))
            }
            CosmosMotionTier.REDUCED -> fade.animateTo(1f, tween(CosmosMotionDurations.FEEDBACK_MS))
            CosmosMotionTier.STATIC -> {
                bloom.snapTo(1f)
                rays.snapTo(1f)
            }
        }
    }
    Canvas(modifier = modifier.size(NodeBloomSize)) {
        val r = 7.dp.toPx()
        val rayT = rays.value
        if (tier == CosmosMotionTier.FULL && rayT in 0.001f..0.999f && kind != TrailNodeKind.START) {
            for (i in 0 until NODE_RAYS) {
                val a = (i * 2.0 * PI / NODE_RAYS).toFloat()
                val inner = r * (1.4f + 1.2f * rayT)
                val outer = inner + r * 0.9f * (1f - rayT)
                drawLine(
                    color = color.copy(alpha = 0.8f * (1f - rayT)),
                    start = Offset(center.x + cos(a) * inner, center.y + sin(a) * inner),
                    end = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        val alpha = if (tier == CosmosMotionTier.REDUCED) fade.value else 1f
        if (kind == TrailNodeKind.START) {
            drawCircle(
                color = color.copy(alpha = 0.7f * alpha),
                radius = r,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        } else {
            val glowR = r * 2.2f * bloom.value.coerceAtLeast(0.01f)
            drawCircle(color = color.copy(alpha = 0.25f * alpha), radius = glowR, center = center)
            drawCircle(color = color.copy(alpha = alpha), radius = r * bloom.value.coerceAtLeast(0.01f), center = center)
        }
    }
}
