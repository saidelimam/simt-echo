package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.model.CaptureLog
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
        onUpdate: (Navigator, MotionUpdate) -> Unit = { _, _ -> },
    ): Run {
        val nav = Navigator(Fixtures.L_ROUTE)
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
            alignFirst(),
        )
        assertEquals(NavPhase.OFF_ROUTE, run.nav.ui.value.phase)
        assertTrue(run.nav.ui.value.instruction.contains("Off route"))
    }

    @Test
    fun earlyTurnGoesUncertain() = runTest {
        val run = runReplay(
            walkLog(listOf(8.0 to 0.0, 10.0 to 90.0)),
            alignFirst(),
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
        val run = runReplay(walkLog(listOf(27.0 to 0.0)), alignFirst())
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
            alignFirst(),
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
            alignFirst(),
        )
        assertEquals(NavPhase.WALK, run.nav.ui.value.phase)
        assertTrue(
            abs(run.nav.ui.value.arrowRad) < deg(5.0),
            "arrow was ${run.nav.ui.value.arrowRad} rad, expected < 5°",
        )
    }
}
