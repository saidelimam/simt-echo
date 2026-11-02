package app.simt.echo.ui.screens.navigate

import app.simt.echo.nav.NavPhase
import app.simt.echo.ui.components.CosmosBannerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W8-10 gate: the phase → UI mapping covers all 8 [NavPhase]s. Phase
 * honesty (the S-reviewed criterion) is asserted per branch: only
 * UNCERTAIN and OFF_ROUTE carry a banner, each mark + copy and never
 * hue-only; the "At marker" trial tap never leaks into product UI.
 */
class NavigatePhaseUiTest {

    @Test
    fun allEightPhasesMap() {
        for (phase in NavPhase.entries) {
            val presentation = navigatePhasePresentation(
                phase = phase,
                hasLandmark = true,
                isLastLeg = true,
                showCards = true,
                researchMode = true,
            )
            when (phase) {
                NavPhase.ALIGN, NavPhase.ARRIVED ->
                    assertTrue(presentation.actions.isEmpty(), "$phase has no actions")
                NavPhase.CONFIRM ->
                    assertEquals(
                        listOf(NavigateAction.SEE_LANDMARK, NavigateAction.NOT_YET),
                        presentation.actions,
                    )
                NavPhase.UNCERTAIN ->
                    assertEquals(
                        listOf(NavigateAction.SEE_LANDMARK, NavigateAction.LOST),
                        presentation.actions,
                    )
                NavPhase.OFF_ROUTE ->
                    assertEquals(
                        listOf(NavigateAction.GO_BACK_TO_LAST, NavigateAction.RESTART_FROM_START),
                        presentation.actions,
                    )
                NavPhase.WALK ->
                    assertEquals(
                        listOf(
                            NavigateAction.SEE_LANDMARK,
                            NavigateAction.IM_HERE,
                            NavigateAction.AT_MARKER,
                            NavigateAction.LOST,
                        ),
                        presentation.actions,
                    )
                NavPhase.APPROACH, NavPhase.TURNING ->
                    assertEquals(
                        listOf(NavigateAction.SEE_LANDMARK, NavigateAction.AT_MARKER, NavigateAction.LOST),
                        presentation.actions,
                    )
            }
        }
    }

    @Test
    fun onlyUncertainAndOffRouteCarryBanners() {
        for (phase in NavPhase.entries) {
            val presentation = navigatePhasePresentation(
                phase = phase,
                hasLandmark = false,
                isLastLeg = false,
                showCards = true,
            )
            when (phase) {
                NavPhase.UNCERTAIN ->
                    assertEquals(CosmosBannerKind.Warning, presentation.bannerKind)
                NavPhase.OFF_ROUTE ->
                    assertEquals(CosmosBannerKind.Error, presentation.bannerKind)
                else -> assertNull(presentation.bannerKind, "$phase has no banner")
            }
        }
    }

    @Test
    fun atMarkerIsResearchGated() {
        for (phase in listOf(NavPhase.WALK, NavPhase.APPROACH, NavPhase.TURNING)) {
            val product = navigatePhasePresentation(
                phase = phase,
                hasLandmark = false,
                isLastLeg = false,
                showCards = true,
                researchMode = false,
            )
            assertFalse(product.actions.contains(NavigateAction.AT_MARKER), "$phase product UI")
            val research = navigatePhasePresentation(
                phase = phase,
                hasLandmark = false,
                isLastLeg = false,
                showCards = true,
                researchMode = true,
            )
            assertTrue(research.actions.contains(NavigateAction.AT_MARKER), "$phase research UI")
        }
        // The gate defaults to product UI at the call site.
        val default = navigatePhasePresentation(
            phase = NavPhase.WALK,
            hasLandmark = false,
            isLastLeg = false,
            showCards = true,
        )
        assertFalse(default.actions.contains(NavigateAction.AT_MARKER))
    }

    @Test
    fun baselineHidesLandmarkConfirmations() {
        val presentation = navigatePhasePresentation(
            phase = NavPhase.CONFIRM,
            hasLandmark = true,
            isLastLeg = false,
            showCards = false,
        )
        assertEquals(listOf(NavigateAction.NOT_YET), presentation.actions)
        val walk = navigatePhasePresentation(
            phase = NavPhase.WALK,
            hasLandmark = true,
            isLastLeg = false,
            showCards = false,
        )
        assertEquals(listOf(NavigateAction.LOST), walk.actions)
    }

    @Test
    fun imHereOnlyOnLastLegWalk() {
        val last = navigatePhasePresentation(
            phase = NavPhase.WALK,
            hasLandmark = false,
            isLastLeg = true,
            showCards = true,
        )
        assertTrue(last.actions.contains(NavigateAction.IM_HERE))
        for (phase in listOf(NavPhase.APPROACH, NavPhase.TURNING, NavPhase.CONFIRM)) {
            val presentation = navigatePhasePresentation(
                phase = phase,
                hasLandmark = false,
                isLastLeg = true,
                showCards = true,
            )
            assertFalse(presentation.actions.contains(NavigateAction.IM_HERE), "$phase")
        }
    }
}
