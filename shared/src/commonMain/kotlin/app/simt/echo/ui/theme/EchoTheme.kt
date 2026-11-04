package app.simt.echo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.intl.Locale

// Semantic schemes from the freeze token tables (§2 Deep Space, §3 Daylight
// Nebula). Slots without a published role use the nearest frozen surface,
// marked [ECHO-local]; snapshot-only fills (freeze §8.4) are not copied.

// Deep Space, dark-first default. Ember is the primary agency channel
// [P-R C04]; void (#120A14) is the text on the primary-action fill, passing
// on every action-gradient stop (frozen pair void-on-ember 8.42 [P-C]).
internal val EchoDarkScheme = darkColorScheme(
    primary = CosmosEmber,
    onPrimary = CosmosOnActionDark,
    primaryContainer = CosmosHullRaised,
    onPrimaryContainer = CosmosInk,
    secondary = CosmosRoseDark,
    onSecondary = CosmosVoid,
    secondaryContainer = CosmosHullRaised,
    onSecondaryContainer = CosmosRoseDark,
    tertiary = CosmosVioletDark,
    onTertiary = CosmosVoid,
    tertiaryContainer = CosmosHullRaised,
    onTertiaryContainer = CosmosVioletDark,
    background = CosmosVoid,
    onBackground = CosmosInk,
    surface = CosmosHull,
    onSurface = CosmosInk,
    surfaceVariant = CosmosHullRaised,
    onSurfaceVariant = CosmosMutedDark,
    surfaceTint = CosmosEmber,
    surfaceContainerLowest = CosmosVoidEdge,
    surfaceContainerLow = CosmosHull,
    surfaceContainer = CosmosHullRaised,
    surfaceContainerHigh = CosmosHullHighest,
    surfaceContainerHighest = CosmosHullHighest,
    error = CosmosDangerDark,
    onError = CosmosVoid,
    errorContainer = CosmosHullRaised,
    onErrorContainer = CosmosDangerDark,
    outline = CosmosBoundaryDark,
    outlineVariant = CosmosLineDark,
    scrim = CosmosVoid,
    inverseSurface = CosmosDayBackground,
    inverseOnSurface = CosmosDayText,
    inversePrimary = CosmosDayAction,
)

// Daylight Nebula. Action text is dark, never assumed white [P-R C06]:
// onPrimary is void (#120A14), frozen pair onAction-on-action 4.64 [P-C].
// Flat background, no nebula/corona/rim/starfield (freeze §3 [P-R C27]).
internal val EchoLightScheme = lightColorScheme(
    primary = CosmosDayAction,
    onPrimary = CosmosDayOnAction,
    primaryContainer = CosmosDayRaised,
    onPrimaryContainer = CosmosDayText,
    secondary = CosmosDayRose,
    onSecondary = CosmosDayBackground,
    secondaryContainer = CosmosDayRaised,
    onSecondaryContainer = CosmosDayRose,
    tertiary = CosmosDayViolet,
    onTertiary = CosmosDayBackground,
    tertiaryContainer = CosmosDayRaised,
    onTertiaryContainer = CosmosDayViolet,
    background = CosmosDayBackground,
    onBackground = CosmosDayText,
    surface = CosmosDaySurface,
    onSurface = CosmosDayText,
    surfaceVariant = CosmosDayRaised,
    onSurfaceVariant = CosmosDayMuted,
    surfaceTint = CosmosDayAction,
    surfaceContainerLowest = CosmosDayBackground,
    surfaceContainerLow = CosmosDaySurface,
    surfaceContainer = CosmosDayRaised,
    surfaceContainerHigh = CosmosDayHighest,
    surfaceContainerHighest = CosmosDayHighest,
    error = CosmosDayDanger,
    onError = CosmosDayBackground,
    errorContainer = CosmosDayRaised,
    onErrorContainer = CosmosDayDanger,
    outline = CosmosDayBoundary,
    outlineVariant = CosmosDayLine,
    scrim = CosmosDayText,
    inverseSurface = CosmosVoid,
    inverseOnSurface = CosmosInk,
    inversePrimary = CosmosEmber,
)

/**
 * ECHO theme: frozen Cosmos schemes + full type scale + Shapes. Accent
 * channels ride a [CompositionLocal] because `ColorScheme` has no
 * ember/rose/violet/gold/cyan/moss slots (freeze §7).
 */
@Composable
fun EchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Screens draw on CosmosBackground (not an M3 Surface), so nothing
    // provides a content color and unstyled Text falls back to Black —
    // invisible on void (W7-12/W9-11 device finding). Provide the
    // scheme's on-background ink once, here; components that set
    // explicit colors are unaffected.
    val scheme = if (darkTheme) EchoDarkScheme else EchoLightScheme
    // Arabic has no glyphs in Space Grotesk: the whole scale switches to
    // El Messiri for an Arabic locale instead of drawing tofu (review 0013 F9).
    val typography = if (Locale.current.language == "ar") {
        rememberEchoArabicTypography()
    } else {
        rememberEchoTypography()
    }
    CompositionLocalProvider(
        LocalEchoExtraColors provides if (darkTheme) EchoDarkExtras else EchoLightExtras,
        LocalContentColor provides scheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = EchoShapes,
            content = content,
        )
    }
}

/** Accent + supporting-text tokens inside an [EchoTheme]. */
val EchoExtras: EchoExtraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalEchoExtraColors.current
