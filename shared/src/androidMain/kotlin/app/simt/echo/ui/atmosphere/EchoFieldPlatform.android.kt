package app.simt.echo.ui.atmosphere

import android.content.Context
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

// Android echo field (W13-01): AGSL aurora on API 33+, gravity tilt for
// parallax. The shader is compiled once per composition and only its
// uniforms change per frame; a compile failure falls back to the Canvas
// field instead of crashing (freeze §5).

@Composable
actual fun rememberEchoFieldShader(): EchoFieldRenderer? = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { AgslEchoField() }.getOrNull()
    } else {
        null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslEchoField : EchoFieldRenderer {
    private val shader = RuntimeShader(ECHO_FIELD_AGSL)
    private val brush = ShaderBrush(shader)

    override fun draw(scope: DrawScope, uniforms: EchoFieldUniforms) = with(scope) {
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uTime", uniforms.timeSec)
        shader.setFloatUniform("uTilt", uniforms.tilt.x, uniforms.tilt.y)
        shader.setFloatUniform("uHorizon", uniforms.horizon)
        shader.setFloatUniform("uFloor", uniforms.floor)
        shader.setFloatUniform("uIntensity", uniforms.intensity)
        shader.setFloatUniform("uRipples", uniforms.ripples)
        shader.setColorUniform("uVoid", uniforms.void.toArgb())
        shader.setColorUniform("uWarm", uniforms.warm.toArgb())
        shader.setColorUniform("uRose", uniforms.rose.toArgb())
        shader.setColorUniform("uCool", uniforms.cool.toArgb())
        drawRect(brush = brush)
    }
}

/**
 * Aurora curtains in the horizon band + a floor glow, over the flat void.
 * Domain-warped value noise, 3 octaves (freeze §5 caps it there). Ripples
 * add a soft ring and push the noise outward where they pass.
 */
private const val ECHO_FIELD_AGSL = """
uniform float2 uSize;
uniform float uTime;
uniform float2 uTilt;
uniform float uHorizon;
uniform float uFloor;
uniform float uIntensity;
uniform float4 uRipples[4];
layout(color) uniform half4 uVoid;
layout(color) uniform half4 uWarm;
layout(color) uniform half4 uRose;
layout(color) uniform half4 uCool;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        v += amp * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fc) {
    float w = uSize.x;
    float2 disp = float2(0.0);
    float glow = 0.0;
    for (int i = 0; i < 4; i++) {
        float4 r = uRipples[i];
        if (r.w > 0.0) {
            float2 dv = fc - r.xy;
            float d = length(dv);
            float k = (d - r.z) / (w * 0.035);
            float band = exp(-k * k) * r.w;
            glow += band;
            disp += (dv / max(d, 1.0)) * band * w * 0.04;
        }
    }
    float2 p = (fc + disp) / w + uTilt * 0.035;
    float t = uTime;
    float2 q = float2(fbm(p * 1.8 + float2(0.0, t * 0.05)), fbm(p * 1.8 + float2(5.2, -t * 0.04)));
    // Curtains: noise stretched vertically reads as hanging aurora folds.
    float n = fbm(float2(p.x * 3.4, p.y * 0.55) + q * 1.3 + float2(t * 0.03, 0.0));
    float y = fc.y / uSize.y;
    float edge = uHorizon * (0.75 + 0.5 * q.y) + (n - 0.5) * 0.10;
    float sky = 1.0 - smoothstep(edge * 0.15, edge, y);
    // An aurora is brightest along its lower hem.
    float hemK = (y - edge * 0.78) / 0.045;
    float hem = exp(-hemK * hemK);
    float ground = smoothstep(1.0 - uFloor, 1.0, y) * 0.6;
    float curtain = smoothstep(0.32, 0.78, n);
    half3 aur = mix(uCool.rgb, uRose.rgb, half(smoothstep(0.3, 0.7, q.x)));
    aur = mix(aur, uWarm.rgb, half(smoothstep(0.5, 0.85, n)));
    float a = (sky * (0.2 + 0.8 * curtain) + hem * curtain * 0.9 + ground * curtain) * uIntensity + glow * 0.5;
    return half4(mix(uVoid.rgb, aur, half(clamp(a, 0.0, 1.0))), 1.0);
}
"""

/** Low-pass factor for gravity tilt (≈ 2 Hz at SENSOR_DELAY_UI). */
private const val TILT_ALPHA = 0.12f

@Composable
actual fun rememberDeviceTilt(enabled: Boolean): State<Offset> {
    val tilt = remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current.applicationContext
    LifecycleResumeEffect(enabled, context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val g = SensorManager.GRAVITY_EARTH
                val x = (-event.values[0] / g).coerceIn(-1f, 1f)
                val y = (event.values[1] / g - 0.7f).coerceIn(-1f, 1f)
                val last = tilt.value
                tilt.value = Offset(
                    last.x + TILT_ALPHA * (x - last.x),
                    last.y + TILT_ALPHA * (y - last.y),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && manager != null && sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            tilt.value = Offset.Zero
        }
        onPauseOrDispose {
            manager?.unregisterListener(listener)
        }
    }
    return tilt
}
