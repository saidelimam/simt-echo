package app.simt.echo.ui.atmosphere

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoExtras

// Echo field (plan W13-01, docs/research/luminous-freeze.md §5): the living
// atmosphere behind every shell surface. An aurora lives in the horizon
// band (top) and a faint floor glow (bottom deck); the body band where copy
// sits stays the flat void (freeze §1.5). Ripples bend it; tilt shifts it.
//
// Renderers: the AGSL shader on Android 13+ (FULL and REDUCED — REDUCED is
// one still frame), else [CanvasEchoField] with the same bands as static
// gradients. The Cosmos edge-band starfield draws on top (dark only).
//
// Cost contract: uniforms and brushes are remembered; the frame loop runs
// only on the FULL tier, at ≤ 30 fps while idle and every frame while a
// ripple travels; every per-frame read happens in the draw phase, so the
// loop never recomposes.

const val EchoFieldTestTag = "EchoField"

/** Idle aurora frame interval: the field breathes at 30 fps, not display rate. */
internal const val ECHO_FIELD_IDLE_FRAME_MS = 33L

/** Seamless echo-field loop: the shader's motion returns to its first frame here. */
internal const val ECHO_FIELD_LOOP_SEC = 60f

/** [ECHO_FIELD_LOOP_SEC] in milliseconds, for the frame clock. */
internal const val ECHO_FIELD_LOOP_MS = 60_000L

/**
 * Shader time, wrapped to [ECHO_FIELD_LOOP_SEC]. Bounding it keeps a field
 * left running for days from losing float precision (which showed up as a
 * stuttering, then frozen, aurora), and the shader is periodic in this
 * period, so the wrap itself is invisible.
 */
internal fun echoFieldLoopSec(nowMs: Long): Float {
    val wrapped = ((nowMs % ECHO_FIELD_LOOP_MS) + ECHO_FIELD_LOOP_MS) % ECHO_FIELD_LOOP_MS
    return wrapped / 1000f
}

/** Star parallax: degrees of starfield rotation per unit of tilt. */
private const val TILT_STAR_DEGREES = 1.2f

@Composable
fun EchoField(
    modifier: Modifier = Modifier,
    ripples: EchoRippleState? = LocalEchoRipples.current,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    val scheme = MaterialTheme.colorScheme
    val extras = EchoExtras
    val isLight = remember(scheme.background) { scheme.background.luminance() > 0.5f }
    val animated = tier == CosmosMotionTier.FULL
    val shader = if (tier == CosmosMotionTier.STATIC) null else rememberEchoFieldShader()
    val tilt = rememberDeviceTilt(enabled = tier.allowsParallax)
    val uniforms = remember(isLight, scheme, extras) {
        EchoFieldUniforms().apply {
            if (isLight) {
                // Dawn horizon (freeze §5 / §9 D1): pale, warm, no floor glow.
                void = scheme.background
                warm = Color(0xFFFFD9B8)
                rose = Color(0xFFF7CFE0)
                cool = Color(0xFFF3E0F7)
                intensity = 0.55f
                floor = 0f
            } else {
                void = scheme.background
                warm = extras.ember
                rose = extras.rose
                cool = extras.violet
                intensity = 0.62f
            }
        }
    }
    val fallback = remember { CanvasEchoField() }
    val frameMs = remember { mutableLongStateOf(0L) }
    if (animated) {
        LaunchedEffect(ripples) {
            var last = 0L
            while (true) {
                withInfiniteAnimationFrameNanos { }
                val now = ripples?.nowMs() ?: (last + ECHO_FIELD_IDLE_FRAME_MS)
                val travelling = ripples?.anyAlive(now) == true
                if (travelling || now - last >= ECHO_FIELD_IDLE_FRAME_MS) {
                    frameMs.longValue = now
                    last = now
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().testTag(EchoFieldTestTag)) {
            val now = frameMs.longValue
            uniforms.timeSec = if (animated) echoFieldLoopSec(now) else 0f
            uniforms.tilt = tilt.value
            if (animated && ripples != null) {
                ripples.writeUniforms(now, minOf(size.width, size.height), uniforms.ripples)
            } else {
                uniforms.ripples.fill(0f)
            }
            (shader ?: fallback).draw(this, uniforms)
        }
        if (!isLight) {
            CosmosBackground(
                variant = CosmosVariant.Panel,
                tier = tier,
                drawField = false,
                parallaxRotationDegrees = { tilt.value.x * TILT_STAR_DEGREES },
            )
        }
    }
}

/**
 * The field without runtime shaders (API < 33, iOS, STATIC tier): the same
 * horizon and floor bands as still gradients, ripples as fading rings.
 */
internal class CanvasEchoField : EchoFieldRenderer {
    override fun draw(scope: DrawScope, uniforms: EchoFieldUniforms) = with(scope) {
        if (size.width <= 0f || size.height <= 0f) return@with
        drawRect(color = uniforms.void)
        val horizonPx = size.height * uniforms.horizon
        val a = uniforms.intensity
        drawRect(
            brush = Brush.verticalGradient(
                0f to uniforms.rose.copy(alpha = 0.55f * a),
                0.45f to uniforms.cool.copy(alpha = 0.30f * a),
                1f to Color.Transparent,
                startY = 0f,
                endY = horizonPx,
            ),
            size = size.copy(height = horizonPx),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(uniforms.warm.copy(alpha = 0.45f * a), Color.Transparent),
                center = Offset(size.width * (0.2f + 0.05f * uniforms.tilt.x), 0f),
                radius = size.width * 0.75f,
            ),
            size = size.copy(height = horizonPx),
        )
        if (uniforms.floor > 0f) {
            val floorTop = size.height * (1f - uniforms.floor)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to uniforms.warm.copy(alpha = 0.22f * a),
                    startY = floorTop,
                    endY = size.height,
                ),
                topLeft = Offset(0f, floorTop),
                size = size.copy(height = size.height - floorTop),
            )
        }
        val ripples = uniforms.ripples
        for (i in 0 until ECHO_RIPPLE_SLOTS) {
            val alpha = ripples[i * 4 + 3]
            if (alpha <= 0f) continue
            drawCircle(
                color = uniforms.warm.copy(alpha = alpha),
                radius = ripples[i * 4 + 2],
                center = Offset(ripples[i * 4], ripples[i * 4 + 1]),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
