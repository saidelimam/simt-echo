package app.simt.echo.ui.screens.record

import app.simt.echo.capture.LedgerOffer
import app.simt.echo.model.EchoConfig
import app.simt.echo.nav.TurnDetector
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.platform.currentEpochMs
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import kotlin.math.cos
import kotlin.math.sin

// Record "Echo walk" signals (plan W13-04, luminous-freeze §4 motifs).
//
// Honest-light mapping — every animated element is bound to a named
// signal; with no signal nothing moves:
//
// | Element (test tag)              | Named signal                              | FULL                              | REDUCED            | STATIC              |
// |---------------------------------|-------------------------------------------|-----------------------------------|--------------------|---------------------|
// | Step ripples (EchoWalkRipples)  | MotionUpdate.isStep (PdrEngine step event) | Ring from alternating edge-band origin, 900 ms (rippleFrame), nothing over centre 60 % | None (step chip carries it) | None |
// | Trail ribbon (EchoWalkTrail)    | MotionUpdate distance + yaw (odometer)    | Heading-up tapered path, pseudo-perspective | Same path, no bloom travel | Same path at once |
// | Node blooms (EchoWalkNode:*)    | TurnDetector.DetectedTurn / hud.marks     | Scale 0→1 on spatialExpressive + 6 fading rays | Fade in 150 ms | Final dot at once |
// | Gold brackets (EchoWalkBrackets)| LedgerOffer Kept/Replaced, top-decile + novel | Brackets + "Great view captured" for 300 ms | Same, 150 ms fade | Same, no fade |
// | Rolling digits (EchoWalkDistance)| hud.distanceM (odometer)                 | Rolling tabular digits, spatialFast per digit | Cross-fade | Snap |
// | Heading tick (RecordHeading)    | MotionUpdate.yawRad (display only)        | Unchanged (kept)                  | Unchanged          | Unchanged           |
//
// CaptureSession is untouched: the tracker below is a second,
// presentation-only reader of the same motion flow (PDR multicasts),
// exactly like the heading tick. The ledger highlight is a nullable
// consumer seam: the FrameTap→ledger pipeline (W11-02) forwards offers
// via [EchoWalkTracker.onLedgerOffer]; until then null shows nothing.
// Screenshots are N/A in CI, so the tags above are the V-review hooks.

/** Annotated points on the walked trail. */
enum class TrailNodeKind {
    /** Walk start (first motion sample). */
    START,

    /** Settled online turn from [TurnDetector]. */
    TURN,

    /** User mark (hud.marks increased). */
    MARK,
}

/** One sampled trail point; [node] is set only on annotated points. */
data class TrailPoint(
    val id: Long,
    val distanceM: Double,
    val yawRad: Double,
    val node: TrailNodeKind? = null,
)

/** One step ripple: which edge band it leaves from, and when. */
data class StepRipple(
    val id: Long,
    val leftEdge: Boolean,
    val startMs: Long,
)

/**
 * A ledger frame worth a bracket: top-decile score and novel hash.
 * Copy is "great view captured" — never an object claim.
 */
data class LedgerHighlight(
    val score: Double,
    val novelty: Double,
    val atMs: Long,
)

/** Immutable snapshot the Record instrument renders (event-rate, never frame-rate). */
data class EchoWalkSnapshot(
    val ripples: List<StepRipple> = emptyList(),
    val trail: List<TrailPoint> = emptyList(),
    val highlight: LedgerHighlight? = null,
)

/** Recent trail kept for the ribbon (metres behind the head). */
const val ECHO_WALK_TRAIL_WINDOW_M = 12.0

/** Hard cap on sampled trail points (event-rate list, tiny). */
const val ECHO_WALK_TRAIL_MAX_POINTS = 64

/** Brackets stay up for one state beat (token, not a magic number). */
const val LEDGER_BRACKET_MS = CosmosMotionDurations.STATE_MS

/** Prior kept scores needed before anything can be "top-decile". */
const val LEDGER_TOP_DECILE_MIN_PRIOR = 9

/** Minimum novelty (fraction of 64 dHash bits) for a bracket. */
const val LEDGER_NOVELTY_MIN = 0.25

/**
 * Presentation-only walk tracker (W13-04). Fed by the Record screen from
 * the shared motion flow plus hud.marks; never touches CaptureSession.
 *
 * - Steps → edge-band ripples (side alternates per step, like footsteps).
 * - Every yaw sample → [TurnDetector]; settled turns annotate the trail.
 * - Mark count increases → MARK node at the latest position.
 * - Kept ledger offers → [LedgerHighlight] when top-decile and novel.
 */
class EchoWalkTracker(
    private val nowMs: () -> Long = { currentEpochMs() },
    config: EchoConfig = EchoConfig(),
) {
    private val turns = TurnDetector(config)
    private val points = ArrayList<TrailPoint>()
    private val ripples = ArrayList<StepRipple>()
    private val keptScores = ArrayList<Double>()
    private var nextId = 0L
    private var leftEdge = false
    private var lastMarks = 0
    private var lastYaw: Double? = null
    private var highlight: LedgerHighlight? = null

    fun onMotion(update: MotionUpdate) {
        lastYaw = update.yawRad
        if (points.isEmpty()) {
            appendPoint(update.distanceM, update.yawRad, TrailNodeKind.START)
        }
        if (update.isStep) {
            leftEdge = !leftEdge
            ripples += StepRipple(id = nextId++, leftEdge = leftEdge, startMs = nowMs())
            appendPoint(update.distanceM, update.yawRad, null)
        }
        val turn = turns.onSample(update.tNs, update.yawRad)
        if (turn != null) {
            appendPoint(update.distanceM, update.yawRad, TrailNodeKind.TURN)
        }
    }

    fun onMarks(marks: Int) {
        if (marks > lastMarks) {
            lastMarks = marks
            val anchor = points.lastOrNull()
            if (anchor != null) {
                points += anchor.copy(id = nextId++, node = TrailNodeKind.MARK)
                trimTrail()
            }
        } else {
            lastMarks = marks
        }
    }

    /** Feeds one ledger outcome; brackets only Kept/Replaced top-decile novel frames. */
    fun onLedgerOffer(offer: LedgerOffer) {
        if (offer !is LedgerOffer.Kept && offer !is LedgerOffer.Replaced) return
        if (isTopDecileAndNovel(offer.score, offer.novelty, keptScores)) {
            highlight = LedgerHighlight(score = offer.score, novelty = offer.novelty, atMs = nowMs())
        }
        keptScores += offer.score
    }

    fun clearHighlight() {
        highlight = null
    }

    fun snapshot(): EchoWalkSnapshot {
        val now = nowMs()
        ripples.removeAll { now - it.startMs >= app.simt.echo.ui.atmosphere.ECHO_RIPPLE_DURATION_MS }
        trimTrail()
        return EchoWalkSnapshot(
            ripples = ripples.toList(),
            trail = points.toList(),
            highlight = highlight,
        )
    }

    /** Latest yaw seen (null before the first sample); tests + ribbon fallback. */
    fun lastYawRad(): Double? = lastYaw

    private fun appendPoint(distanceM: Double, yawRad: Double, node: TrailNodeKind?) {
        points += TrailPoint(id = nextId++, distanceM = distanceM, yawRad = yawRad, node = node)
        trimTrail()
    }

    private fun trimTrail() {
        val head = points.lastOrNull()?.distanceM ?: return
        while (points.size > 1 && head - points.first().distanceM > ECHO_WALK_TRAIL_WINDOW_M) {
            points.removeAt(0)
        }
        while (points.size > ECHO_WALK_TRAIL_MAX_POINTS) {
            val plain = points.indexOfFirst { it.node == null }
            points.removeAt(if (plain >= 0) plain else 0)
        }
    }
}

/**
 * Top-decile + novel rule (pure, unit-tested): [score] beats at least 90 %
 * of [priorKeptScores] with enough history to mean it, and [novelty] clears
 * [LEDGER_NOVELTY_MIN]. The current score is judged against priors only —
 * it joins history afterwards in [EchoWalkTracker.onLedgerOffer].
 */
fun isTopDecileAndNovel(score: Double, novelty: Double, priorKeptScores: List<Double>): Boolean {
    if (priorKeptScores.size < LEDGER_TOP_DECILE_MIN_PRIOR) return false
    if (novelty < LEDGER_NOVELTY_MIN) return false
    val beaten = priorKeptScores.count { it < score }
    return beaten.toDouble() / priorKeptScores.size >= 0.9
}

/** One trail point in head frame: metres right (+) and behind (+) the walker. */
data class TrailVec(val lateralM: Double, val behindM: Double)

/**
 * Projects [points] into the head frame (heading-up): reconstructed from
 * odometer distance + yaw only — the same data CaptureSession logs, no new
 * geometry. Forward integrates east/north segments, then rotates by
 * -[headYaw] so the head faces up and the past falls below.
 */
fun projectTrail(points: List<TrailPoint>, headYaw: Double): List<TrailVec> {
    if (points.isEmpty()) return emptyList()
    val east = DoubleArray(points.size)
    val north = DoubleArray(points.size)
    for (i in 1 until points.size) {
        val dd = (points[i].distanceM - points[i - 1].distanceM).coerceAtLeast(0.0)
        val segYaw = points[i - 1].yawRad
        east[i] = east[i - 1] + dd * sin(segYaw)
        north[i] = north[i - 1] + dd * cos(segYaw)
    }
    val headE = east.last()
    val headN = north.last()
    val fE = sin(headYaw)
    val fN = cos(headYaw)
    val rE = cos(headYaw)
    val rN = -sin(headYaw)
    return points.indices.map { i ->
        val relE = east[i] - headE
        val relN = north[i] - headN
        TrailVec(
            lateralM = relE * rE + relN * rN,
            behindM = -(relE * fE + relN * fN),
        )
    }
}

/** True while the bracket window around [highlight] is open at [nowMs]. */
fun isBracketOpen(highlight: LedgerHighlight?, nowMs: Long): Boolean =
    highlight != null && nowMs - highlight.atMs < LEDGER_BRACKET_MS

/** Ripple origins live in the outer 20 % bands; the centre 60 % stays clear. */
fun StepRipple.xFraction(): Float = if (leftEdge) 0.12f else 0.88f

/** Height alternates deterministically so consecutive rings never stack. */
fun StepRipple.yFraction(): Float = 0.45f + 0.1f * (id % 2)
