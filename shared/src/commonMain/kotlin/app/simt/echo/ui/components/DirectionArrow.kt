package app.simt.echo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * Wraps any angle to (-π, π]. Pure so the navigator and the arrow agree
 * on the same wrap (plan §6 heading convention, clockwise-positive yaw).
 */
internal fun wrapPi(angleRad: Float): Float {
    var a = angleRad % (2f * PI.toFloat())
    if (a > PI) a -= 2f * PI.toFloat()
    if (a < -PI) a += 2f * PI.toFloat()
    return a
}

/**
 * 200 dp navigation chevron (plan §8.3). The arrow animates with a spring
 * and unwraps 359°→0° transitions so it never spins a full turn.
 * [contentDescription] names the arrow for TalkBack (usually the current
 * instruction); null leaves the canvas decorative.
 */
@Composable
fun DirectionArrow(
    angleRad: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    val animated = remember { Animatable(angleRad) }
    LaunchedEffect(angleRad) {
        val delta = wrapPi(angleRad - animated.value)
        if (delta != 0f) {
            animated.animateTo(
                animated.value + delta,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 300f,
                ),
            )
        }
    }
    val a11y = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.size(200.dp).then(a11y)) {
        rotate(degrees = animated.value * 180f / PI.toFloat()) {
            val w = size.width
            val h = size.height
            val chevron = Path().apply {
                moveTo(w * 0.5f, h * 0.12f)
                lineTo(w * 0.82f, h * 0.62f)
                lineTo(w * 0.62f, h * 0.62f)
                lineTo(w * 0.62f, h * 0.88f)
                lineTo(w * 0.38f, h * 0.88f)
                lineTo(w * 0.38f, h * 0.62f)
                lineTo(w * 0.18f, h * 0.62f)
                close()
            }
            drawPath(chevron, color)
            drawCircle(
                color = color,
                radius = w * 0.05f,
                center = Offset(w * 0.5f, h * 0.5f),
                alpha = 0.25f,
            )
        }
    }
}
