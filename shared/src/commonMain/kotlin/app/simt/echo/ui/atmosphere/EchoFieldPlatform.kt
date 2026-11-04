package app.simt.echo.ui.atmosphere

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

// Platform seams for the echo field (luminous-freeze §5): the AGSL shader
// (Android 13+) and device tilt. Both degrade to null/zero, never fail.

/** Everything the field needs for one frame. Reused, never reallocated per frame. */
class EchoFieldUniforms {
    var timeSec: Float = 0f
    var tilt: Offset = Offset.Zero
    var horizon: Float = 0.30f
    var floor: Float = 0.16f
    var intensity: Float = 0.35f

    /** Seamless-loop period; the shader's time-dependent offsets are periodic in it. */
    var loopSec: Float = ECHO_FIELD_LOOP_SEC

    var void: Color = Color.Black
    var warm: Color = Color.Black
    var rose: Color = Color.Black
    var cool: Color = Color.Black

    /** [ECHO_RIPPLE_SLOTS] × (x, y, radius px, alpha). */
    val ripples = FloatArray(ECHO_RIPPLE_SLOTS * 4)
}

/** Draws the whole field (void + aurora + ripples) for one frame. */
fun interface EchoFieldRenderer {
    fun draw(scope: DrawScope, uniforms: EchoFieldUniforms)
}

/** The AGSL renderer, or null where runtime shaders are unavailable (API < 33, iOS). */
@Composable
expect fun rememberEchoFieldShader(): EchoFieldRenderer?

/**
 * Device tilt as x/y in -1..1 (low-passed gravity), for parallax. Reads
 * the sensor only while [enabled] and the screen is resumed; otherwise zero.
 */
@Composable
expect fun rememberDeviceTilt(enabled: Boolean): State<Offset>
