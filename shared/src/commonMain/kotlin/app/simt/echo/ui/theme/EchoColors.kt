package app.simt.echo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ECHO design tokens, implementation plan §8.1. Dark-first: the phone is
// used in dim garages, so dark is the default theme.

//region Base palette
internal val EchoBgDark = Color(0xFF0B0D10)
internal val EchoSurfaceDark = Color(0xFF15181D)
internal val EchoSurfaceHighDark = Color(0xFF1E2228)
internal val EchoOnBgDark = Color(0xFFF2F4F7)
internal val EchoMutedDark = Color(0xFF9AA3AE)

internal val EchoBgLight = Color(0xFFF7F8FA)
internal val EchoSurfaceLight = Color(0xFFFFFFFF)
internal val EchoSurfaceHighLight = Color(0xFFEEF0F3)
internal val EchoOnBgLight = Color(0xFF0B0D10)
internal val EchoMutedLight = Color(0xFF5B6470)

internal val EchoLimeDark = Color(0xFFC6F432)
internal val EchoLimeLight = Color(0xFF4E7A00)
//endregion

//region Status colors (plan §8.1)
// Deviation from §8.1's identical-in-both-themes status colors: EchoDanger
// is only 2.87:1 on the light background, below the 4.5:1 text gate, so the
// light scheme uses a darker red from the same hue family.
internal val EchoWarn = Color(0xFFFFB020)
internal val EchoDanger = Color(0xFFFF5A5F)
internal val EchoDangerLight = Color(0xFFCC2A30)
internal val EchoOk = Color(0xFF3DDC97)
//endregion

/**
 * Tokens that do not fit `ColorScheme`: status colors plus the muted
 * on-background used for secondary text. Provided by [EchoTheme].
 */
@Immutable
data class EchoExtraColors(
    val warn: Color,
    val danger: Color,
    val ok: Color,
    val muted: Color,
)

internal val LocalEchoExtraColors = staticCompositionLocalOf {
    EchoExtraColors(
        warn = EchoWarn,
        danger = EchoDanger,
        ok = EchoOk,
        muted = EchoMutedDark,
    )
}

//region Shape, spacing, touch targets (plan §8.1)
internal val EchoCardRadius = 28.dp
internal val EchoSheetRadius = 24.dp
internal val EchoPillRadius = 999.dp

internal val EchoTouchPrimary = 56.dp
internal val EchoTouchOther = 48.dp
internal val EchoRecordButton = 88.dp
internal val EchoQrMin = 260.dp
//endregion
