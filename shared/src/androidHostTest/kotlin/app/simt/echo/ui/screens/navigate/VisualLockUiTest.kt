package app.simt.echo.ui.screens.navigate

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.simt.echo.Fixtures
import app.simt.echo.capture.visual.EgoMotionFit
import app.simt.echo.capture.visual.VisualReason
import app.simt.echo.capture.visual.egoMotionTestFlow
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.Navigator
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.ProvideCosmosMotionTier
import app.simt.echo.ui.theme.EchoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W19-01 gate: honest-light visual-lock ring and usability nudges.
 *
 * - Visual-lock ring lights only when `VisualEgo.confidence01 >= 0.5` AND a
 *   visual observation was used within the last 2 s; its brightness tracks
 *   confidence and expires after 2 s.
 * - `FLAT`, `BLANK_WALL`, and `DARK` reasons emit quiet single-line nudges
 *   that auto-clear on recovery or after 4 s.
 * - Static tier and fontScale 2.0 render the same facts as accessible text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class VisualLockUiTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    @Test
    fun visualLockLightsOnlyWhenUsedWithinTwoSecondsAndNudgeAutoClears() {
        val nav = Navigator(
            route = Fixtures.L_ROUTE,
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            ghostSeed = 42L,
        )
        nav.onInput(NavInput.Motion(MotionUpdate(1_000_000_000L, 0.0, 0.0, 0, false, null, "test")))
        nav.onInput(NavInput.Aligned)
        assertEquals(0.0, nav.ui.value.visualLockConfidence01)
        assertNull(nav.ui.value.visualNudge)

        // 1. Feed a confident visual ego with FOE at t = 1.5 s: lock lights up.
        val fitter = EgoMotionFit()
        val confidentEgo = fitter.fit(egoMotionTestFlow(expansionPerSec = 0.18, foeX = 0.05), usability01 = 0.90)
        assertTrue(confidentEgo.confidence01 >= 0.5 && confidentEgo.hasFoe)

        nav.onInput(NavInput.Motion(MotionUpdate(1_500_000_000L, 0.7, 0.0, 1, true, null, "test")))
        nav.onVisualEgo(confidentEgo, usability01 = 0.90, reason = VisualReason.NONE, tNs = 1_500_000_000L)
        assertTrue(nav.ui.value.visualLockConfidence01 >= 0.5)

        // 2. At t = 3.4 s (1.9 s elapsed <= 2.0 s): still lit.
        nav.onInput(NavInput.Motion(MotionUpdate(3_400_000_000L, 1.4, 0.0, 2, true, null, "test")))
        assertTrue(nav.ui.value.visualLockConfidence01 >= 0.5)

        // 3. At t = 3.6 s (2.1 s elapsed > 2.0 s without new visual observation): lock extinguishes.
        nav.onInput(NavInput.Motion(MotionUpdate(3_600_000_000L, 2.1, 0.0, 3, true, null, "test")))
        assertEquals(0.0, nav.ui.value.visualLockConfidence01)

        // 4. Usability nudges for FLAT and BLANK_WALL appear and auto-clear on recovery or timeout.
        val rejectedEgo = EgoMotionFit().fit(egoMotionTestFlow(expansionPerSec = 0.0), usability01 = 0.0)
        nav.onVisualEgo(rejectedEgo, usability01 = 0.0, reason = VisualReason.FLAT, tNs = 4_000_000_000L)
        assertEquals("Hold the phone upright and ahead", nav.ui.value.visualNudge)

        nav.onVisualEgo(rejectedEgo, usability01 = 0.1, reason = VisualReason.BLANK_WALL, tNs = 4_500_000_000L)
        assertEquals("Blank wall — using steps", nav.ui.value.visualNudge)

        // Recovery clears the nudge immediately.
        nav.onInput(NavInput.Motion(MotionUpdate(5_000_000_000L, 2.8, 0.0, 4, true, null, "test")))
        nav.onVisualEgo(confidentEgo, usability01 = 0.90, reason = VisualReason.NONE, tNs = 5_000_000_000L)
        assertNull(nav.ui.value.visualNudge)
        assertTrue(nav.ui.value.visualLockConfidence01 >= 0.5)
    }

    @Test
    fun drawnVisualLockRingBrightnessTracksConfidenceAndStaysDarkBelowHalf() {
        val px = 200
        fun renderRingAlpha(conf: Float): Int {
            val bitmap = ImageBitmap(px, px)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
                drawVisualLockRing(color = Color.Cyan, auraRadiusPx = 80f, confidence01 = conf)
            }
            val pixels = IntArray(px * px).also(bitmap::readPixels)
            return pixels.maxOf { (it ushr 24) and 0xFF }
        }

        assertEquals(0, renderRingAlpha(0.0f), "confidence 0 draws nothing")
        assertEquals(0, renderRingAlpha(0.49f), "confidence < 0.5 draws nothing")
        val alphaLow = renderRingAlpha(0.55f)
        val alphaHigh = renderRingAlpha(0.95f)
        assertTrue(alphaLow > 0, "confidence 0.55 lights the ring")
        assertTrue(alphaHigh > alphaLow, "ring brightness must increase with confidence: $alphaLow vs $alphaHigh")
    }

    @Test
    fun staticTierAndFontScaleTwoRenderVisualLockAndNudgeFactsAsText() {
        val big = Density(density = compose.density.density, fontScale = 2.0f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides big) {
                EchoTheme(darkTheme = true) {
                    ProvideCosmosMotionTier(tier = CosmosMotionTier.STATIC) {
                        val inst = navigateInstrumentPresentation(
                            phase = app.simt.echo.nav.NavPhase.WALK,
                            arrowRad = 0.0,
                            sigmaM = 1.2,
                            remainingM = 15.0,
                            nextTurn = null,
                            landmarkPresent = false,
                            fingerprintPrompt = null,
                            relock = null,
                            visualLockConfidence01 = 0.82,
                            visualNudge = "Blank wall — using steps",
                        )
                        InstrumentArrow(instrument = inst, arrowRad = 0.0)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NavigateVisualLockTag).assertIsDisplayed()
        compose.onNodeWithText("Visual lock 82%").assertIsDisplayed()
        compose.onNodeWithTag(NavigateVisualNudgeTag).assertIsDisplayed()
        compose.onNodeWithText("Blank wall — using steps").assertIsDisplayed()
    }
}
