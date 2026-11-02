package app.simt.echo.ui.atmosphere

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset

// iOS is out of v1/2.0 scope (W5-03): the field draws its Canvas fallback
// and no tilt. These actuals keep the iOS compile gate green.

@Composable
actual fun rememberEchoFieldShader(): EchoFieldRenderer? = null

@Composable
actual fun rememberDeviceTilt(enabled: Boolean): State<Offset> = remember { mutableStateOf(Offset.Zero) }
