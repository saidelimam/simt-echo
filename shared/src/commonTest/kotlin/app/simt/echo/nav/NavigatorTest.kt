package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
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
     */
    private fun TestScope.runReplay(
        log: CaptureLog,
        route: EchoRoute = Fixtures.L_ROUTE,
        onUpdate: (Navigator, MotionUpdate) -> Unit = { _, _ -> },
    ): Run {
        val nav = Navigator(route)
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

    @Test
    fun startsInAlignWithStartHint() {
        val nav = Navigator(Fixtures.L_ROUTE)
        assertEquals(NavPhase.ALIGN, nav.ui.value.phase)
        assertEquals(Fixtures.L_ROUTE.start.alignmentHint, nav.ui.value.instruction)
        assertTrue(nav.ui.value.offline)
    }

    @Test
    fun happyPathArrivesWithTwoAnchors() = runTest {
        val align = alignFirst()
        val run = runReplay(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0))) { nav, update ->
            align(nav, update)
            // "I'm here" once the last leg is within the arrival prompt.
            if (nav.ui.value.legIndex == 2 && nav.ui.value.distanceToNextM <= 2.0 &&
                nav.ui.value.phase != NavPhase.ARRIVED
            ) {
                nav.onInput(NavInput.ImHere)
            }
        }
        assertEquals(NavPhase.ARRIVED, run.nav.ui.value.phase)
        val anchors = run.events.filterIsInstance<NavEvent.Anchor>()
        assertEquals(2, anchors.size, "expected one anchor per turn, got $anchors")
        assertEquals(
            listOf(AnchorKind.TURN, AnchorKind.TURN),
            anchors.map { it.kind },
        )
        assertTrue(run.events.contains(NavEvent.Arrived))
        assertTrue(
            run.events.filterIsInstance<NavEvent.Haptic>()
                .any { it.pattern.name == "ARRIVE" },
        )
    }

    @Test
    fun wrongDirectionTurnGoesOffRoute() = runTest {
        val run = runReplay(
            walkLog(listOf(20.0 to 0.0, 8.0 to -90.0)),
            onUpdate = alignFirst(),
        )
        assertEquals(NavPhase.OFF_ROUTE, run.nav.ui.value.phase)
        assertTrue(run.nav.ui.value.instruction.contains("Off route"))
    }

    @Test
    fun earlyTurnGoesUncertain() = runTest {
        val run = runReplay(
            walkLog(listOf(8.0 to 0.0, 10.0 to 90.0)),
            onUpdate = alignFirst(),
        )
        assertEquals(NavPhase.UNCERTAIN, run.nav.ui.value.phase)
        assertTrue(
            run.nav.ui.value.instruction.contains("Turned early?"),
            "instruction was: ${run.nav.ui.value.instruction}",
        )
    }

    @Test
    fun overshootThirtyPercentGoesUncertain() = runTest {
        // Leg 1 is 20 m; UNCERTAIN at 20 + max(5, 30% of 20) = 26 m.
        val run = runReplay(walkLog(listOf(27.0 to 0.0)), onUpdate = alignFirst())
        assertEquals(NavPhase.UNCERTAIN, run.nav.ui.value.phase)
        assertTrue(run.nav.ui.value.instruction.contains("uncertain"))
    }

    @Test
    fun seeLandmarkSnapsProgress() = runTest {
        var snapshot: NavUi? = null
        var anchorsAtSnap = -1
        val events = mutableListOf<NavEvent>()
        var snapped = false
        val align = alignFirst()
        val run = runReplay(walkLog(listOf(20.0 to 0.0, 5.0 to 90.0))) { nav, update ->
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
        val snap = snapshot ?: error("SeeLandmark was never injected")
        assertEquals(NavPhase.TURNING, snap.phase)
        assertEquals(1.0, snap.progress01, 1e-9)
        assertEquals(0.0, snap.distanceToNextM, 1e-9)
        assertEquals(1, anchorsAtSnap)
    }

    @Test
    fun wrongStartGoesOffRoute() = runTest {
        // Aligned facing 0°, then walks the first leg 70° off heading.
        val run = runReplay(
            walkLog(listOf(7.0 to 70.0), startYawDeg = 0.0),
            onUpdate = alignFirst(),
        )
        assertEquals(NavPhase.OFF_ROUTE, run.nav.ui.value.phase)
        assertTrue(
            run.nav.ui.value.instruction.contains("doesn't match the start"),
            "instruction was: ${run.nav.ui.value.instruction}",
        )
    }

    @Test
    fun corridorSnappingReducesOffsetWithin10m() = runTest {
        // Aligned at 0°, then walks straight with a steady 15° yaw offset.
        val run = runReplay(
            walkLog(listOf(10.0 to 15.0), startYawDeg = 0.0),
            onUpdate = alignFirst(),
        )
        assertEquals(NavPhase.WALK, run.nav.ui.value.phase)
        assertTrue(
            abs(run.nav.ui.value.arrowRad) < deg(5.0),
            "arrow was ${run.nav.ui.value.arrowRad} rad, expected < 5°",
        )
    }

    /**
     * W10-08: the L walk turns right then left — each approach cue speaks
     * the turn direction, each detected turn anchors (CONFIRM), then
     * ARRIVE. Exact sequence, no TICK: both nodes have a turn direction.
     */
    @Test
    fun turnCuesFireRightThenLeft() = runTest {
        val align = alignFirst()
        val run = runReplay(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0))) { nav, update ->
            align(nav, update)
            if (nav.ui.value.legIndex == 2 && nav.ui.value.distanceToNextM <= 2.0 &&
                nav.ui.value.phase != NavPhase.ARRIVED
            ) {
                nav.onInput(NavInput.ImHere)
            }
        }
        assertEquals(NavPhase.ARRIVED, run.nav.ui.value.phase)
        assertEquals(
            listOf(
                NavEvent.Haptic(HapticPattern.RIGHT),
                NavEvent.Haptic(HapticPattern.CONFIRM),
                NavEvent.Haptic(HapticPattern.LEFT),
                NavEvent.Haptic(HapticPattern.CONFIRM),
                NavEvent.Haptic(HapticPattern.ARRIVE),
            ),
            run.events.filterIsInstance<NavEvent.Haptic>(),
        )
    }

    /**
     * W10-08: confirming the turn node re-emits the approach cue on
     * entering TURNING. SeeLandmark lands past the 16 m approach line, so
     * the approach RIGHT fires first, then TURNING re-emits RIGHT.
     */
    @Test
    fun turningReEmitsApproachCue() = runTest {
        val align = alignFirst()
        var snapped = false
        val run = runReplay(walkLog(listOf(20.0 to 0.0))) { nav, update ->
            align(nav, update)
            if (!snapped && update.distanceM >= 17.0) {
                snapped = true
                nav.onInput(NavInput.SeeLandmark)
            }
        }
        assertTrue(snapped, "SeeLandmark was never injected")
        assertEquals(NavPhase.TURNING, run.nav.ui.value.phase)
        assertEquals(
            listOf(
                NavEvent.Haptic(HapticPattern.RIGHT),
                NavEvent.Haptic(HapticPattern.RIGHT),
            ),
            run.events.filterIsInstance<NavEvent.Haptic>(),
        )
    }

    /**
     * W10-08: a U-turn node approaches with U_TURN, then overshooting the
     * 10 m leg (UNCERTAIN past 10 + max(5, 30%) = 15 m) warns. Exact
     * sequence over a 16 m straight walk.
     */
    @Test
    fun uTurnApproachEmitsUTurn() = runTest {
        val run = runReplay(
            walkLog(listOf(16.0 to 0.0)),
            onUpdate = alignFirst(),
            route = turnRoute(TurnDir.U_TURN),
        )
        assertEquals(NavPhase.UNCERTAIN, run.nav.ui.value.phase)
        assertEquals(
            listOf(
                NavEvent.Haptic(HapticPattern.U_TURN),
                NavEvent.Haptic(HapticPattern.WARN),
            ),
            run.events.filterIsInstance<NavEvent.Haptic>(),
        )
    }

    /**
     * W10-08: a turn node with no direction is a straight checkpoint — its
     * approach keeps the neutral TICK. An 8 m walk of the 10 m leg enters
     * APPROACH (at 6 m) without overshooting, so TICK is the only haptic.
     */
    @Test
    fun turnWithoutDirectionKeepsTick() = runTest {
        val run = runReplay(
            walkLog(listOf(8.0 to 0.0)),
            onUpdate = alignFirst(),
            route = turnRoute(turn = null),
        )
        assertEquals(NavPhase.APPROACH, run.nav.ui.value.phase)
        assertEquals(
            listOf(NavEvent.Haptic(HapticPattern.TICK)),
            run.events.filterIsInstance<NavEvent.Haptic>(),
        )
    }

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
