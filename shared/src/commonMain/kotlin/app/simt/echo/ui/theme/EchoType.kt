package app.simt.echo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Full semantic type scale, freeze §5 (docs/research/cosmos-echo-freeze.md).
// Sizes/leadings are ECHO-local readings of [P-R C08]; families follow
// [P-T font] [P-R C07]: Space Grotesk for Latin titles + instrumentation,
// native system body on Android (Source Sans 3 is web-only), El Messiri for
// every Arabic role. Zero tracking default (freeze §5: no negative tracking),
// sentence case; tabular numerals + visible adjacent units on every
// distance/bearing/time readout [P-R C08]; verify at fontScale 2.0.

// Tabular-numeral feature baked into the instrument styles so animated
// readouts never jitter horizontally while values change.
internal const val EchoTnum = "tnum"

internal fun echoTypography(display: FontFamily, body: FontFamily): Typography = Typography(
    // Primary instrument 48–64 sp / 1.1: record distance, navigate arrow
    // readout, bearing. tnum baked in.
    displayLarge = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 62.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = EchoTnum,
    ),
    // Narrative display 40–48 sp / 1.1, entry only: first-run Ignition title.
    displayMedium = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = EchoTnum,
    ),
    // [ECHO-local] mirror of the narrative role for the remaining M3 slot.
    displaySmall = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    // Page title 28–32 sp / 1.2: screen titles.
    headlineLarge = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    // Section title 20–24 sp / 1.3: sheet titles, settings sections.
    headlineSmall = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.sp,
    ),
    // Control label 14–16 sp / 1.3: buttons, chips, compact status.
    // Buttons/chips sit on the system family with the titles (covers the
    // titleMedium slot); the label slots below are the same role.
    titleMedium = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    // Body 16 sp / 1.5: default reading, system family.
    bodyLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    // Supporting 14 sp / 1.45: help, metadata.
    bodyMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // [ECHO-local] mirror of the supporting role for the remaining M3 slot.
    bodySmall = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // [ECHO-local] mirrors of the control-label role.
    labelMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
)

// Default Cosmos typography — Space Grotesk display roles over system body.
// Built in composition because the bundled families are resource-backed.
@Composable
internal fun rememberEchoTypography(): Typography = echoTypography(
    display = rememberEchoDisplayFontFamily(),
    body = FontFamily.Default,
)

// Arabic typography — El Messiri for every role (Space Grotesk has no Arabic
// glyphs). Per-locale selection is Wave 8 screen work.
@Composable
internal fun rememberEchoArabicTypography(): Typography = echoTypography(
    display = rememberEchoArabicFontFamily(),
    body = rememberEchoArabicFontFamily(),
)
