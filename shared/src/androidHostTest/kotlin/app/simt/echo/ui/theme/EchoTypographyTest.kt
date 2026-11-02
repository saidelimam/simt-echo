package app.simt.echo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.simt.echo.host.RobolectricActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W8-02 full type-scale gate (freeze §5). The bundled families are
 * resource-backed, so the shipped [Typography] is built in composition and
 * captured here: sizes/leadings (ECHO-local readings of [P-R C08]), tabular
 * numerals on instrument roles, zero tracking everywhere, Space Grotesk
 * display roles over system body, El Messiri for Arabic roles [P-R C07].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class EchoTypographyTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private fun captureThemeTypography(): Typography {
        var captured: Typography? = null
        compose.setContent {
            EchoTheme(darkTheme = true) {
                captured = MaterialTheme.typography
            }
        }
        compose.waitForIdle()
        return captured ?: error("typography not captured")
    }

    @Test
    fun fullTypeScaleRoles() {
        val typography = captureThemeTypography()
        assertEquals(56.sp, typography.displayLarge.fontSize)
        assertEquals(62.sp, typography.displayLarge.lineHeight!!)
        assertEquals(44.sp, typography.displayMedium.fontSize)
        assertEquals(48.sp, typography.displayMedium.lineHeight!!)
        assertEquals(30.sp, typography.headlineLarge.fontSize)
        assertEquals(36.sp, typography.headlineLarge.lineHeight!!)
        assertEquals(22.sp, typography.titleLarge.fontSize)
        assertEquals(29.sp, typography.titleLarge.lineHeight!!)
        assertEquals(16.sp, typography.bodyLarge.fontSize)
        assertEquals(24.sp, typography.bodyLarge.lineHeight!!)
        assertEquals(14.sp, typography.bodyMedium.fontSize)
        assertEquals(20.sp, typography.bodyMedium.lineHeight!!)
        assertEquals(15.sp, typography.labelLarge.fontSize)
        assertEquals(20.sp, typography.labelLarge.lineHeight!!)
        // Instrument readouts carry tabular numerals; every role has zero
        // tracking (freeze §5: no negative tracking).
        assertEquals(EchoTnum, typography.displayLarge.fontFeatureSettings)
        assertEquals(EchoTnum, typography.displayMedium.fontFeatureSettings)
        val all = listOf(
            typography.displayLarge, typography.displayMedium,
            typography.displaySmall, typography.headlineLarge,
            typography.headlineMedium, typography.headlineSmall,
            typography.titleLarge, typography.titleMedium,
            typography.titleSmall, typography.bodyLarge,
            typography.bodyMedium, typography.bodySmall,
            typography.labelLarge, typography.labelMedium,
            typography.labelSmall,
        )
        assertEquals(15, all.size)
        assertTrue(all.all { it.letterSpacing == 0.sp })
    }

    @Test
    fun bundledFamiliesWiredWithSystemBody() {
        var display: FontFamily? = null
        var arabic: FontFamily? = null
        var captured: Typography? = null
        var arabicTypography: Typography? = null
        compose.setContent {
            EchoTheme(darkTheme = true) {
                display = rememberEchoDisplayFontFamily()
                arabic = rememberEchoArabicFontFamily()
                captured = MaterialTheme.typography
                arabicTypography = rememberEchoArabicTypography()
            }
        }
        compose.waitForIdle()
        val displayFamily = display ?: error("display family not captured")
        val arabicFamily = arabic ?: error("arabic family not captured")
        val typography = captured ?: error("typography not captured")
        val arabicType = arabicTypography ?: error("arabic typography not captured")
        // Bundled families resolve to real families, never silent Default.
        assertTrue(displayFamily != FontFamily.Default)
        assertTrue(arabicFamily != FontFamily.Default)
        assertTrue(displayFamily != arabicFamily)
        // Titles + instrumentation on Space Grotesk, body on system [P-R C07].
        assertEquals(displayFamily, typography.displayLarge.fontFamily)
        assertEquals(displayFamily, typography.titleLarge.fontFamily)
        assertEquals(FontFamily.Default, typography.bodyLarge.fontFamily)
        assertEquals(FontFamily.Default, typography.labelLarge.fontFamily)
        // El Messiri for every Arabic role (Space Grotesk has no Arabic glyphs).
        assertEquals(arabicFamily, arabicType.displayLarge.fontFamily)
        assertEquals(arabicFamily, arabicType.bodyLarge.fontFamily)
    }
}
