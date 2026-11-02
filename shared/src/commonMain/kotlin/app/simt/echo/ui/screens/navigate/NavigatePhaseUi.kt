package app.simt.echo.ui.screens.navigate

import app.simt.echo.nav.NavPhase
import app.simt.echo.ui.components.CosmosBannerKind

/**
 * Contextual Navigate actions (plan §8.3 / W8-10). The screen renders this
 * list in order: at most one primary (the first element) then secondaries.
 * Order matters: [NavigateActions] treats the head as primary.
 */
enum class NavigateAction {
    SEE_LANDMARK,
    NOT_YET,
    LOST,
    GO_BACK_TO_LAST,
    RESTART_FROM_START,
    IM_HERE,
    /** Trial drift tap (§10 W4-01): research-gated, never product UI. */
    AT_MARKER,
}

/**
 * Pure phase → UI mapping for the Navigate screen (W8-10 acceptance).
 *
 * Phase honesty is structural here and asserted per branch in
 * [NavigatePhaseUiTest]: UNCERTAIN and OFF_ROUTE are the only phases with
 * a banner, and each banner is mark + copy ([CosmosBannerKind]), never
 * hue-only. The "At marker" trial tap appears only when [researchMode] is
 * true (wired by Settings/research in W8-11); the tap itself still saves
 * via `NavigateViewModel.atMarker`, which this mapping never touches.
 */
data class NavigatePhasePresentation(
    /** Null except UNCERTAIN (Warning) and OFF_ROUTE (Error). */
    val bannerKind: CosmosBannerKind?,
    /** Ordered actions; the head renders as the primary command. */
    val actions: List<NavigateAction>,
)

fun navigatePhasePresentation(
    phase: NavPhase,
    hasLandmark: Boolean,
    isLastLeg: Boolean,
    showCards: Boolean,
    researchMode: Boolean = false,
): NavigatePhasePresentation = when (phase) {
    NavPhase.ALIGN, NavPhase.ARRIVED ->
        NavigatePhasePresentation(bannerKind = null, actions = emptyList())

    NavPhase.CONFIRM -> NavigatePhasePresentation(
        bannerKind = null,
        actions = buildList {
            if (showCards) add(NavigateAction.SEE_LANDMARK)
            add(NavigateAction.NOT_YET)
        },
    )

    NavPhase.UNCERTAIN -> NavigatePhasePresentation(
        bannerKind = CosmosBannerKind.Warning,
        actions = buildList {
            if (showCards) add(NavigateAction.SEE_LANDMARK)
            add(NavigateAction.LOST)
        },
    )

    NavPhase.OFF_ROUTE -> NavigatePhasePresentation(
        bannerKind = CosmosBannerKind.Error,
        actions = listOf(NavigateAction.GO_BACK_TO_LAST, NavigateAction.RESTART_FROM_START),
    )

    /**
     * Relock search (W12-06): the "Finding you…" instruction plus the
     * tightening σ ring carry the state — no banner (nothing is wrong
     * yet) and no actions (the search resolves itself, or the 20 m
     * fallback raises the OFF_ROUTE sheets).
     */
    NavPhase.RELOCKING -> NavigatePhasePresentation(
        bannerKind = null,
        actions = emptyList(),
    )

    NavPhase.WALK, NavPhase.APPROACH, NavPhase.TURNING -> NavigatePhasePresentation(
        bannerKind = null,
        actions = buildList {
            if (showCards && hasLandmark) add(NavigateAction.SEE_LANDMARK)
            if (phase == NavPhase.WALK && isLastLeg) add(NavigateAction.IM_HERE)
            if (researchMode) add(NavigateAction.AT_MARKER)
            add(NavigateAction.LOST)
        },
    )
}
