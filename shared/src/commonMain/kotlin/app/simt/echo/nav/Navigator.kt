package app.simt.echo.nav

import app.simt.echo.capture.circularMean
import app.simt.echo.capture.wrapPi
import app.simt.echo.capture.MagProfiler
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.Landmark
import app.simt.echo.model.MagProfile
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.TurnDir
import app.simt.echo.model.defaultCue
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.HitDebounce
import app.simt.echo.nav.ghost.FingerprintHit
import app.simt.echo.nav.ghost.FixGate
import app.simt.echo.nav.ghost.FingerprintPrompt
import app.simt.echo.nav.ghost.MagneticMatch
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

    /** Raw odometer at the last Ghost heading observation (see [HEADING_OBS_STEP_M]). */
    private var lastHeadingObsOdo: Double = 0.0

    /** Heading observations the Ghost filter has applied; 0 on LINEAR. */
    internal val ghostHeadingObservations: Int get() = ghost?.headingObservationCount ?: 0

    /**
     * Raw odometer value where the current leg began. The overshoot guard
     * below reads this on both engines: overshoot compares metres actually
     * walked against the leg length, independent of the position belief
     * (on LINEAR it always equals [legStartDist]).
     */
    private var legStartOdo: Double = 0.0

    /**
     * Whether `dist - legStartOdo` is a whole-leg odometer reading the
     * stride learner may fold in. False after a relock (the origin is the
     * found position mid-leg) and after an observation (an anchored leg
     * is counted once, not again when its turn is detected); true again
     * at every real leg start.
     */
    private var legOdoValid: Boolean = true

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

    /** Slowly-varying camera-to-travel offset from visual FOE (rad, W16-03). */
    var travelOffsetRad: Double = 0.0
        private set

    /** Extra σ from flagged `MISSED_STEP_SUSPECT` events on LINEAR (m, W16-04). */
    private var missedStepSigmaBonusM: Double = 0.0

    /** Count of `MISSED_STEP_SUSPECT` events observed during this walk (W16-04). */
    var missedStepSuspectCount: Int = 0
        private set

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
    private val hitDebounce = HitDebounce()

    /** Timestamp of the last motion update: the clock for [hitDebounce]. */
    private var lastMotionTNs: Long = 0L

    /**
     * Lost → relock search (W12-06): odo + clock where the search began,
     * the (dist, yaw, tNs) trail feeding the turn-signature DTW (kept to
     * the ≤ 10 s / 8 m window), and the 1 m bucket of the last signature
     * observation so each metre feeds the filter at most once.
     */
    private var relockStartOdo: Double = 0.0
    private val relockTrail = mutableListOf<RelockSample>()
    private var lastSignatureBucket: Int = -1

    /**
     * Transient "Found you" cue after a relock: the nearest landmark
     * title (null when the route has none — the copy falls back to
     * "back on route"), shown on WALK until the walker moves on.
     * [foundAtS] finite means a lock is being announced.
     */
    private var foundNearLabel: String? = null
    private var foundAtS: Double = Double.NaN

    /**
     * Live level awareness (W12-08): relative-pressure Δh from the
     * leg-start baseline. Reset on every leg transition; confirmed node
     * ids persist for the route so the cue keeps its ✓.
     */
    private val levelAwareness = LevelAwareness(config.levelThresholdM)
    private val confirmedLevelNodeIds = mutableSetOf<String>()

    /** Recent (distance, raw yaw) for corridor snapping. */
    private val yawTrail = mutableListOf<Pair<Double, Double>>()

    /**
     * Live magnetic signature (W12-04): the last 10 m observed, bucketed
     * from motion updates carrying magnetometer data. Matched against the
     * route's stored mag profile at most once per 0.5 m bucket; silent
     * when the route has no profile or the device has no magnetometer.
     */
    private val magProfiler = MagProfiler()
    private var lastMagMatchBucket: Int = -1
    private val magFixGate = FixGate()

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
        get() = (ghost?.sigma
            ?: (config.navUncertaintyBaseM + config.navUncertaintyPerMetre * (dist - lastAnchorDist))) +
            missedStepSigmaBonusM
    private val arrow: Double
        get() {
            val offset = if (config.visualAssist) yawOffset + travelOffsetRad else yawOffset
            return leg?.let { wrapPi(degToRad(it.headingRelDeg) - (yaw + offset)) } ?: 0.0
        }
    private val isLastLeg: Boolean get() = legIndex >= route.legs.lastIndex

    private fun landmarkFor(node: RouteNode?): Landmark? =
        node?.let { n -> route.landmarks.firstOrNull { it.nodeId == n.id } }

    private fun onAligned() {
        if (phase != NavPhase.ALIGN) return
        legStartDist = dist
        lastAnchorDist = dist
        legStartOdo = dist
        legOdoValid = true
        lastAnchorLegIndex = 0
        yawOffset = wrapPi(degToRad(leg?.headingRelDeg ?: 0.0) - yaw)
        travelOffsetRad = 0.0
        missedStepSigmaBonusM = 0.0
        missedStepSuspectCount = 0
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
        lastMotionTNs = update.tNs
        if (config.visualAssist && update.travelOffsetRad.isFinite() && update.travelOffsetRad != 0.0) {
            travelOffsetRad = update.travelOffsetRad
        }
        if (config.visualAssist && update.missedStepSuspect) {
            missedStepSuspectCount += 1
            val inflation = app.simt.echo.capture.VisualStepGate.MISSED_STEP_SIGMA_INFLATION_M
            if (ghost != null) {
                ghost.inflateUncertainty(inflation)
            } else {
                missedStepSigmaBonusM += inflation
            }
        }
        if (phase != NavPhase.WALK && phase != NavPhase.APPROACH &&
            phase != NavPhase.TURNING && phase != NavPhase.CONFIRM &&
            phase != NavPhase.RELOCKING
        ) {
            return
        }
        legYaws += update.yawRad
        ghost?.let {
            val delta = dist - prevOdoM
            prevOdoM = dist
            if (delta > 0.0) it.predict(delta)
            // The heading likelihood is applied about once per step, in
            // step with predict. Motion updates arrive at ~100 Hz; folding
            // each in would count the same heading ~50x per stride and
            // resample on every one.
            if (dist - lastHeadingObsOdo >= HEADING_OBS_STEP_M) {
                lastHeadingObsOdo = dist
                val alignedYaw = if (config.visualAssist) yaw + yawOffset + travelOffsetRad else yaw + yawOffset
                it.observe(Observation.Heading(alignedYaw, update.headingQuality))
            }
        }
        if (foundAtS.isFinite() && absoluteS - foundAtS >= FOUND_COPY_EXPIRE_M) {
            foundNearLabel = null
            foundAtS = Double.NaN
        }
        applyCorridorSnapping(update)
        levelAwareness.onSample(update.pressureHpa, update.stepCount, update.tNs)?.let { onLevelEvent(it) }
        onMagneticUpdate(update)
        val detected = turnDetector.onSample(update.tNs, update.yawRad)
        if (detected != null) {
            onTurnDetected(detected)
            return
        }
        if (isWrongStart()) {
            enterOffRoute("This doesn't match the start — re-align")
            return
        }
        if (uncertainty > config.navUncertaintyMaxM && phase != NavPhase.TURNING &&
            phase != NavPhase.RELOCKING
        ) {
            enterUncertain(null)
            return
        }
        when (phase) {
            NavPhase.WALK, NavPhase.APPROACH -> onWalkMotion()
            NavPhase.CONFIRM -> {
                if (absoluteS - confirmEntryDist >= config.navConfirmAutoAdvanceM) advanceLeg(null)
            }
            NavPhase.TURNING -> if (overshot()) enterUncertain(null)
            NavPhase.RELOCKING -> onRelockMotion(update)
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
        if (phase == NavPhase.RELOCKING) {
            // Position unknown: a turn is evidence, not progress — it feeds
            // the re-spread cloud (single-turn likelihood plus the yaw
            // trail the signature DTW reads) and may complete the lock.
            // It never advances a leg off a stale target.
            ghost?.observe(Observation.Turn(detected.dir))
            checkRelockConvergence()
            return
        }
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
        if (phase == NavPhase.ALIGN || phase == NavPhase.ARRIVED || phase == NavPhase.RELOCKING) return
        val target = targetNode ?: return
        val currentLeg = leg ?: return
        learnStride(currentLeg)
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
     *
     * Hits repeat for as long as the camera sees the landmark, so they are
     * debounced per candidate ([HitDebounce]: ≥ 3 s and ≥ 2 m apart) — one
     * reweight per window, not one per frame — and the TICK fires once per
     * prompt, not once per hit.
     */
    /**
     * Visual ego-motion feed (W16-03): updates the slowly-varying FOE
     * travel-direction offset when [EchoConfig.visualAssist] is enabled and
     * the frame clears the confidence gate. Confidence 0 is a no-op.
     */
    fun onVisualEgo(ego: app.simt.echo.capture.visual.VisualEgo) {
        if (!config.visualAssist || config.baselineMode) return
        if (!ego.confidence01.isFinite() || ego.confidence01 < 0.5 || !ego.hasFoe) return
        val offset = ego.travelOffsetRad
        if (!offset.isFinite()) return
        val clamped = offset.coerceIn(-MAX_TRAVEL_OFFSET_RAD, MAX_TRAVEL_OFFSET_RAD)
        travelOffsetRad = if (travelOffsetRad == 0.0) {
            clamped
        } else {
            val alpha = (0.2 * ego.confidence01).coerceIn(0.02, 0.35)
            wrapPi(travelOffsetRad + alpha * wrapPi(clamped - travelOffsetRad))
                .coerceIn(-MAX_TRAVEL_OFFSET_RAD, MAX_TRAVEL_OFFSET_RAD)
        }
        refresh()
    }

    fun onFingerprintHit(hit: FingerprintHit) {
        if (phase == NavPhase.ALIGN || phase == NavPhase.ARRIVED) return
        if (!hitDebounce.accept(hit.nodeS, lastMotionTNs, dist)) return
        ghost?.observe(Observation.Fingerprint(hit.nodeS, hit.similarity01))
        val prompt = FingerprintPrompt(hit.label, hit.nodeS)
        if (prompt != fingerprintPrompt) {
            fingerprintPrompt = prompt
            emit(NavEvent.Haptic(HapticPattern.TICK))
        }
        if (phase == NavPhase.RELOCKING) checkRelockConvergence()
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
        if (phase == NavPhase.RELOCKING) {
            // An explicit user confirm is ground truth: it completes the
            // search at the suggested position even before σ converges.
            finalizeRelock(prompt.nodeS)
            refresh()
            return
        }
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

    /**
     * Magnetic-signature fix (W12-04): feeds the live mag samples into the
     * profiler and, at most once per 0.5 m bucket, matches the last 10 m
     * against the route's stored mag profile around the Ghost belief. A
     * passing match is a soft filter observation — never a phase change,
     * never an anchor event — and only when [magFixGate] finds it new
     * information (consecutive buckets re-match ~95 % the same window).
     * Silent on LINEAR (no filter), without a route profile, or without
     * magnetometer data.
     */
    private fun onMagneticUpdate(update: MotionUpdate) {
        val g = ghost ?: return
        val mag = update.magMag ?: return
        val vert = update.magVert ?: return
        val profile = route.perception?.magProfile ?: return
        magProfiler.onSample(dist, mag, vert)
        val bucket = (dist / MagProfile.MAG_STEP_M).toInt()
        if (bucket == lastMagMatchBucket) return
        lastMagMatchBucket = bucket
        val (obsMag, obsVert) = magProfiler.lastWindow(MagneticMatch.QUERY_M, dist) ?: return
        MagneticMatch.match(profile, obsMag, obsVert, g.meanS, g.sigma)?.let {
            if (magFixGate.accept(dist, it.estimatedS)) g.observe(Observation.Magnetic(it.estimatedS))
        }
    }

    private fun onNotYet() {
        if (phase != NavPhase.CONFIRM) return
        // Dismissed, not confirmed: keep walking this leg; auto-advance only
        // after the CONFIRM grace distance measured from leg progress.
        confirmAutoAdvanceProgress = progress + config.navConfirmAutoAdvanceM
        setPhase(NavPhase.WALK)
    }

    private fun onLost() {
        if (phase == NavPhase.ALIGN || phase == NavPhase.ARRIVED || phase == NavPhase.RELOCKING) return
        val g = ghost
        if (g == null) {
            // LINEAR has no particles to re-spread: today's sheets,
            // honestly and immediately.
            enterOffRoute(offRouteCopy())
            return
        }
        fingerprintPrompt = null
        g.respreadUniform()
        relockStartOdo = dist
        relockTrail.clear()
        lastSignatureBucket = (dist / RELOCK_SIGNATURE_STEP_M).toInt()
        emit(NavEvent.Haptic(HapticPattern.WARN))
        setPhase(NavPhase.RELOCKING)
    }

    private fun onImHere() {
        if (phase == NavPhase.ARRIVED || phase == NavPhase.RELOCKING || !isLastLeg) return
        if (targetNode?.type == NodeType.DESTINATION && remaining <= config.navArrivePromptM) {
            arrive()
        }
    }

    /**
     * One relock motion update (W12-06): extends the signature trail,
     * feeds the DTW observation at most once per metre walked, checks for
     * a σ < 3 m lock, and falls back to today's sheets after 20 m
     * without convergence — never a fake lock.
     */
    private fun onRelockMotion(update: MotionUpdate) {
        val g = ghost ?: run {
            clearRelockTrail()
            enterOffRoute(offRouteCopy())
            return
        }
        relockTrail += RelockSample(update.distanceM, update.yawRad, update.tNs)
        relockTrail.removeAll {
            update.distanceM - it.d > RELOCK_WINDOW_M || update.tNs - it.tNs > RELOCK_WINDOW_NS
        }
        val bucket = (update.distanceM / RELOCK_SIGNATURE_STEP_M).toInt()
        if (bucket != lastSignatureBucket) {
            lastSignatureBucket = bucket
            buildRelockSignature(update.distanceM)?.let { g.observe(Observation.TurnSignature(it, RELOCK_SIGNATURE_STEP_M)) }
        }
        if (checkRelockConvergence()) return
        if (update.distanceM - relockStartOdo >= RELOCK_FALLBACK_M) {
            clearRelockTrail()
            enterOffRoute(offRouteCopy())
        }
    }

    /**
     * Observed heading-change sequence ending at the walker's current
     * position: yaw deltas per [RELOCK_SIGNATURE_STEP_M] over the last up
     * to 8 whole metres walked since the search began (a sliding window,
     * never anchored to where the search started). Null until at least one
     * full metre is walked. Rotation-invariant (yaw deltas), so the
     * unknown alignment offset cancels.
     */
    private fun buildRelockSignature(distM: Double): DoubleArray? {
        val walked = distM - relockStartOdo
        val steps = walked.toInt().coerceAtMost(GhostFilter.MAX_SIGNATURE_STEPS)
        if (steps < 1) return null
        val base = distM - steps * RELOCK_SIGNATURE_STEP_M
        return DoubleArray(steps) { k ->
            wrapPi(yawAt(base + (k + 1) * RELOCK_SIGNATURE_STEP_M) - yawAt(base + k * RELOCK_SIGNATURE_STEP_M))
        }
    }

    /** Yaw of the nearest relock-trail sample to along-walk [d]. */
    private fun yawAt(d: Double): Double {
        var best = relockTrail.lastOrNull()?.yaw ?: yaw
        var bestDist = Double.MAX_VALUE
        for (sample in relockTrail) {
            val dd = abs(sample.d - d)
            if (dd < bestDist) {
                bestDist = dd
                best = sample.yaw
            }
        }
        return best
    }

    /** σ < 3 m completes the search; anything else keeps searching. */
    private fun checkRelockConvergence(): Boolean {
        val g = ghost ?: return false
        if (!g.sigma.isFinite() || g.sigma >= RELOCK_SIGMA_M) return false
        val s = g.meanS
        // A posterior crowded against either route end is indistinguishable
        // from clamp pile-up after a uniform re-spread (predict pins
        // overshooting particles at the boundary, so σ collapses without
        // evidence). Keep searching rather than announce a lock the
        // evidence cannot support; the 20 m fallback stays honest.
        if (s < RELOCK_EDGE_M || s > geometry.totalLengthM - RELOCK_EDGE_M) return false
        finalizeRelock(s)
        return true
    }

    /**
     * Completes the search at along-route [s]: the leg re-derives from
     * the found position, the heading re-aligns to it, and WALK resumes
     * with a transient "Found you" cue naming the nearest landmark.
     */
    private fun finalizeRelock(s: Double) {
        val clamped = s.coerceIn(0.0, geometry.totalLengthM)
        legIndex = geometry.legIndexAt(clamped)
        legStartDist = geometry.legStartS(legIndex)
        lastAnchorDist = clamped
        legStartOdo = dist
        legOdoValid = false
        lastAnchorLegIndex = legIndex
        yawOffset = wrapPi(geometry.headingAt(clamped) - yaw)
        tickedLegIndex = -1
        confirmAutoAdvanceProgress = null
        foundNearLabel = nearestLandmarkTitle(clamped)
        foundAtS = clamped
        fingerprintPrompt = null
        clearRelockTrail()
        turnDetector.reset()
        yawTrail.clear()
        legYaws.clear()
        levelAwareness.resetLeg()
        emit(NavEvent.Haptic(HapticPattern.CONFIRM))
        setPhase(NavPhase.WALK)
    }

    /** Title of the landmark nearest to along-route [s], if the route has any. */
    private fun nearestLandmarkTitle(s: Double): String? {
        var best: String? = null
        var bestDist = Double.MAX_VALUE
        for (landmark in route.landmarks) {
            val nodeS = geometry.nodeS(landmark.nodeId) ?: continue
            val d = abs(nodeS - s)
            if (d < bestDist) {
                bestDist = d
                best = landmark.title
            }
        }
        return best
    }

    private fun clearRelockTrail() {
        relockTrail.clear()
        lastSignatureBucket = -1
    }

    private fun restart(toLegIndex: Int) {
        if (phase != NavPhase.OFF_ROUTE && phase != NavPhase.UNCERTAIN) return
        foundNearLabel = null
        foundAtS = Double.NaN
        legIndex = toLegIndex.coerceIn(0, route.legs.lastIndex)
        legStartDist = dist
        lastAnchorDist = dist
        legStartOdo = dist
        legOdoValid = true
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

    /** Folds the finished [leg] into the stride posterior, once per leg. */
    private fun learnStride(leg: RouteLeg) {
        if (legOdoValid) strideLearner.observe(leg.lengthM, dist - legStartOdo)
        legOdoValid = false
    }

    private fun advanceLeg(anchorKind: AnchorKind?) {
        val target = targetNode
        if (anchorKind != null && target != null) {
            leg?.let { learnStride(it) }
            emit(NavEvent.Anchor(anchorKind, target.id, absoluteS))
            emit(NavEvent.Haptic(HapticPattern.CONFIRM))
        }
        if (isLastLeg) {
            arrive()
            return
        }
        foundNearLabel = null
        foundAtS = Double.NaN
        legIndex += 1
        // A suggestion belongs to the leg it fired on; never carry it over.
        fingerprintPrompt = null
        legStartDist = dist
        lastAnchorDist = dist
        legStartOdo = dist
        legOdoValid = true
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
        if (to != NavPhase.WALK) {
            foundNearLabel = null
            foundAtS = Double.NaN
        }
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
        NavPhase.RELOCKING -> "Finding you…"
        NavPhase.WALK -> if (foundAtS.isFinite()) {
            foundNearLabel?.let { "Found you — near $it" } ?: "Found you — back on route"
        } else when {
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

    /**
     * One relock-trail sample (W12-06): where the walker was, which way
     * they faced, and when — the signature window reads yaw deltas off
     * these, pruned to ≤ 10 s / 8 m.
     */
    private data class RelockSample(val d: Double, val yaw: Double, val tNs: Long)

    companion object {
        /**
         * Odometer metres between Ghost heading observations: about one
         * step, so the likelihood is applied once per predict-sized stride
         * instead of once per ~100 Hz motion update.
         */
        const val HEADING_OBS_STEP_M = 0.5

        /** Relock lock criterion: σ below 3 m announces "Found you". */
        const val RELOCK_SIGMA_M = 3.0

        /**
         * Relock end margin: a lock crowded within 3 m of either route end
         * reads as clamp pile-up, not evidence — the search continues.
         */
        const val RELOCK_EDGE_M = 3.0

        /** Relock gives up after 20 m walked without convergence. */
        const val RELOCK_FALLBACK_M = 20.0

        /** Turn-signature window: the next ≤ 8 m of heading changes. */
        const val RELOCK_WINDOW_M = 8.0

        /** Turn-signature window: the next ≤ 10 s of heading changes, ns. */
        const val RELOCK_WINDOW_NS = 10_000_000_000L

        /** Turn-signature resolution: one heading change per metre. */
        const val RELOCK_SIGNATURE_STEP_M = 1.0

        /** "Found you" cue expires after 5 m of further walking. */
        const val FOUND_COPY_EXPIRE_M = 5.0

        private val MAX_TRAVEL_OFFSET_RAD = 30.0 * kotlin.math.PI / 180.0
    }
}
