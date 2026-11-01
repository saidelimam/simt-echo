package app.simt.echo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

internal val EchoDarkScheme = darkColorScheme(
    primary = EchoLimeDark,
    background = EchoBgDark,
    surface = EchoSurfaceDark,
    surfaceVariant = EchoSurfaceHighDark,
    onBackground = EchoOnBgDark,
    onSurface = EchoOnBgDark,
    error = EchoDanger,
)

internal val EchoLightScheme = lightColorScheme(
    primary = EchoLimeLight,
    background = EchoBgLight,
    surface = EchoSurfaceLight,
    surfaceVariant = EchoSurfaceHighLight,
    onBackground = EchoOnBgLight,
    onSurface = EchoOnBgLight,
    error = EchoDangerLight,
)

/**
 * ECHO theme. Extra status tokens ride a [CompositionLocal] because
 * `ColorScheme` has no warn/ok/muted slots (plan §8.1).
 */
@Composable
fun EchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extras = if (darkTheme) {
        EchoExtraColors(
            warn = EchoWarn,
            danger = EchoDanger,
            ok = EchoOk,
            muted = EchoMutedDark,
        )
    } else {
        EchoExtraColors(
            warn = EchoWarn,
            danger = EchoDangerLight,
            ok = EchoOk,
            muted = EchoMutedLight,
        )
    }
    CompositionLocalProvider(LocalEchoExtraColors provides extras) {
        MaterialTheme(
            colorScheme = if (darkTheme) EchoDarkScheme else EchoLightScheme,
            typography = EchoTypography,
            content = content,
        )
    }
}

/** Status tokens inside an [EchoTheme]. */
val EchoExtras: EchoExtraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalEchoExtraColors.current
