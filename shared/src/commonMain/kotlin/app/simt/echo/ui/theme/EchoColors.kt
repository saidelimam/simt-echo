package app.simt.echo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Cosmos → ECHO frozen tokens, docs/research/cosmos-echo-freeze.md §2–§4.
// Every hex below is copied from the freeze (§2 Deep Space, §3 Daylight
// Nebula, §4 shapes/spacing); provenance tags match the freeze legend.
// Echo Lime (§1: retired, no Cosmos provenance) and snapshot-only aliases
// (freeze §8.4: line-strong, *Core containers, *Container fills) are
// deliberately absent — a container need is a new-slice proposal.

//region Deep Space (dark-first default), freeze §2 [P-T adapters.deepSpace.*]
internal val CosmosVoid = Color(0xFF120A14)
internal val CosmosVoidEdge = Color(0xFF1F1126)
internal val CosmosHull = Color(0xFF1D1220)
internal val CosmosHullRaised = Color(0xFF2A1830)
internal val CosmosHullHighest = Color(0xFF352040)
internal val CosmosInk = Color(0xFFFFF8F2)
internal val CosmosMutedDark = Color(0xFFBBAEBB)
internal val CosmosFaintDark = Color(0xFF9A8B9A)
internal val CosmosDimDark = Color(0xFF90839A)
internal val CosmosLineDark = Color(red = 255, green = 236, blue = 226, alpha = 31)
internal val CosmosBoundaryDark = Color(0xFF9A8B9A)
internal val CosmosEmber = Color(0xFFFF8C42)
internal val CosmosEmberSoft = Color(0xFFFFB27C)
internal val CosmosOnActionDark = Color(0xFF120A14)
internal val CosmosRoseDark = Color(0xFFFF7E8A)
internal val CosmosVioletDark = Color(0xFFC9A0FF)
internal val CosmosGoldDark = Color(0xFFFFC766)
internal val CosmosCyanDark = Color(0xFF00E5FF)
internal val CosmosMossDark = Color(0xFF83B692)
internal val CosmosDangerDark = Color(0xFFFF6978)
internal val CosmosSuccessDark = Color(0xFF71D5A4)
internal val CosmosPetrol = Color(0xFF102C2D)
internal val CosmosWater = Color(0xFF123D42)
//endregion

//region Daylight Nebula (light), freeze §3 [P-T adapters.daylight.*]
internal val CosmosDayBackground = Color(0xFFFFF6EC)
internal val CosmosDaySurface = Color(0xFFFFF9F2)
internal val CosmosDayRaised = Color(0xFFF6E7DC)
internal val CosmosDayHighest = Color(0xFFF3E0F7)
internal val CosmosDayText = Color(0xFF241A14)
internal val CosmosDayMuted = Color(0xFF52443C)
internal val CosmosDayFaint = Color(0xFF6B5F58)
internal val CosmosDayLine = Color(red = 36, green = 26, blue = 20, alpha = 36)
internal val CosmosDayBoundary = Color(0xFF52443C)
internal val CosmosDayAction = Color(0xFFD84E00)
internal val CosmosDayAccentText = Color(0xFFB04300)
internal val CosmosDayOnAction = Color(0xFF120A14)
internal val CosmosDayRose = Color(0xFFB83266)
internal val CosmosDayViolet = Color(0xFF7C4DBD)
internal val CosmosDayGold = Color(0xFF8B5E00)
internal val CosmosDayCyan = Color(0xFF006974)
internal val CosmosDayMoss = Color(0xFF356447)
internal val CosmosDayDanger = Color(0xFF9B2432)
internal val CosmosDaySuccess = Color(0xFF276442)
//endregion

/**
 * Accent + text tokens that do not fit `ColorScheme`: the frozen accent
 * grammar (freeze §1/§7 [P-R C04]) plus supporting-text roles. Provided
 * per-scheme by [EchoTheme]; at most one dominant + two supporting channels
 * per viewport (freeze §7).
 */
@Immutable
data class EchoExtraColors(
    val ember: Color,
    val emberSoft: Color,
    val rose: Color,
    val violet: Color,
    val gold: Color,
    val cyan: Color,
    val moss: Color,
    val danger: Color,
    val success: Color,
    val muted: Color,
    val faint: Color,
    val boundary: Color,
    val onAction: Color,
)

internal val EchoDarkExtras = EchoExtraColors(
    ember = CosmosEmber,
    emberSoft = CosmosEmberSoft,
    rose = CosmosRoseDark,
    violet = CosmosVioletDark,
    gold = CosmosGoldDark,
    cyan = CosmosCyanDark,
    moss = CosmosMossDark,
    danger = CosmosDangerDark,
    success = CosmosSuccessDark,
    muted = CosmosMutedDark,
    faint = CosmosFaintDark,
    boundary = CosmosBoundaryDark,
    onAction = CosmosOnActionDark,
)

internal val EchoLightExtras = EchoExtraColors(
    ember = CosmosDayAction,
    emberSoft = CosmosDayAccentText,
    rose = CosmosDayRose,
    violet = CosmosDayViolet,
    gold = CosmosDayGold,
    cyan = CosmosDayCyan,
    moss = CosmosDayMoss,
    danger = CosmosDayDanger,
    success = CosmosDaySuccess,
    muted = CosmosDayMuted,
    faint = CosmosDayFaint,
    boundary = CosmosDayBoundary,
    onAction = CosmosDayOnAction,
)

internal val LocalEchoExtraColors = staticCompositionLocalOf { EchoDarkExtras }

//region Shapes, freeze §4 [P-T platformAdapters.android.radius] [P-R C09]
internal val EchoControlRadius = 8.dp
internal val EchoSecondaryRadius = 10.dp
internal val EchoEmphasisRadius = 14.dp
internal val EchoRecordRadius = 20.dp
internal val EchoOverlayRadius = 28.dp

// Role mapping [ECHO-local] (M3 1.9 exposes five public shape slots; the
// circle role uses CircleShape / EchoPillRadius directly): fields and compact
// controls → extraSmall, buttons/inputs → small, start/record emphasis →
// medium, route records/cards → large, sheets/dialogs/panels → extraLarge.
internal val EchoShapes = Shapes(
    extraSmall = RoundedCornerShape(EchoControlRadius),
    small = RoundedCornerShape(EchoSecondaryRadius),
    medium = RoundedCornerShape(EchoEmphasisRadius),
    large = RoundedCornerShape(EchoRecordRadius),
    extraLarge = RoundedCornerShape(EchoOverlayRadius),
)

// Legacy component tokens, kept so no screen file changes in this slice:
// record-type cards (QrCard, LandmarkCard, Minimap) → record 20, sheets →
// overlay 28, icon controls/tags → circle pill.
internal val EchoCardRadius = EchoRecordRadius
internal val EchoSheetRadius = EchoOverlayRadius
internal val EchoPillRadius = 999.dp
//endregion

//region Spacing scale [P-T space] + touch targets [P-R C19] (1 unit = 1 dp)
internal val EchoSpace4 = 4.dp
internal val EchoSpace8 = 8.dp
internal val EchoSpace12 = 12.dp
internal val EchoSpace16 = 16.dp
internal val EchoSpace20 = 20.dp
internal val EchoSpace24 = 24.dp
internal val EchoSpace32 = 32.dp
internal val EchoSpace40 = 40.dp
internal val EchoSpace48 = 48.dp
internal val EchoSpace64 = 64.dp
internal val EchoSpace80 = 80.dp
internal val EchoSpace96 = 96.dp
internal val EchoSpace128 = 128.dp

internal val EchoTouchPrimary = 56.dp
internal val EchoTouchOther = 48.dp
internal val EchoRecordButton = 88.dp
internal val EchoQrMin = 260.dp
//endregion
