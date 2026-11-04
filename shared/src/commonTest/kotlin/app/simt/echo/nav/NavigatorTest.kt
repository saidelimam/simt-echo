package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.model.NodeType
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W2-05 gate: the seven §6.4 scenarios, each replaying a synthetic capture
 * of `L_ROUTE` through [ReplayMotionSource] into [Navigator] — sensors
 * only, exactly as Simulate will drive it on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigatorTest {

    private class Run(val nav: Navigator, val events: List<NavEvent>)

    /**
     * Replays [log] into a fresh Navigator on `L_ROUTE`. [onUpdate] runs
     * after each motion input so scenarios can align, confirm landmarks,
     * or snapshot state mid-walk.
     *
     * W12-02: every scenario runs on both engines. LINEAR is today's
     * odometer path; GHOST drives the same §6.4 rules from the filter.
     */
    private fun TestScope.runReplay(
        log: CaptureLog,
        route: EchoRoute = Fixtures.L_ROUTE,
        engine: NavigatorEngine = NavigatorEngine.LINEAR,
        ghostSeed: Long = GhostFilter.DEFAULT_SEED,
        onUpdate: (Navigator, MotionUpdate) -> Unit = { _, _ -> },
    ): Run {
        val nav = Navigator(route, EchoConfig(navigatorEngine = engine), ghostSeed)
        val events = mutableListOf<NavEvent>()
        val eventJob = launch { nav.events.collect { events += it } }
        runCurrent()
        val source = ReplayMotionSource(log, this)
        val motionJob = launch {
            source.updates.collect { update ->
                nav.onInput(NavInput.Motion(update))
                onUpdate(nav, update)
            }
        }
        runCurrent()
        source.start()
        advanceUntilIdle()
        motionJob.cancel()
        eventJob.cancel()
        return Run(nav, events.toList())
    }

    /** Aligns on the first update, as the Align screen does. */
    private fun alignFirst(): (Navigator, MotionUpdate) -> Unit {
        var done = false
        return { nav, _ ->
            if (!done) {
                done = true
                nav.onInput(NavInput.Aligned)
            }
        }
    }

    /** Both engines under test (W12-02): LINEAR reference + GHOST filter. */
    private fun engines(): List<NavigatorEngine> = NavigatorEngine.entries

    @Test
    fun startsInAlignWithStartHint() {
        for (engine in engines()) {
            val nav = Navigator(Fixtures.L_ROUTE, EchoConfig(navigatorEngine = engine))
            assertEquals(NavPhase.ALIGN, nav.ui.value.phase, "engine $engine")
            assertEquals(Fixtures.L_ROUTE.start.alignmentHint, nav.ui.value.instruction, "engine $engine")
            assertTrue(nav.ui.value.offline, "engine $engine")
        }
    }

    @Test
    fun happyPathArrivesWithTwoAnchors() = runTest {
        for (engine in engines()) {
            val align = alignFirst()
            val run = runReplay(
                walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)),
                engine = engine,
            ) { nav, update ->
                align(nav, update)
                // "I'm here" once the last leg is within the arrival prompt.
                if (nav.ui.value.legIndex == 2 && nav.ui.value.distanceToNextM <= 2.0 &&
                    nav.ui.value.phase != NavPhase.ARRIVED
                ) {
                    nav.onInput(NavInput.ImHere)
                }
            }
            assertEquals(NavPhase.ARRIVED, run.nav.ui.value.phase, "engine $engine")
            val anchors = run.events.filterIsInstance<NavEvent.Anchor>()
            assertEquals(2, anchors.size, "engine $engine: expected one anchor per turn, got $anchors")
            assertEquals(
                listOf(AnchorKind.TURN, AnchorKind.TURN),
                anchors.map { it.kind },
                "engine $engine",
            )
            assertTrue(run.events.contains(NavEvent.Arrived), "engine $engine")
            assertTrue(
                run.events.filterIsInstance<NavEvent.Haptic>()
                    .any { it.pattern.name == "ARRIVE" },
                "engine $engine",
            )
        }
    }

    @Test
    fun wrongDirectionTurnGoesOffRoute() = runTest {
        for (engine in engines()) {
            val run = runReplay(
                walkLog(listOf(20.0 to 0.0, 8.0 to -90.0)),
                engine = engine,
                onUpdate = alignFirst(),
            )
            assertEquals(NavPhase.OFF_ROUTE, run.nav.ui.value.phase, "engine $engine")
            assertTrue(run.nav.ui.value.instruction.contains("Off route"), "engine $engine")
        }
    }

    @Test
    fun earlyTurnGoesUncertain() = runTest {
        for (engine in engines()) {
            val run = runReplay(
                walkLog(listOf(8.0 to 0.0, 10.0 to 90.0)),
                engine = engine,
                onUpdate = alignFirst(),
            )
            assertEquals(NavPhase.UNCERTAIN, run.nav.ui.value.phase, "engine $engine")
            assertTrue(
                run.nav.ui.value.instruction.contains("Turned early?"),
                "engine $engine: instruction was: ${run.nav.ui.value.instruction}",
            )
        }
    }

    @Test
    fun overshootThirtyPercentGoesUncertain() = runTest {
        for (engine in engines()) {
            // Leg 1 is 20 m; UNCERTAIN at 20 + max(5, 30% of 20) = 26 m.
            val run = runReplay(
                walkLog(listOf(27.0 to 0.0)),
                engine = engine,
                onUpdate = alignFirst(),
            )
            assertEquals(NavPhase.UNCERTAIN, run.nav.ui.value.phase, "engine $engine")
            assertTrue(run.nav.ui.value.instruction.contains("uncertain"), "engine $engine")
        }
    }

    @Test
    fun seeLandmarkSnapsProgress() = runTest {
        for (engine in engines()) {
            var snapshot: NavUi? = null
            var anchorsAtSnap = -1
            val events = mutableListOf<NavEvent>()
            var snapped = false
            val align = alignFirst()
            val run = runReplay(
                walkLog(listOf(20.0 to 0.0, 5.0 to 90.0)),
                engine = engine,
            ) { nav, update ->
                align(nav, update)
                if (!snapped && update.distanceM >= 12.0) {
                    snapped = true
                    nav.onInput(NavInput.SeeLandmark)
                    snapshot = nav.ui.value
                }
            }
            events += run.events
            anchorsAtSnap = events.filterIsInstance<NavEvent.Anchor>().count {
                it.kind == AnchorKind.LANDMARK
            }
            val snap = snapshot ?: error("engine $engine: SeeLandmark was never injected")
            assertEquals(NavPhase.TURNING, snap.phase, "engine $engine")
            if (engine == NavigatorEngine.LINEAR) {
                assertEquals(1.0, snap.progress01, 1e-9, "engine $engine")
                assertEquals(0.0, snap.distanceToNextM, 1e-9, "engine $engine")
            } else {
                // GHOST hard-anchors at the node ± 1 m (plan W12-02), so the
                // snap lands within the anchor window, not exactly on it.
                assertTrue(
                    abs(snap.progress01 - 1.0) <= 0.06,
                    "engine $engine: progress01 was ${snap.progress01}",
                )
                assertTrue(
                    snap.distanceToNextM <= 1.0 + 1e-9,
                    "engine $engine: distanceToNextM was ${snap.distanceToNextM}",
                )
            }
            assertEquals(1, anchorsAtSnap, "engine $engine")
        }
    }

    @Test
    fun stillWalkerNeverGoesUncertain() = runTest {
        for (engine in engines()) {
            val nav = Navigator(
                Fixtures.L_ROUTE,
                EchoConfig(navigatorEngine = engine, visualAssist = true),
            )
            nav.onInput(NavInput.Aligned)
            val initialRemaining = nav.ui.value.distanceToNextM
            repeat(60) { i ->
                nav.onInput(
                    NavInput.Motion(
                        MotionUpdate(
                            tNs = (i + 1) * 1_000_000_000L,
                            distanceM = 0.0,
                            yawRad = 0.0,
                            stepCount = 0,
                            isStep = false,
                            pressureHpa = null,
                            source = "test",
                            missedStepSuspect = true,
                        ),
                    ),
                )
                runCurrent()
            }
            assertEquals(NavPhase.WALK, nav.ui.value.phase, "engine $engine")
            assertTrue(
                abs(nav.ui.value.distanceToNextM - initialRemaining) < 0.5,
                "engine $engine must not invent progress",
            )
        }
    }

    @Test
    fun anchorClearsSuspectBonus() = runTest {
        for (engine in engines()) {
            val nav = Navigator(
                Fixtures.L_ROUTE,
                EchoConfig(navigatorEngine = engine, visualAssist = true),
            )
            nav.onInput(NavInput.Aligned)
            repeat(5) { i ->
                nav.onInput(
                    NavInput.Motion(
                        MotionUpdate(
                            tNs = (i + 1) * 1_000_000_000L,
                            distanceM = 0.0,
                            yawRad = 0.0,
                            stepCount = 0,
                            isStep = false,
                            pressureHpa = null,
                            source = "test",
                            missedStepSuspect = true,
                        ),
                    ),
                )
            }
            val inflated = nav.ui.value.uncertaintyM
            nav.onInput(
                NavInput.Motion(
                    MotionUpdate(
                        tNs = 6_000_000_000L,
                        distanceM = 12.0,
                        yawRad = 0.0,
                        stepCount = 10,
                        isStep = true,
                        pressureHpa = null,
                        source = "test",
                    ),
                ),
            )
            nav.onInput(NavInput.SeeLandmark)
            runCurrent()
            assertTrue(
                nav.ui.value.uncertaintyM < inflated,
                "engine $engine: anchor must clear the suspect bonus (${nav.ui.value.uncertaintyM} < $inflated)",
            )
        }
    }

    @Test
    fun wrongStartGoesOffRoute() = runTest {
        for (engine in engines()) {
            // Aligned facing 0°, then walks the first leg 70° off heading.
            val run = runReplay(
                walkLog(listOf(7.0 to 70.0), startYawDeg = 0.0),
                engine = engine,
                onUpdate = alignFirst(),
            )
            assertEquals(NavPhase.OFF_ROUTE, run.nav.ui.value.phase, "engine $engine")
            assertTrue(
                run.nav.ui.value.instruction.contains("doesn't match the start"),
                "engine $engine: instruction was: ${run.nav.ui.value.instruction}",
            )
        }
    }

    @Test
    fun corridorSnappingReducesOffsetWithin10m() = runTest {
        for (engine in engines()) {
            // Aligned at 0°, then walks straight with a steady 15° yaw offset.
            val run = runReplay(
                walkLog(listOf(10.0 to 15.0), startYawDeg = 0.0),
                engine = engine,
                onUpdate = alignFirst(),
            )
            assertEquals(NavPhase.WALK, run.nav.ui.value.phase, "engine $engine")
            assertTrue(
                abs(run.nav.ui.value.arrowRad) < deg(5.0),
                "engine $engine: arrow was ${run.nav.ui.value.arrowRad} rad, expected < 5°",
            )
        }
    }

    /**
     * W10-08: the L walk turns right then left — each approach cue speaks
     * the turn direction, each detected turn anchors (CONFIRM), then
     * ARRIVE. Exact sequence, no TICK: both nodes have a turn direction.
     */
    @Test
    fun turnCuesFireRightThenLeft() = runTest {
        for (engine in engines()) {
            val align = alignFirst()
            val run = runReplay(
                walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)),
                engine = engine,
            ) { nav, update ->
                align(nav, update)
                if (nav.ui.value.legIndex == 2 && nav.ui.value.distanceToNextM <= 2.0 &&
                    nav.ui.value.phase != NavPhase.ARRIVED
                ) {
                    nav.onInput(NavInput.ImHere)
                }
            }
            assertEquals(NavPhase.ARRIVED, run.nav.ui.value.phase, "engine $engine")
            assertEquals(
                listOf(
                    NavEvent.Haptic(HapticPattern.RIGHT),
                    NavEvent.Haptic(HapticPattern.CONFIRM),
                    NavEvent.Haptic(HapticPattern.LEFT),
                    NavEvent.Haptic(HapticPattern.CONFIRM),
                    NavEvent.Haptic(HapticPattern.ARRIVE),
                ),
                run.events.filterIsInstance<NavEvent.Haptic>(),
                "engine $engine",
            )
        }
    }

    /**
     * W10-08: confirming the turn node re-emits the approach cue on
     * entering TURNING. SeeLandmark lands past the 16 m approach line, so
     * the approach RIGHT fires first, then TURNING re-emits RIGHT.
     */
    @Test
    fun turningReEmitsApproachCue() = runTest {
        for (engine in engines()) {
            val align = alignFirst()
            var snapped = false
            val run = runReplay(
                walkLog(listOf(20.0 to 0.0)),
                engine = engine,
            ) { nav, update ->
                align(nav, update)
                if (!snapped && update.distanceM >= 17.0) {
                    snapped = true
                    nav.onInput(NavInput.SeeLandmark)
                }
            }
            assertTrue(snapped, "engine $engine: SeeLandmark was never injected")
            assertEquals(NavPhase.TURNING, run.nav.ui.value.phase, "engine $engine")
            assertEquals(
                listOf(
                    NavEvent.Haptic(HapticPattern.RIGHT),
                    NavEvent.Haptic(HapticPattern.RIGHT),
                ),
                run.events.filterIsInstance<NavEvent.Haptic>(),
                "engine $engine",
            )
        }
    }

    /**
     * W10-08: a U-turn node approaches with U_TURN, then overshooting the
     * 10 m leg (UNCERTAIN past 10 + max(5, 30%) = 15 m) warns. Exact
     * sequence over a 16 m straight walk.
     */
    @Test
    fun uTurnApproachEmitsUTurn() = runTest {
        for (engine in engines()) {
            val run = runReplay(
                walkLog(listOf(16.0 to 0.0)),
                engine = engine,
                onUpdate = alignFirst(),
                route = turnRoute(TurnDir.U_TURN),
            )
            assertEquals(NavPhase.UNCERTAIN, run.nav.ui.value.phase, "engine $engine")
            assertEquals(
                listOf(
                    NavEvent.Haptic(HapticPattern.U_TURN),
                    NavEvent.Haptic(HapticPattern.WARN),
                ),
                run.events.filterIsInstance<NavEvent.Haptic>(),
                "engine $engine",
            )
        }
    }

    /**
     * W10-08: a turn node with no direction is a straight checkpoint — its
     * approach keeps the neutral TICK. An 8 m walk of the 10 m leg enters
     * APPROACH (at 6 m) without overshooting, so TICK is the only haptic.
     */
    @Test
    fun turnWithoutDirectionKeepsTick() = runTest {
        for (engine in engines()) {
            val run = runReplay(
                walkLog(listOf(8.0 to 0.0)),
                engine = engine,
                onUpdate = alignFirst(),
                route = turnRoute(turn = null),
            )
            assertEquals(NavPhase.APPROACH, run.nav.ui.value.phase, "engine $engine")
            assertEquals(
                listOf(NavEvent.Haptic(HapticPattern.TICK)),
                run.events.filterIsInstance<NavEvent.Haptic>(),
                "engine $engine",
            )
        }
    }

    /**
     * W12-02: odometer reads 12 % short (stride scale 0.88). Turn anchors
     * let the filter's k distribution self-calibrate, so GHOST still
     * arrives with the true position within 2 m of the destination. The
     * LINEAR outcome is recorded, not asserted — its leg-reset anchors
     * mask most of the error and it lands early.
     */
    @Test
    fun strideTwelvePercentOffGhostArrivesWithin2mLinearReported() = runTest {
        val scale = 0.88
        val truth = walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0))
        val biased = truth.copy(samples = truth.samples.map { it.copy(d = it.d * scale) })

        var ghostTruthAtArrival = -1.0
        val ghostAlign = alignFirst()
        val ghost = runReplay(biased, engine = NavigatorEngine.GHOST) { nav, update ->
            ghostAlign(nav, update)
            if (nav.ui.value.legIndex == 2 && nav.ui.value.distanceToNextM <= 2.0 &&
                nav.ui.value.phase != NavPhase.ARRIVED
            ) {
                ghostTruthAtArrival = update.distanceM / scale
                nav.onInput(NavInput.ImHere)
            }
        }
        assertEquals(NavPhase.ARRIVED, ghost.nav.ui.value.phase, "GHOST should arrive")
        assertTrue(
            ghostTruthAtArrival >= 0.0,
            "GHOST never entered the arrival prompt",
        )
        assertTrue(
            Fixtures.L_ROUTE.totalLengthM - ghostTruthAtArrival <= 2.0,
            "GHOST arrived at truth $ghostTruthAtArrival m, expected within 2 m of 45 m",
        )

        var linearTruthAtArrival = -1.0
        val linearAlign = alignFirst()
        val linear = runReplay(biased, engine = NavigatorEngine.LINEAR) { nav, update ->
            linearAlign(nav, update)
            if (nav.ui.value.legIndex == 2 && nav.ui.value.distanceToNextM <= 2.0 &&
                nav.ui.value.phase != NavPhase.ARRIVED
            ) {
                linearTruthAtArrival = update.distanceM / scale
                nav.onInput(NavInput.ImHere)
            }
        }
        println(
            "W12-02 stride-off x$scale: GHOST arrived at truth $ghostTruthAtArrival m " +
                "(σ=${ghost.nav.ui.value.uncertaintyM}); LINEAR phase=${linear.nav.ui.value.phase} " +
                "arrival truth=$linearTruthAtArrival m (σ=${linear.nav.ui.value.uncertaintyM})",
        )
    }

    /**
     * W12-02: the same seed and the same replay produce an identical
     * s/σ trace on GHOST — seizure-free A/B and field replay depend on it.
     */
    @Test
    fun ghostSeededDeterminismIdenticalTrace() = runTest {
        fun trace(): List<Pair<Double, Double>> {
            val points = mutableListOf<Pair<Double, Double>>()
            val align = alignFirst()
            runReplay(
                walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)),
                engine = NavigatorEngine.GHOST,
                ghostSeed = 1234L,
            ) { nav, update ->
                align(nav, update)
                val ui = nav.ui.value
                points += ui.progress01 to ui.uncertaintyM
            }
            return points
        }
        assertEquals(trace(), trace())
        assertTrue(trace().isNotEmpty())
    }

    /**
     * W12-08: climbing 3 m with steps confirms the LEVEL_CHANGE target on
     * both engines — a LEVEL anchor and CONFIRM haptic each. Starts from
     * a blank stamped cue so the [defaultCue] fallback path is covered
     * too. The engines consume the confirm differently: LINEAR keeps
     * walking the leg, so the cue keeps its ✓; GHOST anchors at the
     * leg-end node, which arrives the leg (like a landmark confirm).
     */
    @Test
    fun stairsUpConfirmsLevelOnBothEngines() = runTest {
        for (engine in engines()) {
            val seen = mutableListOf<String>()
            val pressures = levelPressures(toM = 3.0, rampSamples = 16)
            val (nav, events) = runDirect(levelRoute(levelDelta = 1), engine) { nav ->
                nav.onInput(NavInput.Aligned)
                feedLevelWalk(
                    nav,
                    pressures = pressures,
                    steps = List(pressures.size) { it },
                    distStepM = 0.1,
                    seen = seen,
                )
            }
            assertTrue(
                events.filterIsInstance<NavEvent.Anchor>()
                    .any { it.kind == AnchorKind.LEVEL && it.nodeId == "n1" },
                "engine $engine: no LEVEL anchor in $events",
            )
            assertTrue(
                NavEvent.Haptic(HapticPattern.CONFIRM) in events,
                "engine $engine: no CONFIRM haptic in $events",
            )
            if (engine == NavigatorEngine.LINEAR) {
                assertTrue(
                    "Up 1 level ✓" in seen,
                    "engine $engine: never confirmed, saw $seen",
                )
            } else {
                assertEquals(1, nav.ui.value.legIndex, "engine $engine")
            }
            assertEquals(NavPhase.WALK, nav.ui.value.phase, "engine $engine")
        }
    }

    /**
     * W12-08: a stepless 3 m descent reads as a lift and still confirms —
     * "Down 1 level ✓" with a LEVEL anchor on both engines. Step count
     * stays flat while distance barely advances, as in a lift car.
     *
     * Note: on GHOST the anchor mean lands just short of the leg end, so
     * the leg does not auto-arrive and the ✓ stays rendered (the stairs
     * test covers the arriving side). Both outcomes are valid
     * consumptions of the same observation.
     */
    @Test
    fun liftDownConfirmsLevelOnBothEngines() = runTest {
        for (engine in engines()) {
            val seen = mutableListOf<String>()
            val pressures = levelPressures(toM = -3.0, rampSamples = 12)
            val (nav, events) = runDirect(levelRoute(levelDelta = -1), engine) { nav ->
                nav.onInput(NavInput.Aligned)
                feedLevelWalk(
                    nav,
                    pressures = pressures,
                    steps = List(pressures.size) { 0 },
                    distStepM = 0.1,
                    seen = seen,
                )
            }
            assertTrue(
                "Down 1 level ✓" in seen,
                "engine $engine: never confirmed, saw $seen",
            )
            assertTrue(
                events.filterIsInstance<NavEvent.Anchor>()
                    .any { it.kind == AnchorKind.LEVEL && it.nodeId == "n1" },
                "engine $engine: no LEVEL anchor in $events",
            )
            assertTrue(
                NavEvent.Haptic(HapticPattern.CONFIRM) in events,
                "engine $engine: no CONFIRM haptic in $events",
            )
            assertEquals(NavPhase.WALK, nav.ui.value.phase, "engine $engine")
        }
    }

    /**
     * Drives [nav] with hand-built motion updates (direct, not replayed)
     * so pressure and step count are controlled independently — replay
     * derives steps from sample indices and cannot express a lift.
     * Records every rendered instruction in [seen].
     */
    private fun feedLevelWalk(
        nav: Navigator,
        pressures: List<Double>,
        steps: List<Int>,
        distStepM: Double,
        seen: MutableList<String>,
    ) {
        require(pressures.size == steps.size)
        var t = TEST_T0_NS
        var d = 0.0
        pressures.forEachIndexed { i, p ->
            nav.onInput(
                NavInput.Motion(
                    MotionUpdate(
                        tNs = t,
                        distanceM = d,
                        yawRad = 0.0,
                        stepCount = steps[i],
                        isStep = i > 0 && steps[i] > steps[i - 1],
                        pressureHpa = p,
                        source = "test",
                    ),
                ),
            )
            seen += nav.ui.value.instruction
            d += distStepM
            t += TEST_TICK_NS
        }
    }

    /** Collects events around a direct (non-replay) feed, like [runReplay]. */
    private fun TestScope.runDirect(
        route: EchoRoute,
        engine: NavigatorEngine,
        feed: (Navigator) -> Unit,
    ): Pair<Navigator, List<NavEvent>> {
        val nav = Navigator(route, EchoConfig(navigatorEngine = engine))
        val events = mutableListOf<NavEvent>()
        val eventJob = launch { nav.events.collect { events += it } }
        runCurrent()
        feed(nav)
        runCurrent()
        eventJob.cancel()
        return nav to events.toList()
    }

    /**
     * A level change as the barometer sees it: a quiet baseline (the
     * leg-start median), the ramp to [toM], then a 1.5 s+ dwell at the new
     * level so the crossing holds long enough to count (50 ms ticks).
     */
    private fun levelPressures(toM: Double, rampSamples: Int): List<Double> =
        List(5) { pressureForDh(1013.25, 0.0) } +
            rampDh(1013.25, 0.0, toM, rampSamples) +
            List(35) { pressureForDh(1013.25, toM) }

    /** Pressures ramping from [fromM] to [toM] above datum [p0]. */
    private fun rampDh(p0: Double, fromM: Double, toM: Double, samples: Int): List<Double> =
        List(samples) { i ->
            pressureForDh(p0, fromM + (toM - fromM) * i / (samples - 1))
        }

    /** Two-leg straight route whose middle node changes level by [levelDelta]. */
    private fun levelRoute(levelDelta: Int): EchoRoute = EchoRoute(
        routeId = "level-route",
        name = "Level route",
        createdAtEpochMs = 0L,
        creatorLabel = "test",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 20.0,
        start = StartAnchor(
            description = "Start",
            photoAssetId = null,
            alignmentHint = "Face forward",
        ),
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Start"),
            // Blank cue: the instruction falls back to defaultCue until
            // the barometer confirms, then appends the ✓ marker.
            RouteNode(
                id = "n1",
                type = NodeType.LEVEL_CHANGE,
                atDistanceM = 10.0,
                label = "Stairs",
                levelDelta = levelDelta,
            ),
            RouteNode(id = "n2", type = NodeType.DESTINATION, atDistanceM = 20.0, label = "Lobby"),
        ),
        legs = listOf(
            RouteLeg(fromNode = "n0", toNode = "n1", lengthM = 10.0, headingRelDeg = 0.0),
            RouteLeg(fromNode = "n1", toNode = "n2", lengthM = 10.0, headingRelDeg = 0.0),
        ),
        path = emptyList(),
    )

    /** Two-leg straight route whose middle node turns [turn] (or not). */
    private fun turnRoute(turn: TurnDir?): EchoRoute = EchoRoute(
        routeId = "turn-cue-route",
        name = "Turn cue route",
        createdAtEpochMs = 0L,
        creatorLabel = "test",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 20.0,
        start = StartAnchor(
            description = "Start",
            photoAssetId = null,
            alignmentHint = "Face forward",
        ),
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Start"),
            RouteNode(id = "n1", type = NodeType.TURN, atDistanceM = 10.0, label = "Turn", turn = turn),
            RouteNode(id = "n2", type = NodeType.DESTINATION, atDistanceM = 20.0, label = "End"),
        ),
        legs = listOf(
            RouteLeg(fromNode = "n0", toNode = "n1", lengthM = 10.0, headingRelDeg = 0.0),
            RouteLeg(fromNode = "n1", toNode = "n2", lengthM = 10.0, headingRelDeg = 0.0),
        ),
        path = emptyList(),
    )
}
