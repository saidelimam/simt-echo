package app.simt.echo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W4-04 gates, no device needed: both EchoTheme schemes render distinctly,
 * body text meets 4.5:1 and primary meets 3:1 in each theme, and the touch
 * / QR size tokens are asserted rather than eyeballed.
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

    @Test
    fun lightAndDarkThemesRenderDistinctly() {
        assertTrue(EchoBgLight != EchoBgDark)
        assertTrue(EchoSurfaceLight != EchoSurfaceDark)
        assertTrue(EchoLimeLight != EchoLimeDark)
        assertTrue(EchoMutedLight != EchoMutedDark)
        assertTrue(EchoOnBgLight != EchoOnBgDark)
    }

    @Test
    fun bodyTextContrastBothThemes() {
        assertTrue(contrast(EchoOnBgDark, EchoBgDark) >= 4.5)
        assertTrue(contrast(EchoOnBgLight, EchoBgLight) >= 4.5)
    }

    @Test
    fun primaryContrastBothThemes() {
        assertTrue(contrast(EchoLimeDark, EchoBgDark) >= 3.0)
        assertTrue(contrast(EchoLimeLight, EchoBgLight) >= 3.0)
    }

    @Test
    fun touchTargetsMeetMinimum() {
        assertTrue(EchoTouchPrimary >= EchoTouchOther)
        assertTrue(EchoTouchOther == 48.dp)
    }

    @Test
    fun qrCardMeetsMinimum() {
        assertTrue(EchoQrMin >= 260.dp)
    }
}
