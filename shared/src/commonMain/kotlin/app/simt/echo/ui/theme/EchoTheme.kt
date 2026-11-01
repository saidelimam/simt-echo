package app.simt.echo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-first scaffold tokens (implementation plan §8). Slice W0-03 grows
// these into the full design system on simt-app `core/ui` conventions.
internal val EchoBackground = Color(0xFF0B0D10)
internal val EchoLime = Color(0xFFC6F432)
internal val EchoWarn = Color(0xFFFFB020)

private val EchoColorScheme = darkColorScheme(
    primary = EchoLime,
    background = EchoBackground,
    surface = EchoBackground,
    error = EchoWarn,
)

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EchoColorScheme,
        content = content,
    )
}
