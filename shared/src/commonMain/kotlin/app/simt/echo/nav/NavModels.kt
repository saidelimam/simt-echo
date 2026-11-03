package app.simt.echo.nav

import app.simt.echo.model.Landmark
import app.simt.echo.model.TurnDir
import app.simt.echo.nav.ghost.FingerprintPrompt
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.MotionUpdate

/**
 * Navigation contract (implementation plan §6.4, W2-05).
 *
 * Pure sensor-driven types: geometry originates from [MotionUpdate]s only,
 * never from the network or AI output (§0 rule — `nav/` imports no HTTP).
 * Screens in W2-06 render [NavUi] and forward user actions as [NavInput]s;
 * side effects (haptics, anchors, arrival) surface as [NavEvent]s.
 */

/** Navigator phases, 1:1 with the §6.4 state table. */
enum class NavPhase {
    ALIGN,
    WALK,
    APPROACH,
    TURNING,
    CONFIRM,
    UNCERTAIN,
    OFF_ROUTE,
    ARRIVED,
    /**
     * Lost → relock search (W12-06): particles re-spread uniformly and
     * reconverge from the turn signature, fingerprints and magnetic
     * offsets. Renders "Finding you…" with the tightening σ ring; a lock
     * (σ < 3 m) returns to WALK with a transient "Found you" cue, while
     * 20 m without convergence falls back to OFF_ROUTE sheets.
     */
    RELOCKING,
}

/** Which kind of node confirmation anchored the odometer. */
enum class AnchorKind {
    TURN,
    LANDMARK,
    /** Barometer-confirmed LEVEL_CHANGE node (W12-08 self-confirm). */
    LEVEL,
}

/** Immutable snapshot the Navigate screen renders. */
data class NavUi(
    val phase: NavPhase,
    val legIndex: Int,
    val instruction: String,
    /** Metres left on the current leg, never negative. */
    val distanceToNextM: Double,
    /** Signed arrow error in radians, clockwise-positive; 0 = on heading. */
    val arrowRad: Double,
    val nextTurn: TurnDir?,
    /** Next node's landmark once it should be visible, else null. */
    val landmark: Landmark?,
    /** Odometer uncertainty σ in metres since the last anchor. */
    val uncertaintyM: Double,
    /** Current leg completion, 0..1. */
    val progress01: Double,
    /**
     * Pending fingerprint suggestion (W12-05 hook): a gated match proposed
     * "That's the …?" and waits for user tap or a consistent turn event.
     * Null when nothing is proposed. The full W13-08 instrument renders
     * this; the state machine never acts on it alone.
     */
    val fingerprintPrompt: FingerprintPrompt? = null,
    /** Navigation runs on sensors only, so this is always true. */
    val offline: Boolean = true,
    /**
     * Honest-light visual lock confidence (plan W19-01): in `0.5..1.0` only
     * while `VisualEgo.confidence01 >= 0.5` AND the filter used a visual
     * observation within the last 2 s; `0.0` otherwise.
     */
    val visualLockConfidence01: Double = 0.0,
    /**
     * Quiet single-line usability nudge (plan W19-01) when the phone is held
     * flat, against a blank wall, or in the dark; null when clear.
     */
    val visualNudge: String? = null,
)

/** Everything that can drive the state machine: motion and user actions. */
sealed interface NavInput {
    data class Motion(val update: MotionUpdate) : NavInput

    /** User matched the start view: zero the odometer and heading here. */
    data object Aligned : NavInput

    /** User confirms the expected landmark/node is here. */
    data object SeeLandmark : NavInput

    /** User dismisses a CONFIRM card without confirming. */
    data object NotYet : NavInput

    /** User reports being lost; offers restart choices. */
    data object Lost : NavInput

    /** Resume from the last anchored node. */
    data object RestartFromLast : NavInput

    /** Resume from the route start. */
    data object RestartFromStart : NavInput

    /** User confirms arrival at the destination. */
    data object ImHere : NavInput
}

/** One-shot side effects for the UI layer to perform or record. */
sealed interface NavEvent {
    data class Haptic(val pattern: HapticPattern) : NavEvent

    data class Anchor(val kind: AnchorKind, val nodeId: String, val dist: Double) : NavEvent

    data class Phase(val from: NavPhase, val to: NavPhase) : NavEvent

    data object Arrived : NavEvent
}
