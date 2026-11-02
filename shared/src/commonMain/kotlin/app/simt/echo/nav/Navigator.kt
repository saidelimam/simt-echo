package app.simt.echo.nav

import app.simt.echo.capture.circularMean
import app.simt.echo.capture.wrapPi
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.Landmark
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.TurnDir
import app.simt.echo.model.defaultCue
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.FingerprintHit
import app.simt.echo.nav.ghost.FingerprintPrompt
import app.simt.echo.nav.ghost.Observation
import app.simt.echo.nav.ghost.RouteGeometry
import app.simt.echo.nav.ghost.StrideLearner
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Offline navigation state machine (implementation plan §6.4).
 *
 * Consumes sensor [MotionUpdate]s and user actions, emits render state on
 * [ui] and one-shot effects on [events]. Geometry comes from sensors only:
 * this class (and the whole `nav/` package) imports no networking.
 *
 * Uncertainty is honest by construction: σ grows since the last anchor,
 * and overshooting a node raises [NavPhase.UNCERTAIN] instead of silently
 * advancing. Thresholds live in [EchoConfig] for W4-02 tuning.
 */
class Navigator(
    private val route: EchoRoute,
    private val config: EchoConfig = EchoConfig(),
    ghostSeed: Long = GhostFilter.DEFAULT_SEED,
) {
    private val turnDetector = TurnDetector(config)

    /**
     * Ghost engine (W12-02): non-null exactly when
     * [EchoConfig.navigatorEngine] is GHOST. Its posterior mean/σ replace
     * odometer progress and the linear σ below; every phase rule,
     * threshold and copy string is shared and unchanged.
     */
    private val geometry = RouteGeometry(route)

    /**
     * Self-calibrating stride (W12-03): restored from the persisted
     * per-device posterior, fed one observation per anchored leg. The
     * Ghost `k` prior seeds from it at construction; phases, geometry
     * and thresholds are untouched.
     */
    private val strideLearner = StrideLearner.fromStored(config.learnedStepK, config.learnedStrideLegs)
    private val ghost: GhostFilter? =
        if (config.navigatorEngine == NavigatorEngine.GHOST) {
            GhostFilter(route, ghostSeed, kPriorMean = strideLearner.meanK)
        } else {
            null
        }

    /** Posterior mean K learned from anchored legs so far. */
    val learnedStrideK: Double get() = strideLearner.meanK

    /** Anchored legs folded into [learnedStrideK] so far. */
    val learnedStrideLegs: Int get() = strideLearner.legs

    /** Previous raw odometer reading, for Ghost per-update deltas. */
    private var prevOdoM: Double = 0.0

    /**
     * Raw odometer value where the current leg began. The overshoot guard
     * below reads this on both engines: overshoot compares metres actually
     * walked against the leg length, independent of the position belief
     * (on LINEAR it always equals [legStartDist]).
     */
    private var legStartOdo: Double = 0.0

    private var phase: NavPhase = NavPhase.ALIGN
    private var legIndex: Int = 0
    private var dist: Double = 0.0
    private var yaw: Double = 0.0

    /** Odometer value where the current leg began (B's odometer). */
    private var legStartDist: Double = 0.0

    /** Odometer value at the last confirmed node; σ grows from here. */
    private var lastAnchorDist: Double = 0.0
    private var lastAnchorLegIndex: Int = 0

    /** Added to raw yaw so the current leg heading reads as arrow 0. */
    private var yawOffset: Double = 0.0

    private var tickedLegIndex: Int = -1
    private var confirmEntryDist: Double = 0.0
    private var confirmAutoAdvanceProgress: Double? = null
    private var uncertainInstruction: String? = null
    private var offRouteInstruction: String? = null

    /**
     * Pending fingerprint suggestion (W12-05): a gated match proposed a
     * "That's the …?" chip. It never advances, anchors, or confirms on
     * its own — only [confirmFingerprint] (user tap) or a consistent turn
     * event in [onTurnDetected] resolves it.
     */
    private var fingerprintPrompt: FingerprintPrompt? = null

    /**
     * Live level awareness (W12-08): relative-pressure Δh from the
     * leg-start baseline. Reset on every leg transition; confirmed node
     * ids persist for the route so the cue keeps its ✓.
     */
    private val levelAwareness = LevelAwareness(config.levelThresholdM)
    private val confirmedLevelNodeIds = mutableSetOf<String>()

    /** Recent (distance, raw yaw) for corridor snapping. */
    private val yawTrail = mutableListOf<Pair<Double, Double>>()

    /** Raw yaws seen on the current leg, for the wrong-start rule. */
    private val legYaws = mutableListOf<Double>()

    private val _ui = MutableStateFlow(buildUi())
    val ui: StateFlow<NavUi> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NavEvent> = _events.asSharedFlow()

    fun onInput(input: NavInput) {
        when (input) {
            is NavInput.Motion -> onMotion(input.update)
            NavInput.Aligned -> onAligned()
            NavInput.SeeLandmark -> onSeeLandmark()
            NavInput.NotYet -> onNotYet()
            NavInput.Lost -> onLost()
            NavInput.RestartFromLast -> restart(lastAnchorLegIndex)
            NavInput.RestartFromStart -> restart(0)
            NavInput.ImHere -> onImHere()
        }
        refresh()
    }

    private val leg: RouteLeg? get() = route.legs.getOrNull(legIndex)
    private val targetNode: RouteNode?
        get() = leg?.let { l -> route.nodes.firstOrNull { it.id == l.toNode } }
    private val anchorNode: RouteNode?
        get() = route.nodes.getOrNull(lastAnchorLegIndex)
    /** Absolute route position: raw odometer on LINEAR, filter mean on GHOST. */
    private val absoluteS: Double get() = ghost?.meanS ?: dist
    private val progress: Double get() = absoluteS - legStartDist
    private val remaining: Double get() = (leg?.lengthM ?: 0.0) - progress
    private val uncertainty: Double
        get() = ghost?.sigma
            ?: (config.navUncertaintyBaseM + config.navUncertaintyPerMetre * (dist - lastAnchorDist))
    private val arrow: Double
        get() = leg?.let { wrapPi(degToRad(it.headingRelDeg) - (yaw + yawOffset)) } ?: 0.0
    private val isLastLeg: Boolean get() = legIndex >= route.legs.lastIndex

    private fun landmarkFor(node: RouteNode?): Landmark? =
        node?.let { n -> route.landmarks.firstOrNull { it.nodeId == n.id } }

    private fun onAligned() {
        if (phase != NavPhase.ALIGN) return
        legStartDist = dist
        lastAnchorDist = dist
        legStartOdo = dist
        lastAnchorLegIndex = 0
        yawOffset = wrapPi(degToRad(leg?.headingRelDeg ?: 0.0) - yaw)
        turnDetector.reset()
        yawTrail.clear()
        legYaws.clear()
        prevOdoM = dist
        levelAwareness.resetLeg()
        ghost?.let {
            // Route position is known exactly at alignment: odometer
            // domain (dist) is replaced by route domain (s = 0).
            legStartDist = 0.0
            lastAnchorDist = 0.0
            it.reset(0.0)
        }
        setPhase(NavPhase.WALK)
    }

    private fun onMotion(update: MotionUpdate) {
        dist = update.distanceM
        yaw = update.yawRad
        if (phase != NavPhase.WALK && phase != NavPhase.APPROACH &&
            phase != NavPhase.TURNING && phase != NavPhase.CONFIRM
        ) {
            return
        }
        legYaws += update.yawRad
        ghost?.let {
            val delta = dist - prevOdoM
            prevOdoM = dist
            if (delta > 0.0) it.predict(delta)
            it.observe(Observation.Heading(yaw + yawOffset, update.headingQuality))
        }
        applyCorridorSnapping(update)
        levelAwareness.onSample(update.pressureHpa, update.stepCount)?.let { onLevelEvent(it) }
        val detected = turnDetector.onSample(update.tNs, update.yawRad)
        if (detected != null) {
            onTurnDetected(detected)
            return
        }
        if (isWrongStart()) {
            enterOffRoute("This doesn't match the start — re-align")
            return
        }
        if (uncertainty > config.navUncertaintyMaxM && phase != NavPhase.TURNING) {
            enterUncertain(null)
            return
        }
        when (phase) {
            NavPhase.WALK, NavPhase.APPROACH -> onWalkMotion()
            NavPhase.CONFIRM -> {
                if (absoluteS - confirmEntryDist >= config.navConfirmAutoAdvanceM) advanceLeg(null)
            }
            NavPhase.TURNING -> if (overshot()) enterUncertain(null)
            else -> Unit
        }
    }

    private fun onWalkMotion() {
        val target = targetNode ?: return
        val currentLeg = leg ?: return
        when {
            target.type == NodeType.TURN -> {
                if (remaining <= max(config.navApproachMinM, config.navApproachFraction * currentLeg.lengthM)) {
                    if (phase == NavPhase.WALK) {
                        setPhase(NavPhase.APPROACH)
                        if (tickedLegIndex != legIndex) {
                            tickedLegIndex = legIndex
                            emit(NavEvent.Haptic(turnCueFor(target)))
                        }
                    }
                }
                if (overshot()) enterUncertain(null)
            }
            target.type == NodeType.DESTINATION && isLastLeg -> {
                if (remaining <= -config.navArriveAutoPastM) arrive()
            }
            confirmAutoAdvanceProgress != null ->
                if (progress >= (confirmAutoAdvanceProgress ?: Double.MAX_VALUE)) advanceLeg(null)
            landmarkFor(target) != null -> if (progress >= currentLeg.lengthM) enterConfirm()
            else -> if (progress >= currentLeg.lengthM) advanceLeg(null)
        }
    }

    private fun onTurnDetected(detected: DetectedTurn) {
        val target = targetNode ?: return
        ghost?.observe(Observation.Turn(detected.dir))
        val expected = target.turn?.takeIf { target.type == NodeType.TURN }
        when {
            expected == null -> enterOffRoute(offRouteCopy())
            detected.dir != expected -> enterOffRoute(offRouteCopy())
            phase == NavPhase.TURNING -> {
                // A consistent turn event also resolves a pending
                // fingerprint suggestion (W12-05 confirm rule).
                fingerprintPrompt = null
                advanceLeg(AnchorKind.TURN)
            }
            progress >= config.navTurnMinProgressFraction * (leg?.lengthM ?: 0.0) -> {
                fingerprintPrompt = null
                advanceLeg(AnchorKind.TURN)
            }
            else -> enterUncertain("Turned early? Expected in ${ceil(remaining).toInt()} m")
        }
    }

    private fun onSeeLandmark() {
        if (phase == NavPhase.ALIGN || phase == NavPhase.ARRIVED) return
        val target = targetNode ?: return
        val currentLeg = leg ?: return
        strideLearner.observe(currentLeg.lengthM, dist - legStartOdo)
        // We are at the node: snap the odometer so progress reads exactly
        // the leg length, and anchor σ here.
        legStartDist = dist - currentLeg.lengthM
        lastAnchorDist = dist
        legStartOdo = dist - currentLeg.lengthM
        ghost?.let {
            // Hard anchor: the filter collapses to the node ± 1 m and the
            // leg origin moves with it in route domain.
            val nodeS = geometry.nodeS(target.id) ?: it.meanS
            it.observe(Observation.Anchor(nodeS))
            legStartDist = nodeS - currentLeg.lengthM
            lastAnchorDist = it.meanS
        }
        lastAnchorLegIndex = (legIndex + 1).coerceAtMost(route.legs.lastIndex)
        emit(NavEvent.Anchor(AnchorKind.LANDMARK, target.id, absoluteS))
        confirmAutoAdvanceProgress = null
        when {
            target.type == NodeType.TURN -> {
                if (phase != NavPhase.TURNING) {
                    setPhase(NavPhase.TURNING)
                    emit(NavEvent.Haptic(turnCueFor(target)))
                }
            }
            target.type == NodeType.DESTINATION && isLastLeg -> arrive()
            else -> advanceLeg(null)
        }
    }

    /**
     * Fingerprint suggestion (W12-05): a gated [FingerprintHit] (cosine ≥
     * τ, same embedder id + seed, within s ± (3σ + 5 m)) becomes a
     * low-weight filter observation plus a pending "That's the …?" chip
     * and a soft TICK — deliberately not CONFIRM, which would imply the
     * match already confirmed something. Phases, legs and anchors are
     * untouched: without [confirmFingerprint] or a consistent turn event,
     * nothing else happens. Never a silent auto-confirm.
     */
    fun onFingerprintHit(hit: FingerprintHit) {
        if (phase == NavPhase.ALIGN || phase == NavPhase.ARRIVED) return
        ghost?.observe(Observation.Fingerprint(hit.nodeS, hit.similarity01))
        fingerprintPrompt = FingerprintPrompt(hit.label, hit.nodeS)
        emit(NavEvent.Haptic(HapticPattern.TICK))
        refresh()
    }

    /**
     * User tapped the fingerprint chip: the suggestion confirms into a
     * soft anchor at the suggested position (stride and bias survive,
     * like [GhostFilter.anchorAt]) and the chip clears. No-op without a
     * pending prompt.
     */
    fun confirmFingerprint() {
        val prompt = fingerprintPrompt ?: return
        fingerprintPrompt = null
        ghost?.observe(Observation.Anchor(prompt.nodeS))
        lastAnchorDist = absoluteS
        emit(NavEvent.Haptic(HapticPattern.CONFIRM))
        refresh()
    }

    /** User dismissed the fingerprint chip: proposal dropped, no anchor. */
    fun dismissFingerprint() {
        if (fingerprintPrompt == null) return
        fingerprintPrompt = null
        refresh()
    }

    /**
     * Barometer crossing (W12-08): the walker's |Δh| since the leg start
     * passed 2.2 m, so the current LEVEL_CHANGE target confirms itself —
     * a filter observation on GHOST plus an anchor event on both engines.
     * A sign mismatch against the node's [RouteNode.levelDelta] (route
     * says up, pressure says down) stays unconfirmed rather than
     * anchoring the wrong belief.
     */
    private fun onLevelEvent(event: LevelAwareness.LevelEvent) {
        val target = targetNode ?: return
        if (target.type != NodeType.LEVEL_CHANGE) return
        if (target.levelDelta != 0 && (event.deltaHM > 0.0) != (target.levelDelta > 0)) return
        if (!confirmedLevelNodeIds.add(target.id)) return
        ghost?.let {
            val nodeS = geometry.nodeS(target.id) ?: it.meanS
            it.observe(Observation.Level(nodeS))
        }
        emit(NavEvent.Anchor(AnchorKind.LEVEL, target.id, absoluteS))
        emit(NavEvent.Haptic(HapticPattern.CONFIRM))
    }

    private fun onNotYet() {
        if (phase != NavPhase.CONFIRM) return
        // Dismissed, not confirmed: keep walking this leg; auto-advance only
        // after the CONFIRM grace distance measured from leg progress.
        confirmAutoAdvanceProgress = progress + config.navConfirmAutoAdvanceM
        setPhase(NavPhase.WALK)
    }

    private fun onLost() {
        if (phase == NavPhase.ALIGN || phase == NavPhase.ARRIVED) return
        enterOffRoute(offRouteCopy())
    }

    private fun onImHere() {
        if (phase == NavPhase.ARRIVED || !isLastLeg) return
        if (targetNode?.type == NodeType.DESTINATION && remaining <= config.navArrivePromptM) {
            arrive()
        }
    }

    private fun restart(toLegIndex: Int) {
        if (phase != NavPhase.OFF_ROUTE && phase != NavPhase.UNCERTAIN) return
        legIndex = toLegIndex.coerceIn(0, route.legs.lastIndex)
        legStartDist = dist
        lastAnchorDist = dist
        legStartOdo = dist
        lastAnchorLegIndex = legIndex
        yawOffset = wrapPi(degToRad(leg?.headingRelDeg ?: 0.0) - yaw)
        tickedLegIndex = -1
        confirmAutoAdvanceProgress = null
        turnDetector.reset()
        yawTrail.clear()
        legYaws.clear()
        prevOdoM = dist
        levelAwareness.resetLeg()
        ghost?.let {
            val s = geometry.legStartS(legIndex)
            legStartDist = s
            lastAnchorDist = s
            it.reset(s)
        }
        setPhase(NavPhase.WALK)
    }

    private fun advanceLeg(anchorKind: AnchorKind?) {
        val target = targetNode
        if (anchorKind != null && target != null) {
            leg?.let { strideLearner.observe(it.lengthM, dist - legStartOdo) }
            emit(NavEvent.Anchor(anchorKind, target.id, absoluteS))
            emit(NavEvent.Haptic(HapticPattern.CONFIRM))
        }
        if (isLastLeg) {
            arrive()
            return
        }
        legIndex += 1
        // A suggestion belongs to the leg it fired on; never carry it over.
        fingerprintPrompt = null
        legStartDist = dist
        lastAnchorDist = dist
        legStartOdo = dist
        ghost?.let {
            // Absolute filter position is continuous across legs; only the
            // leg origin moves, to the new leg's start in route domain.
            val s = geometry.legStartS(legIndex)
            legStartDist = s
            lastAnchorDist = it.meanS
        }
        lastAnchorLegIndex = legIndex
        confirmAutoAdvanceProgress = null
        legYaws.clear()
        levelAwareness.resetLeg()
        setPhase(NavPhase.WALK)
    }

    private fun enterConfirm() {
        confirmEntryDist = absoluteS
        setPhase(NavPhase.CONFIRM)
    }

    private fun enterUncertain(instruction: String?) {
        if (phase == NavPhase.UNCERTAIN) return
        uncertainInstruction = instruction
        emit(NavEvent.Haptic(HapticPattern.WARN))
        setPhase(NavPhase.UNCERTAIN)
    }

    private fun enterOffRoute(instruction: String) {
        if (phase == NavPhase.OFF_ROUTE) return
        offRouteInstruction = instruction
        emit(NavEvent.Haptic(HapticPattern.WARN))
        setPhase(NavPhase.OFF_ROUTE)
    }

    private fun arrive() {
        if (phase == NavPhase.ARRIVED) return
        fingerprintPrompt = null
        setPhase(NavPhase.ARRIVED)
        emit(NavEvent.Haptic(HapticPattern.ARRIVE))
        emit(NavEvent.Arrived)
    }

    private fun setPhase(to: NavPhase) {
        if (phase == to) return
        val from = phase
        phase = to
        if (to != NavPhase.UNCERTAIN) uncertainInstruction = null
        if (to != NavPhase.OFF_ROUTE) offRouteInstruction = null
        emit(NavEvent.Phase(from, to))
    }

    private fun emit(event: NavEvent) {
        _events.tryEmit(event)
    }

    // Wrong start (§6.4): the first leg's sustained heading (circular mean
    // since the leg began, not the latest yaw) is far off once
    // [EchoConfig.navWrongStartDistanceM] is walked. The mean keeps a
    // legitimate mid-leg turn from being misread as a wrong start.
    private fun isWrongStart(): Boolean {
        if (legIndex != 0 || phase != NavPhase.WALK) return false
        if (progress < config.navWrongStartDistanceM || legYaws.isEmpty()) return false
        val sustainedArrow = wrapPi(
            degToRad(leg?.headingRelDeg ?: 0.0) - (circularMean(legYaws) + yawOffset),
        )
        return abs(sustainedArrow) > degToRad(config.navWrongStartHeadingErrorDeg)
    }

    private fun overshot(): Boolean {
        val length = leg?.lengthM ?: return false
        return dist - legStartOdo > length + max(
            config.navUncertainOvershootMinM,
            config.navUncertainOvershootFraction * length,
        )
    }

    private fun offRouteCopy(): String =
        "Off route — go back to ${anchorNode?.label ?: "the last landmark"}"

    /**
     * Turn haptic (W10-08): the approach and turning cues speak the next
     * turn's direction; a node with no turn (straight checkpoint) keeps
     * the neutral TICK. Geometry and phases are untouched — this only
     * selects the pattern.
     */
    private fun turnCueFor(node: RouteNode?): HapticPattern = when (node?.turn) {
        TurnDir.LEFT -> HapticPattern.LEFT
        TurnDir.RIGHT -> HapticPattern.RIGHT
        TurnDir.U_TURN -> HapticPattern.U_TURN
        null -> HapticPattern.TICK
    }

    /**
     * Corridor snapping (§6.4): on a straight, steady corridor the arrow
     * error is gyro/alignment drift, so absorb a fraction of it into
     * [yawOffset] per motion update instead of blaming the walker.
     */
    private fun applyCorridorSnapping(update: MotionUpdate) {
        yawTrail += update.distanceM to update.yawRad
        yawTrail.removeAll { it.first < update.distanceM - config.navCorridorWindowM }
        if (phase != NavPhase.WALK || yawTrail.size < 2) return
        val span = yawTrail.last().first - yawTrail.first().first
        if (span < config.navCorridorWindowM - 1e-9) return
        val yaws = yawTrail.map { it.second }
        val mean = circularMean(yaws)
        val std = sqrt(yaws.sumOf { val d = wrapPi(it - mean); d * d } / yaws.size)
        val currentArrow = arrow
        if (std < degToRad(config.navCorridorYawStdDeg) &&
            abs(currentArrow) < degToRad(config.navCorridorArrowMaxDeg)
        ) {
            yawOffset = wrapPi(yawOffset + config.navCorridorCorrection * currentArrow)
        }
    }

    private fun refresh() {
        _ui.value = buildUi()
    }

    private fun buildUi(): NavUi {
        val target = targetNode
        val currentLeg = leg
        val landmark = landmarkFor(target)
        val visible = landmark != null && (
            phase == NavPhase.APPROACH || phase == NavPhase.TURNING || phase == NavPhase.CONFIRM ||
                remaining <= max(
                    landmark.visibleFromM ?: config.navLandmarkVisibleDefaultM,
                    config.navLandmarkVisibleMinM,
                )
            )
        return NavUi(
            phase = phase,
            legIndex = legIndex,
            instruction = instructionFor(target, landmark),
            distanceToNextM = remaining.coerceAtLeast(0.0),
            arrowRad = arrow,
            nextTurn = target?.turn?.takeIf { target.type == NodeType.TURN },
            landmark = landmark?.takeIf { visible },
            uncertaintyM = uncertainty,
            progress01 = currentLeg?.lengthM?.takeIf { it > 0.0 }
                ?.let { (progress / it).coerceIn(0.0, 1.0) } ?: 0.0,
            fingerprintPrompt = fingerprintPrompt,
        )
    }

    private fun instructionFor(target: RouteNode?, landmark: Landmark?): String = when (phase) {
        NavPhase.ALIGN -> route.start.alignmentHint
        NavPhase.WALK -> when {
            target?.type == NodeType.LEVEL_CHANGE -> levelCueFor(target)
            target?.type == NodeType.DESTINATION && isLastLeg &&
                remaining <= config.navArrivePromptM -> "You're here — ${target.label}"
            else -> "Walk straight · ${ceil(remaining.coerceAtLeast(0.0)).toInt()} m"
        }
        NavPhase.APPROACH -> {
            val dir = turnWord(target?.turn)
            val place = landmark?.title ?: target?.label ?: "the turn"
            "In ${ceil(remaining.coerceAtLeast(0.0)).toInt()} m turn $dir at the $place"
        }
        NavPhase.TURNING -> "Turn ${turnWord(target?.turn)} now"
        NavPhase.CONFIRM -> landmark?.cue?.takeIf { it.isNotEmpty() }
            ?: target?.cue?.takeIf { it.isNotEmpty() }
            ?: "Confirm: ${target?.label ?: "landmark"}"
        NavPhase.UNCERTAIN -> uncertainInstruction ?: "Location uncertain — confirm landmark"
        NavPhase.OFF_ROUTE -> offRouteInstruction ?: offRouteCopy()
        NavPhase.ARRIVED -> "Arrived at ${target?.label ?: "destination"}"
    }

    private fun turnWord(dir: TurnDir?): String = when (dir) {
        TurnDir.LEFT -> "left"
        TurnDir.RIGHT -> "right"
        TurnDir.U_TURN -> "around"
        null -> "ahead"
    }

    /**
     * Level cue (W12-08): the stamped cue until the barometer confirms,
     * then the cue confirms itself ("Down 1 level ✓"). The only new copy
     * is the confirmation marker; the words come from the node's
     * [RouteNode.levelDelta] like [defaultCue].
     */
    private fun levelCueFor(node: RouteNode): String =
        if (node.id in confirmedLevelNodeIds) confirmedLevelCue(node)
        else node.cue.ifEmpty { defaultCue(node) }

    private fun confirmedLevelCue(node: RouteNode): String {
        val floors = abs(node.levelDelta)
        return when {
            floors == 0 -> "Change level ✓"
            node.levelDelta > 0 && floors == 1 -> "Up 1 level ✓"
            node.levelDelta > 0 -> "Up $floors levels ✓"
            floors == 1 -> "Down 1 level ✓"
            else -> "Down $floors levels ✓"
        }
    }
}
