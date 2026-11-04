package app.simt.echo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.font.FontFamily
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.ui.icons.EchoIcons
import app.simt.echo.ui.screens.settings.promoArrow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review 0013 F9 (R13-09): Back mirrors in RTL, the promo arrow follows the
 * layout direction, and an Arabic locale switches the whole type scale to
 * El Messiri instead of drawing tofu through Space Grotesk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class RtlAndTypographyTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun backIconMirrorsInRtl() {
        assertTrue(EchoIcons.Back.autoMirror)
    }

    @Test
    fun promoArrowPointsForwardInBothDirections() {
        assertEquals("→", promoArrow(isRtl = false))
        assertEquals("←", promoArrow(isRtl = true))
    }

    @Test
    fun arabicLocaleSelectsElMessiriForTheWholeScale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            var arabicFamily: FontFamily? = null
            var captured: Typography? = null
            compose.setContent {
                EchoTheme(darkTheme = true) {
                    arabicFamily = rememberEchoArabicFontFamily()
                    captured = MaterialTheme.typography
                }
            }
            compose.waitForIdle()
            val family = arabicFamily ?: error("Arabic family not captured")
            val typography = captured ?: error("typography not captured")
            assertEquals(family, typography.displayLarge.fontFamily)
            assertEquals(family, typography.titleLarge.fontFamily)
            assertEquals(family, typography.bodyLarge.fontFamily)
            assertEquals(family, typography.labelSmall.fontFamily)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
