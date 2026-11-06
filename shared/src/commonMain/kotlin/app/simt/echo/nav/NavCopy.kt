package app.simt.echo.nav

import app.simt.echo.model.Landmark
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import app.simt.echo.model.TurnDir
import app.simt.echo.platform.HapticPattern
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Navigation copy builders (W10/W12): the human-readable strings and
 * haptic-pattern selection for each phase. Pure functions of the state
 * the [Navigator] passes in, so the copy stays testable without a route
 * and the Navigator keeps only the geometry and phase logic.
 */
internal object NavCopy {

    /**
     * Turn haptic (W10-08): the approach and turning cues speak the next
     * turn's direction; a node with no turn (straight checkpoint) keeps
     * the neutral TICK. Geometry and phases are untouched — this only
     * selects the pattern.
     */
    fun turnCueFor(node: RouteNode?): HapticPattern = when (node?.turn) {
        TurnDir.LEFT -> HapticPattern.LEFT
        TurnDir.RIGHT -> HapticPattern.RIGHT
        TurnDir.U_TURN -> HapticPattern.U_TURN
        null -> HapticPattern.TICK
    }

    fun offRouteCopy(lastAnchorLabel: String?): String =
        "Off route — go back to ${lastAnchorLabel ?: "the last landmark"}"

    /** Title of the landmark nearest to along-route [s], if the route has any. */
    fun nearestLandmarkTitle(landmarks: List<Landmark>, nodeS: (String) -> Double?, s: Double): String? {
        var best: String? = null
        var bestDist = Double.MAX_VALUE
        for (landmark in landmarks) {
            val d = abs((nodeS(landmark.nodeId) ?: return best) - s)
            if (d < bestDist) {
                bestDist = d
                best = landmark.title
            }
        }
        return best
    }

    fun turnWord(dir: TurnDir?): String = when (dir) {
        TurnDir.LEFT -> "left"
        TurnDir.RIGHT -> "right"
        TurnDir.U_TURN -> "around"
        null -> "ahead"
    }

    /**
     * Confirmed level cue (W12-08): the cue confirms itself ("Down 1
     * level ✓"). The only new copy is the confirmation marker; the words
     * come from the node's [RouteNode.levelDelta].
     */
    fun confirmedLevelCue(node: RouteNode): String {
        val floors = abs(node.levelDelta)
        return when {
            floors == 0 -> "Change level ✓"
            node.levelDelta > 0 && floors == 1 -> "Up 1 level ✓"
            node.levelDelta > 0 -> "Up $floors levels ✓"
            floors == 1 -> "Down 1 level ✓"
            else -> "Down $floors levels ✓"
        }
    }

    /** The phase instruction, built from the state the Navigator passes in. */
    fun instruction(
        phase: NavPhase,
        target: RouteNode?,
        landmark: Landmark?,
        alignmentHint: String,
        foundNearLabel: String?,
        foundAtS: Double,
        levelCue: (RouteNode) -> String,
        isLastLeg: Boolean,
        remainingM: Double,
        arrivePromptM: Double,
        lastAnchorLabel: String?,
        uncertainInstruction: String?,
        offRouteInstruction: String?,
    ): String = when (phase) {
        NavPhase.ALIGN -> alignmentHint
        NavPhase.RELOCKING -> "Finding you…"
        NavPhase.WALK -> if (foundAtS.isFinite()) {
            foundNearLabel?.let { "Found you — near $it" } ?: "Found you — back on route"
        } else when {
            target?.type == NodeType.LEVEL_CHANGE -> levelCue(target)
            target?.type == NodeType.DESTINATION && isLastLeg &&
                remainingM <= arrivePromptM -> "You're here — ${target.label}"
            else -> "Walk straight · ${ceil(remainingM.coerceAtLeast(0.0)).toInt()} m"
        }
        NavPhase.APPROACH -> {
            val dir = turnWord(target?.turn)
            val place = landmark?.title ?: target?.label ?: "the turn"
            "In ${ceil(remainingM.coerceAtLeast(0.0)).toInt()} m turn $dir at the $place"
        }
        NavPhase.TURNING -> "Turn ${turnWord(target?.turn)} now"
        NavPhase.CONFIRM -> landmark?.cue?.takeIf { it.isNotEmpty() }
            ?: target?.cue?.takeIf { it.isNotEmpty() }
            ?: "Confirm: ${target?.label ?: "landmark"}"
        NavPhase.UNCERTAIN -> uncertainInstruction ?: "Location uncertain — confirm landmark"
        NavPhase.OFF_ROUTE -> offRouteInstruction ?: offRouteCopy(lastAnchorLabel)
        NavPhase.ARRIVED -> "Arrived at ${target?.label ?: "destination"}"
    }
}
