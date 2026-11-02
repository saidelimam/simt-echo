package app.simt.echo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import simt_echo.shared.generated.resources.Res
import simt_echo.shared.generated.resources.el_messiri_bold
import simt_echo.shared.generated.resources.el_messiri_medium
import simt_echo.shared.generated.resources.el_messiri_regular
import simt_echo.shared.generated.resources.el_messiri_semibold
import simt_echo.shared.generated.resources.space_grotesk

// Bundled display + Arabic families, freeze §6 (docs/research/cosmos-echo-freeze.md).
// Space Grotesk (Latin titles + instrumentation, variable 300–700) and
// El Messiri (every Arabic role, static weights) ship as ECHO-owned assets
// under SIL OFL 1.1 — see shared/fonts/. Android body text stays on the
// native system font per [P-R C07]; Source Sans 3 is web-only and is not
// bundled. CMP resource-backed Font() is @Composable, so these families are
// built in composition with system fallback declared via FontFamily default
// handling; callers use [rememberEchoTypography] / [rememberEchoArabicTypography].
//
// APK packaging contract: :shared:copyAndroidMainComposeResourcesToAndroidAssets
// is not wired under this AGP/KMP combo (orphaned task, unset outputDirectory),
// so the device runtime path is mirrored by hand at
// androidApp/src/main/assets/composeResources/simt_echo.shared.generated.resources/font/
// (exact asset path the generated FontResource items resolve). Keep the two
// copies byte-identical; the prefix derives from the generated Res package.

// Variable Space Grotesk: one file, weights 300–700 via variation settings.
@OptIn(ExperimentalTextApi::class)
@Composable
internal fun rememberEchoDisplayFontFamily(): FontFamily = FontFamily(
    Font(Res.font.space_grotesk, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(Res.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(Res.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(Res.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(Res.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

// Static El Messiri weights, wired per [P-R C07] (bundle fonts, declare
// fallbacks). Per-locale selection is Wave 8 screen work; the theme exposes
// this family plus [rememberEchoArabicTypography].
@Composable
internal fun rememberEchoArabicFontFamily(): FontFamily = FontFamily(
    Font(Res.font.el_messiri_regular, FontWeight.Normal),
    Font(Res.font.el_messiri_medium, FontWeight.Medium),
    Font(Res.font.el_messiri_semibold, FontWeight.SemiBold),
    Font(Res.font.el_messiri_bold, FontWeight.Bold),
)
