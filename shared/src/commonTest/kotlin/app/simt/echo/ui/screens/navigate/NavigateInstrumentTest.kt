package app.simt.echo.ui.screens.navigate

import app.simt.echo.model.TurnDir
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.ghost.FingerprintPrompt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W13-08 gate: the phase → instrument mapping covers all 8 [NavPhase]s,
 * and UNCERTAIN/OFF_ROUTE honesty is structural —
 * σ stays visible in every phase, dashed + labelled only in UNCERTAIN.
 * Pure mapping: tiers settle to the same info (Static parity) because
 * the mapping takes no tier — animation only changes arrival, never
 * content.
 */
class NavigateInstrumentTest {

    private fun presentation(
        phase: NavPhase,
        arrowRad: Double = 0.1,
        sigmaM: Double = 2.0,
        remainingM: Double = 20.0,
        nextTurn: TurnDir? = null,
        landmarkPresent: Boolean = false,
        fingerprintPrompt: FingerprintPrompt? = null,
        relock: NavigateRelockState? = null,
    ) = navigateInstrumentPresentation(
        phase = phase,
        arrowRad = arrowRad,
        sigmaM = sigmaM,
        remainingM = remainingM,
        nextTurn = nextTurn,
        landmarkPresent = landmarkPresent,
        fingerprintPrompt = fingerprintPrompt,
        relock = relock,
    )

    @Test
    fun allEightPhasesMap() {
        for (phase in NavPhase.entries) {
            val p = presentation(phase = phase)
            when (phase) {
                NavPhase.ARRIVED -> assertEquals(ArrowShape.CHECK, p.arrowShape, "$phase")
                else -> assertEquals(ArrowShape.STRAIGHT, p.arrowShape, "$phase")
            }
        }
    }

    @Test
    fun arrowMorphsStraightToBendToU() {
        assertEquals(ArrowShape.STRAIGHT, arrowShapeFor(NavPhase.WALK, 0.0))
        assertEquals(ArrowShape.STRAIGHT, arrowShapeFor(NavPhase.WALK, ARROW_STRAIGHT_MAX_RAD - 0.01))
        assertEquals(ArrowShape.BEND_RIGHT, arrowShapeFor(NavPhase.WALK, 0.8))
        assertEquals(ArrowShape.BEND_LEFT, arrowShapeFor(NavPhase.WALK, -0.8))
        assertEquals(ArrowShape.BEND_LEFT, arrowShapeFor(NavPhase.APPROACH, -1.5))
        assertEquals(ArrowShape.U_TURN, arrowShapeFor(NavPhase.TURNING, 2.5))
        assertEquals(ArrowShape.U_TURN, arrowShapeFor(NavPhase.TURNING, -2.5))
        assertEquals(ArrowShape.CHECK, arrowShapeFor(NavPhase.ARRIVED, 0.0))
        assertEquals(ArrowShape.CHECK, arrowShapeFor(NavPhase.ARRIVED, 2.5))
        // Bend is angle-proportional: grows across the bend range.
        val small = bendFractionFor(0.6)
        val large = bendFractionFor(1.8)
        assertTrue(small > 0f && small < large, "bend $small < $large")
        assertTrue(large < 1f)
        assertEquals(0f, bendFractionFor(0.0))
        assertEquals(1f, bendFractionFor(3.0))
    }

    @Test
    fun auraTracksSigmaMonotonically() {
        val radii = listOf(0.5, 1.0, 2.0, 4.0, 8.0).map(::auraRadiusDpFor)
        for (i in 1 until radii.size) assertTrue(radii[i] >= radii[i - 1], "radius monotonic")
        val soft = listOf(0.5, 1.0, 2.0, 4.0, 8.0).map(::auraSoftnessFor)
        for (i in 1 until soft.size) assertTrue(soft[i] >= soft[i - 1], "softness monotonic")
        assertTrue(auraRadiusDpFor(100.0) <= AURA_MAX_DP, "radius clamped")
        // The aura must fit the canvas, or every σ past the clamp draws the same disc.
        assertTrue(AURA_MAX_DP * 2f <= AURA_CANVAS_DP, "max aura fits the canvas")
        assertTrue(auraRadiusDpFor(0.0) < auraRadiusDpFor(3.0), "radius moves across the σ range")
        assertTrue(auraRadiusDpFor(3.0) < auraRadiusDpFor(5.0), "radius still moves at high σ")
        assertTrue(auraSoftnessFor(100.0) <= 1f, "softness clamped")
    }

    @Test
    fun onlyUncertainDashesButSigmaShowsEverywhere() {
        for (phase in NavPhase.entries) {
            val p = presentation(phase = phase)
            assertEquals(phase == NavPhase.UNCERTAIN, p.auraDashed, "$phase dash")
            // Honesty: the σ label is present in every phase — UNCERTAIN
            // and OFF_ROUTE never hide uncertainty.
            assertTrue(p.sigmaLabel.isNotBlank(), "$phase sigma label")
            assertTrue(p.sigmaLabel.startsWith("σ ±"), "$phase sigma mark: ${p.sigmaLabel}")
        }
        val uncertain = presentation(phase = NavPhase.UNCERTAIN, sigmaM = 7.5)
        assertTrue(uncertain.sigmaLabel.contains("7.5"), uncertain.sigmaLabel)
        val offRoute = presentation(phase = NavPhase.OFF_ROUTE, sigmaM = 7.5)
        assertTrue(offRoute.sigmaLabel.contains("7.5"), offRoute.sigmaLabel)
    }

    @Test
    fun glowIntensityFollowsOneMinusRemainingOverEight() {
        assertEquals(1f, glowIntensityFor(0.0))
        assertEquals(0.5f, glowIntensityFor(4.0))
        assertEquals(0f, glowIntensityFor(8.0))
        assertEquals(0f, glowIntensityFor(20.0))
        assertEquals(1f, glowIntensityFor(-3.0))
    }

    @Test
    fun glowSideAndRange() {
        // Turning side from the arrow while approaching/turning.
        assertEquals(
            GlowSide.RIGHT,
            presentation(phase = NavPhase.TURNING, arrowRad = 0.9, remainingM = 3.0).glowSide,
        )
        assertEquals(
            GlowSide.LEFT,
            presentation(phase = NavPhase.APPROACH, arrowRad = -0.9, remainingM = 3.0).glowSide,
        )
        // Next-turn fallback when walking straight toward a turn.
        assertEquals(
            GlowSide.LEFT,
            presentation(phase = NavPhase.WALK, arrowRad = 0.0, remainingM = 5.0, nextTurn = TurnDir.LEFT).glowSide,
        )
        // Out of range: no glow.
        assertNull(presentation(phase = NavPhase.TURNING, arrowRad = 0.9, remainingM = 8.01).glowSide)
        assertEquals(
            0f,
            presentation(phase = NavPhase.TURNING, arrowRad = 0.9, remainingM = 20.0).glowIntensity01,
        )
        // No turn context: no glow even in range.
        assertNull(presentation(phase = NavPhase.WALK, arrowRad = 0.0, remainingM = 3.0).glowSide)
        // Arrived: no glow.
        assertNull(presentation(phase = NavPhase.ARRIVED, arrowRad = 0.9, remainingM = 0.0).glowSide)
    }

    @Test
    fun fingerprintChipAsksOnlyWhenPrompted() {
        assertNull(presentation(phase = NavPhase.WALK).fingerprintChipCopy)
        val p = presentation(
            phase = NavPhase.APPROACH,
            fingerprintPrompt = FingerprintPrompt(label = "Red pillar", nodeS = 42.0),
        )
        assertEquals("That's the Red pillar?", p.fingerprintChipCopy)
    }

    @Test
    fun relockRingShowsOnlyWhenStatePresent() {
        assertFalse(presentation(phase = NavPhase.OFF_ROUTE).showRelockRing)
        // W12-06 absent → ViewModel passes null → nothing renders.
        assertFalse(presentation(phase = NavPhase.UNCERTAIN, relock = null).showRelockRing)
        assertTrue(presentation(phase = NavPhase.UNCERTAIN, relock = NavigateRelockState(0.4)).showRelockRing)
    }

    @Test
    fun lookForThisOnlyOnApproachTurningConfirm() {
        for (phase in listOf(NavPhase.APPROACH, NavPhase.TURNING, NavPhase.CONFIRM)) {
            assertTrue(presentation(phase = phase, landmarkPresent = true).showLookForThis, "$phase")
        }
        assertFalse(presentation(phase = NavPhase.WALK, landmarkPresent = true).showLookForThis)
        assertFalse(presentation(phase = NavPhase.APPROACH, landmarkPresent = false).showLookForThis)
        assertFalse(presentation(phase = NavPhase.UNCERTAIN, landmarkPresent = true).showLookForThis)
        assertFalse(presentation(phase = NavPhase.OFF_ROUTE, landmarkPresent = true).showLookForThis)
    }

    @Test
    fun calmModeOnlyWalkingStraight() {
        assertTrue(presentation(phase = NavPhase.WALK, arrowRad = 0.0).calmMode)
        assertTrue(presentation(phase = NavPhase.WALK, arrowRad = 0.3).calmMode)
        assertFalse(presentation(phase = NavPhase.WALK, arrowRad = 0.9).calmMode)
        assertFalse(presentation(phase = NavPhase.APPROACH, arrowRad = 0.0).calmMode)
        assertFalse(presentation(phase = NavPhase.TURNING, arrowRad = 0.0).calmMode)
        // Calm dims chrome only: copy is untouched by the mode.
        val calm = presentation(phase = NavPhase.WALK, arrowRad = 0.0, sigmaM = 3.0)
        val alert = presentation(phase = NavPhase.APPROACH, arrowRad = 0.0, sigmaM = 3.0)
        assertEquals(calm.sigmaLabel, alert.sigmaLabel)
    }

    @Test
    fun mappingIsDeterministicAcrossTiers() {
        // Static parity: the mapping takes no motion tier, so FULL,
        // REDUCED and STATIC settle to the same info — animation only
        // changes how the arrow/glow arrive, never what they say.
        val first = presentation(phase = NavPhase.TURNING, arrowRad = 1.1, sigmaM = 3.5, remainingM = 2.0)
        val second = presentation(phase = NavPhase.TURNING, arrowRad = 1.1, sigmaM = 3.5, remainingM = 2.0)
        assertEquals(first, second)
        assertTrue(abs(first.glowIntensity01 - (1f - 2f / 8f)) < 1e-6f)
    }
}
