package app.simt.echo.ui.screens.navigate

import app.simt.echo.model.TurnDir
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import app.simt.echo.nav.ghost.FingerprintPrompt
import app.simt.echo.ui.components.oneDecimal
import kotlin.math.abs
import kotlin.math.ceil

// Navigate instrument 2.0 signals (plan W13-08, luminous-freeze §4 motifs).
//
// Honest-light mapping — every animated element is bound to a named
// signal; with no signal nothing moves. Screenshots are N/A in CI, so
// the pure mapping below (plus the test tags in NavigateInstrumentUi)
// is the V-review hook:
//
// | Element (test tag)              | Named signal                              | FULL                              | REDUCED            | STATIC              |
// |---------------------------------|-------------------------------------------|-----------------------------------|--------------------|---------------------|
// | Morph arrow (NavigateMorphArrow)| NavUi.arrowRad + phase (bend ∝ angle)     | Bend/rotate on spatialDefault     | Snap, no travel    | Same shape at once  |
// | σ aura (NavigateSigmaAura)      | NavUi.uncertaintyM (posterior σ)          | Radius/softness track σ           | Same geometry      | Same geometry       |
// | UNCERTAIN dash + label          | phase == UNCERTAIN (never hidden)         | Dashed ring + "σ ±x m" label      | Same, no animation | Same, no animation  |
// | Periphery glow (NavigateGlow:*) | remaining ≤ 8 m + turning side            | Alpha ∝ 1 − remaining/8          | Same final alpha   | Same final alpha    |
// | Rolling digits                  | NavUi.distanceToNextM (odometer)          | Rolling tabular digits            | Cross-fade         | Snap                |
// | Look-for-this card              | NavUi.landmark (visible landmark)         | Traced mask via MaskedCropImage   | Same, 150 ms fade  | Same at once        |
// | Fingerprint chip                | NavUi.fingerprintPrompt (W12-05)          | Chip asks, never asserts          | Same               | Same                |
// | Relock ring                     | W12-06 state (absent → renders nothing)   | Ring progress                     | Same final state   | Same final state    |
// | Calm mode                       | phase == WALK + arrow ≈ straight          | Chrome dims, text contrast kept   | Same               | Same                |
//
// Phase honesty (S review): UNCERTAIN and OFF_ROUTE never hide
// uncertainty — the σ label and aura stay on in every phase, and only
// UNCERTAIN adds the dashed ring + label emphasis. Calm mode dims
// decorative chrome only; text contrast is never reduced.
// Reduced/Static parity: [navigateInstrumentPresentation] takes no tier,
// so every tier settles to the same info — animation only changes how
// the arrow/glow arrive, never what they say.

/** Morph states of the navigate arrow: straight → bend → U → check. */
enum class ArrowShape {
    STRAIGHT,
    BEND_LEFT,
    BEND_RIGHT,
    U_TURN,
    CHECK,
}

/** Which screen edge the periphery glow sits on. */
enum class GlowSide {
    LEFT,
    RIGHT,
}

/**
 * Relock progress ring state (W12-06 contract placeholder, additive).
 * W12-06 owns this type when it lands; until then the ViewModel passes
 * null and [RelockRing] renders nothing. Null-safe by construction.
 */
data class NavigateRelockState(
    /** Relock search progress, 0..1. */
    val progress01: Double,
)

/** Pure presentation the Navigate instrument renders. */
data class NavigateInstrumentPresentation(
    val arrowShape: ArrowShape,
    /** Bend amount 0..1 across the bend range (angle-proportional). */
    val bendFraction01: Float,
    /** Aura radius in dp; monotonic in σ. */
    val auraRadiusDp: Float,
    /** Aura softness 0..1; monotonic in σ. */
    val auraSoftness01: Float,
    /** Dashed ring + label emphasis; true only in UNCERTAIN. */
    val auraDashed: Boolean,
    /** σ label copy; present in every phase (honesty invariant). */
    val sigmaLabel: String,
    /** Null unless ≤ 8 m from a turn with a known turning side. */
    val glowSide: GlowSide?,
    /** Glow strength ∝ 1 − remaining/8, 0..1. */
    val glowIntensity01: Float,
    /** "Look for this" card with the traced mask. */
    val showLookForThis: Boolean,
    /** Fingerprint match chip copy, e.g. "That's the Red pillar?". */
    val fingerprintChipCopy: String?,
    /** True only when W12-06 relock state is present. */
    val showRelockRing: Boolean,
    /** Walking straight: dim decorative chrome, keep text contrast. */
    val calmMode: Boolean,
)

/** Periphery glow range: on the turning side when this close to a turn. */
const val PERIPHERY_GLOW_RANGE_M = 8.0

/** |arrow| below this reads straight (no bend). */
const val ARROW_STRAIGHT_MAX_RAD = 0.44

/** |arrow| above this reads as a U-turn. */
const val ARROW_UTURN_MIN_RAD = 2.36

/** Side of the square instrument canvas; the aura must fit inside it. */
const val AURA_CANVAS_DP = 200f

/**
 * σ aura geometry: base radius plus per-metre growth, clamped so the
 * largest aura exactly fills the canvas — radius really tracks σ.
 */
const val AURA_BASE_DP = 72f
const val AURA_PER_SIGMA_DP = 5f
const val AURA_MAX_DP = AURA_CANVAS_DP / 2f

/**
 * Pure phase → instrument mapping (W13-08 acceptance: covers all 8
 * [NavPhase]s). Engine-independent by construction: it reads only the
 * [NavUi] snapshot fields passed in, so LINEAR and GHOST render alike.
 */
fun navigateInstrumentPresentation(
    phase: NavPhase,
    arrowRad: Double,
    sigmaM: Double,
    remainingM: Double,
    nextTurn: TurnDir?,
    landmarkPresent: Boolean,
    fingerprintPrompt: FingerprintPrompt?,
    relock: NavigateRelockState?,
): NavigateInstrumentPresentation {
    val shape = arrowShapeFor(phase, arrowRad)
    val remaining = remainingM.coerceAtLeast(0.0)
    val side = glowSideFor(phase, arrowRad, nextTurn)
    val inRange = remaining <= PERIPHERY_GLOW_RANGE_M
    return NavigateInstrumentPresentation(
        arrowShape = shape,
        bendFraction01 = bendFractionFor(arrowRad),
        auraRadiusDp = auraRadiusDpFor(sigmaM),
        auraSoftness01 = auraSoftnessFor(sigmaM),
        auraDashed = phase == NavPhase.UNCERTAIN,
        sigmaLabel = "σ ±${oneDecimal(sigmaM)} m",
        glowSide = side.takeIf { inRange && it != null && phase != NavPhase.ARRIVED },
        glowIntensity01 = glowIntensityFor(remaining).takeIf { inRange } ?: 0f,
        showLookForThis = landmarkPresent &&
            (phase == NavPhase.APPROACH || phase == NavPhase.TURNING || phase == NavPhase.CONFIRM),
        fingerprintChipCopy = fingerprintPrompt?.chipCopy(),
        showRelockRing = relock != null,
        calmMode = phase == NavPhase.WALK && abs(arrowRad) <= ARROW_STRAIGHT_MAX_RAD,
    )
}

/** Arrow morph: ARRIVED checks; U past ~135°; bend with side by sign; else straight. */
fun arrowShapeFor(phase: NavPhase, arrowRad: Double): ArrowShape {
    if (phase == NavPhase.ARRIVED) return ArrowShape.CHECK
    val abs = abs(arrowRad)
    if (abs >= ARROW_UTURN_MIN_RAD) return ArrowShape.U_TURN
    if (abs >= ARROW_STRAIGHT_MAX_RAD) {
        return if (arrowRad > 0) ArrowShape.BEND_RIGHT else ArrowShape.BEND_LEFT
    }
    return ArrowShape.STRAIGHT
}

/** Angle-proportional bend 0..1 across the straight→U range. */
fun bendFractionFor(arrowRad: Double): Float =
    ((abs(arrowRad) - ARROW_STRAIGHT_MAX_RAD) / (ARROW_UTURN_MIN_RAD - ARROW_STRAIGHT_MAX_RAD))
        .coerceIn(0.0, 1.0).toFloat()

/** Aura radius tracks posterior σ: base + k·σ, clamped. Monotonic. */
fun auraRadiusDpFor(sigmaM: Double): Float =
    (AURA_BASE_DP + AURA_PER_SIGMA_DP * sigmaM.coerceAtLeast(0.0).toFloat())
        .coerceIn(AURA_BASE_DP, AURA_MAX_DP)

/** Aura softness tracks posterior σ 0..1 (σ = 6 m reads fully soft). */
fun auraSoftnessFor(sigmaM: Double): Float =
    (sigmaM.coerceAtLeast(0.0) / 6.0).coerceIn(0.0, 1.0).toFloat()

/** Glow side: arrow sign while turning/approaching, else the next turn. */
fun glowSideFor(phase: NavPhase, arrowRad: Double, nextTurn: TurnDir?): GlowSide? {
    if (phase == NavPhase.TURNING || phase == NavPhase.APPROACH) {
        if (abs(arrowRad) >= ARROW_STRAIGHT_MAX_RAD) {
            return if (arrowRad > 0) GlowSide.RIGHT else GlowSide.LEFT
        }
    }
    return when (nextTurn) {
        TurnDir.LEFT -> GlowSide.LEFT
        TurnDir.RIGHT, TurnDir.U_TURN -> GlowSide.RIGHT
        null -> null
    }
}

/** Glow intensity ∝ 1 − remaining/8, 0 at range edge, 1 at the turn. */
fun glowIntensityFor(remainingM: Double): Float =
    (1.0 - remainingM.coerceAtLeast(0.0) / PERIPHERY_GLOW_RANGE_M)
        .coerceIn(0.0, 1.0).toFloat()

/** Ceil metres for copy parity checks (mirrors Navigator's instruction rule). */
internal fun ceilMetres(value: Double): Int = ceil(value.coerceAtLeast(0.0)).toInt()

/**
 * Shared snapshot both Navigate bodies render (camera HUD and plain).
 * W12-06 has not landed, so relock is always null here and the ring
 * renders nothing (additive, null-safe).
 */
fun navigateInstrumentFor(nav: NavUi, landmarkPresent: Boolean): NavigateInstrumentPresentation =
    navigateInstrumentPresentation(
        phase = nav.phase,
        arrowRad = nav.arrowRad,
        sigmaM = nav.uncertaintyM,
        remainingM = nav.distanceToNextM,
        nextTurn = nav.nextTurn,
        landmarkPresent = landmarkPresent,
        fingerprintPrompt = nav.fingerprintPrompt,
        relock = null,
    )
