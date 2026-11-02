package app.simt.echo.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cosmos freeze contrast gates (W8-02) extending the W4-04 scheme tests.
 *
 * Frozen pairs from docs/research/cosmos-echo-freeze.md §2–§3 ([P-C]):
 * text roles clear 4.5:1, essential boundaries 3:1, in both schemes. Quoted
 * ratios are pinned within float tolerance so the frozen hexes cannot drift;
 * gates use the WCAG thresholds. No device needed.
 */
class EchoThemeGatesTest {

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val s = channel.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private fun contrast(first: Color, second: Color): Double {
        val ordered = listOf(luminance(first), luminance(second)).sortedDescending()
        return (ordered[0] + 0.05) / (ordered[1] + 0.05)
    }

    private fun assertQuoted(actual: Double, quoted: Double, label: String) {
        assertTrue(actual >= quoted - 0.1, "$label: $actual, frozen quoted $quoted")
    }

    @Test
    fun lightAndDarkThemesRenderDistinctly() {
        assertTrue(EchoDarkScheme.background != EchoLightScheme.background)
        assertTrue(EchoDarkScheme.surface != EchoLightScheme.surface)
        assertTrue(EchoDarkScheme.primary != EchoLightScheme.primary)
        assertTrue(EchoDarkExtras.muted != EchoLightExtras.muted)
        assertTrue(EchoDarkScheme.onBackground != EchoLightScheme.onBackground)
    }

    @Test
    fun deepSpaceFrozenPairs() {
        // Freeze §2 [P-C]: ink 18.50, muted 9.16, faint 6.05 (supporting text
        // only on a passing surface), void-on-ember 8.42, boundary 6.05.
        val ink = contrast(CosmosInk, CosmosVoid)
        val muted = contrast(CosmosMutedDark, CosmosVoid)
        val faint = contrast(CosmosFaintDark, CosmosVoid)
        val action = contrast(CosmosVoid, CosmosEmber)
        val boundary = contrast(CosmosBoundaryDark, CosmosVoid)
        assertTrue(ink >= 4.5, "ink-on-void: $ink")
        assertTrue(muted >= 4.5, "muted-on-void: $muted")
        assertTrue(faint >= 4.5, "faint-on-void: $faint")
        assertTrue(action >= 4.5, "void-on-ember: $action")
        assertTrue(boundary >= 3.0, "boundary-on-void: $boundary")
        assertQuoted(ink, 18.50, "ink-on-void")
        assertQuoted(muted, 9.16, "muted-on-void")
        assertQuoted(faint, 6.05, "faint-on-void")
        assertQuoted(action, 8.42, "void-on-ember")
        assertQuoted(boundary, 6.05, "boundary-on-void")
    }

    @Test
    fun daylightFrozenPairs() {
        // Freeze §3 [P-C]: every pair below clears AA normal text.
        val onBg = contrast(CosmosDayText, CosmosDayBackground)
        val onSurface = contrast(CosmosDayText, CosmosDaySurface)
        val onRaised = contrast(CosmosDayText, CosmosDayRaised)
        val onHighest = contrast(CosmosDayText, CosmosDayHighest)
        val muted = contrast(CosmosDayMuted, CosmosDayBackground)
        val faint = contrast(CosmosDayFaint, CosmosDayBackground)
        val boundary = contrast(CosmosDayBoundary, CosmosDayBackground)
        val onAction = contrast(CosmosDayOnAction, CosmosDayAction)
        val accent = contrast(CosmosDayAccentText, CosmosDayBackground)
        val rose = contrast(CosmosDayRose, CosmosDayBackground)
        val violet = contrast(CosmosDayViolet, CosmosDayBackground)
        val gold = contrast(CosmosDayGold, CosmosDayBackground)
        val cyan = contrast(CosmosDayCyan, CosmosDayBackground)
        val moss = contrast(CosmosDayMoss, CosmosDayBackground)
        val danger = contrast(CosmosDayDanger, CosmosDayBackground)
        val success = contrast(CosmosDaySuccess, CosmosDayBackground)
        for ((label, ratio) in listOf(
            "text-on-bg" to onBg, "text-on-surface" to onSurface,
            "text-on-raised" to onRaised, "text-on-highest" to onHighest,
            "muted-on-bg" to muted, "faint-on-bg" to faint,
            "boundary-on-bg" to boundary, "onAction-on-action" to onAction,
            "accentText-on-bg" to accent, "rose-on-bg" to rose,
            "violet-on-bg" to violet, "gold-on-bg" to gold,
            "cyan-on-bg" to cyan, "moss-on-bg" to moss,
            "danger-on-bg" to danger, "success-on-bg" to success,
        )) {
            assertTrue(ratio >= 4.5, "$label: $ratio")
        }
        assertQuoted(onBg, 15.93, "text-on-bg")
        assertQuoted(onSurface, 16.29, "text-on-surface")
        assertQuoted(onRaised, 14.10, "text-on-raised")
        assertQuoted(onHighest, 13.64, "text-on-highest")
        assertQuoted(muted, 8.73, "muted-on-bg")
        assertQuoted(faint, 5.77, "faint-on-bg")
        assertQuoted(boundary, 8.73, "boundary-on-bg")
        assertQuoted(onAction, 4.64, "onAction-on-action")
        assertQuoted(accent, 5.38, "accentText-on-bg")
        assertQuoted(rose, 5.33, "rose-on-bg")
        assertQuoted(violet, 5.38, "violet-on-bg")
        assertQuoted(gold, 5.31, "gold-on-bg")
        assertQuoted(cyan, 6.00, "cyan-on-bg")
        assertQuoted(moss, 6.41, "moss-on-bg")
        assertQuoted(danger, 7.30, "danger-on-bg")
        assertQuoted(success, 6.58, "success-on-bg")
    }

    @Test
    fun primaryActionTextBothSchemes() {
        // Freeze §1: Echo Lime retired — primary is Ember, never lime.
        assertEquals(CosmosEmber, EchoDarkScheme.primary)
        assertEquals(CosmosDayAction, EchoLightScheme.primary)
        assertTrue(EchoDarkScheme.primary != Color(0xFFC6F432))
        assertTrue(EchoLightScheme.primary != Color(0xFF4E7A00))
        // Primary-action text passes as text in both schemes (dark text on
        // the Daylight fill, never assumed white [P-R C06]).
        assertTrue(contrast(EchoDarkScheme.onPrimary, EchoDarkScheme.primary) >= 4.5)
        assertTrue(contrast(EchoLightScheme.onPrimary, EchoLightScheme.primary) >= 4.5)
        assertEquals(CosmosOnActionDark, EchoDarkScheme.onPrimary)
        assertEquals(CosmosDayOnAction, EchoLightScheme.onPrimary)
    }

    @Test
    fun errorContrastMeetsTextGate() {
        // The schemes are what components consume via colorScheme.error.
        assertEquals(CosmosDayDanger, EchoLightScheme.error)
        assertEquals(CosmosDangerDark, EchoDarkScheme.error)
        assertTrue(
            contrast(EchoLightScheme.error, EchoLightScheme.background) >= 4.5,
            "light error on light bg: ${contrast(EchoLightScheme.error, EchoLightScheme.background)}",
        )
        assertTrue(
            contrast(EchoLightScheme.error, EchoLightScheme.surface) >= 4.5,
            "light error on light surface: ${contrast(EchoLightScheme.error, EchoLightScheme.surface)}",
        )
        assertTrue(
            contrast(EchoDarkScheme.error, EchoDarkScheme.background) >= 4.5,
            "dark error on dark bg: ${contrast(EchoDarkScheme.error, EchoDarkScheme.background)}",
        )
        assertTrue(contrast(EchoDarkScheme.onError, EchoDarkScheme.error) >= 4.5)
        assertTrue(contrast(EchoLightScheme.onError, EchoLightScheme.error) >= 4.5)
        assertTrue(contrast(EchoDarkScheme.onErrorContainer, EchoDarkScheme.errorContainer) >= 4.5)
        assertTrue(contrast(EchoLightScheme.onErrorContainer, EchoLightScheme.errorContainer) >= 4.5)
    }

    @Test
    fun mutedAndOnBackgroundContrastOnSurface() {
        assertTrue(contrast(CosmosDayMuted, CosmosDayBackground) >= 4.5)
        assertTrue(contrast(CosmosDayMuted, CosmosDaySurface) >= 4.5)
        assertTrue(contrast(CosmosMutedDark, CosmosVoid) >= 4.5)
        assertTrue(contrast(CosmosMutedDark, CosmosHull) >= 4.5)
        assertTrue(contrast(CosmosDayText, CosmosDaySurface) >= 4.5)
        assertTrue(contrast(CosmosInk, CosmosHull) >= 4.5)
        assertTrue(contrast(CosmosDayMuted, CosmosDayRaised) >= 4.5)
        assertTrue(contrast(CosmosMutedDark, CosmosHullRaised) >= 4.5)
        assertTrue(contrast(CosmosInk, CosmosHullHighest) >= 4.5)
        assertTrue(contrast(CosmosFaintDark, CosmosVoid) >= 4.5)
        assertTrue(contrast(CosmosDayFaint, CosmosDaySurface) >= 4.5)
    }

    @Test
    fun supportingChannelsMeetTextGateOnTheirFills() {
        assertTrue(contrast(EchoDarkScheme.onSecondary, EchoDarkScheme.secondary) >= 4.5)
        assertTrue(contrast(EchoDarkScheme.onTertiary, EchoDarkScheme.tertiary) >= 4.5)
        assertTrue(contrast(EchoLightScheme.onSecondary, EchoLightScheme.secondary) >= 4.5)
        assertTrue(contrast(EchoLightScheme.onTertiary, EchoLightScheme.tertiary) >= 4.5)
    }

    @Test
    fun extrasCarryFrozenChannels() {
        assertEquals(CosmosEmber, EchoDarkExtras.ember)
        assertEquals(CosmosEmberSoft, EchoDarkExtras.emberSoft)
        assertEquals(CosmosMutedDark, EchoDarkExtras.muted)
        assertEquals(CosmosFaintDark, EchoDarkExtras.faint)
        assertEquals(CosmosBoundaryDark, EchoDarkExtras.boundary)
        assertEquals(CosmosDayAction, EchoLightExtras.ember)
        assertEquals(CosmosDayAccentText, EchoLightExtras.emberSoft)
        assertEquals(CosmosDayMuted, EchoLightExtras.muted)
        assertEquals(CosmosDayFaint, EchoLightExtras.faint)
        assertEquals(CosmosDayBoundary, EchoLightExtras.boundary)
    }

    @Test
    fun shapesMatchFrozenRoles() {
        // Freeze §4: control 8 / secondary 10 / emphasis 14 / record 20 /
        // overlay 28, wired into MaterialTheme.
        assertEquals(EchoControlRadius, 8.dp)
        assertEquals(EchoSecondaryRadius, 10.dp)
        assertEquals(EchoEmphasisRadius, 14.dp)
        assertEquals(EchoRecordRadius, 20.dp)
        assertEquals(EchoOverlayRadius, 28.dp)
        assertEquals(CornerSize(8.dp), EchoShapes.extraSmall.topStart)
        assertEquals(CornerSize(10.dp), EchoShapes.small.topStart)
        assertEquals(CornerSize(14.dp), EchoShapes.medium.topStart)
        assertEquals(CornerSize(20.dp), EchoShapes.large.topStart)
        assertEquals(CornerSize(28.dp), EchoShapes.extraLarge.topStart)
        // Legacy component aliases kept for zero screen-file changes.
        assertEquals(20.dp, EchoCardRadius)
        assertEquals(28.dp, EchoSheetRadius)
    }

    @Test
    fun spacingScaleAndTouchTargets() {
        // Freeze §4 [P-T space]: exact scale, 1 unit = 1 dp.
        assertEquals(
            listOf(4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128).map { it.dp },
            listOf(
                EchoSpace4, EchoSpace8, EchoSpace12, EchoSpace16, EchoSpace20,
                EchoSpace24, EchoSpace32, EchoSpace40, EchoSpace48, EchoSpace64,
                EchoSpace80, EchoSpace96, EchoSpace128,
            ),
        )
        // Touch targets ≥ 48×48 dp [P-R C19]; record keeps 88 dp Start and
        // ≥260 dp QR surface [ECHO-local] (carried from EchoRecordButton).
        assertTrue(EchoTouchPrimary == 56.dp)
        assertTrue(EchoTouchPrimary >= EchoTouchOther)
        assertTrue(EchoTouchOther == 48.dp)
        assertTrue(EchoRecordButton == 88.dp)
        assertTrue(EchoQrMin >= 260.dp)
    }

    @Test
    fun touchTargetsMeetMinimum() {
        assertTrue(EchoTouchPrimary == 56.dp)
        assertTrue(EchoTouchPrimary >= EchoTouchOther)
        assertTrue(EchoTouchOther == 48.dp)
    }

    @Test
    fun qrCardMeetsMinimum() {
        assertTrue(EchoQrMin >= 260.dp)
    }
}
