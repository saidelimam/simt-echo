package app.simt.echo.ui.screens.navigate

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import app.simt.echo.Fixtures
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import app.simt.echo.nav.walkLog
import app.simt.echo.model.TurnDir
import app.simt.echo.platform.CueOutput
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.LiveUpdateDirector
import app.simt.echo.platform.LiveUpdatePlan
import app.simt.echo.platform.LiveUpdateSink
import app.simt.echo.platform.NAV_LIVE_UPDATE_ID
import app.simt.echo.platform.MotionSource
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W13-09: the distance heartbeat (TICK replayed on the freeze §7 schedule
 * while closing on a turn) and the cue outputs (earcon on every cue,
 * eyes-free announcements for decision points only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigateCuesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeHaptics : Haptics {
        val played = mutableListOf<HapticPattern>()
        override fun play(pattern: HapticPattern) {
            played += pattern
        }
    }

    private class FakeCues(override val eyesFree: Boolean) : CueOutput {
        val earcons = mutableListOf<HapticPattern>()
        val announced = mutableListOf<String>()
        override fun earcon(pattern: HapticPattern, arrowRad: Double) {
            earcons += pattern
        }
        override fun announce(text: String) {
            announced += text
        }
    }

    /** A source the test drives by hand, one odometer reading at a time. */
    private class ManualMotion : MotionSource {
        val flow = MutableSharedFlow<MotionUpdate>(extraBufferCapacity = 64)
        override val updates: Flow<MotionUpdate> = flow
        override fun start() = Unit
        override fun stop() = Unit
        private var t = 1_000_000_000L
        fun at(distanceM: Double) {
            t += 50_000_000L
            flow.tryEmit(MotionUpdate(t, distanceM, 0.0, 0, true, null, "test"))
        }
    }

    private fun routes(): RouteRepository {
        val repo = RouteRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        repo.save(Fixtures.L_ROUTE)
        return repo
    }

    private class Rig(
        val vm: NavigateViewModel,
        val motion: ManualMotion,
        val haptics: FakeHaptics,
        val cues: FakeCues,
    )

    private fun rig(
        config: EchoConfig = EchoConfig(),
        eyesFree: Boolean = false,
    ): Rig {
        val motion = ManualMotion()
        val haptics = FakeHaptics()
        val cues = FakeCues(eyesFree)
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId, routes(), motion, haptics, config, cues = cues,
        ) {}
        return Rig(vm, motion, haptics, cues)
    }

    private fun ticks(h: FakeHaptics) = h.played.count { it == HapticPattern.TICK }

    /** Starts the walk and brings the 20 m opening leg within 3 m of its right turn (APPROACH). */
    private fun kotlinx.coroutines.test.TestScope.approachTheTurn(r: Rig) {
        runCurrent()
        r.vm.start()
        r.motion.at(0.0)
        r.vm.align()
        runCurrent()
        r.motion.at(17.0)
        runCurrent()
        assertEquals(NavPhase.APPROACH, r.vm.ui.value?.phase)
    }

    /**
     * Ends the walk (OFF_ROUTE cancels the heartbeat): runTest drains the
     * scheduler on exit, and a live heartbeat would tick in virtual time forever.
     */
    private fun kotlinx.coroutines.test.TestScope.endWalk(r: Rig) {
        r.vm.lost()
        runCurrent()
    }

    @Test
    fun heartbeatTicksOnTheFreezeScheduleWhileClosingOnATurn() = runTest(dispatcher) {
        val r = rig()
        approachTheTurn(r)
        // 3 m to the turn: 400 + 3/8 * 800 = 700 ms between ticks.
        assertEquals(0, ticks(r.haptics))
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(4, ticks(r.haptics))
        // The turn cue itself was played once on entering APPROACH.
        assertEquals(1, r.haptics.played.count { it == HapticPattern.RIGHT })
        endWalk(r)
    }

    @Test
    fun heartbeatQuickensAsTheTurnGetsCloser() = runTest(dispatcher) {
        val r = rig()
        approachTheTurn(r)
        r.motion.at(19.0) // 1 m left: 400 + 1/8 * 800 = 500 ms
        runCurrent()
        advanceTimeBy(700) // the tick already pending from 3 m out
        runCurrent()
        val before = ticks(r.haptics)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(4, ticks(r.haptics) - before)
        endWalk(r)
    }

    @Test
    fun heartbeatStopsWhenThePhaseLeavesApproach() = runTest(dispatcher) {
        val r = rig()
        approachTheTurn(r)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(ticks(r.haptics) > 0)
        r.vm.lost()
        runCurrent()
        assertEquals(NavPhase.OFF_ROUTE, r.vm.ui.value?.phase)
        val frozen = ticks(r.haptics)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(frozen, ticks(r.haptics))
    }

    @Test
    fun heartbeatStopsWhenTheViewModelIsCleared() = runTest(dispatcher) {
        val motion = ManualMotion()
        val haptics = FakeHaptics()
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                addInitializer(NavigateViewModel::class) {
                    NavigateViewModel(Fixtures.L_ROUTE.routeId, routes(), motion, haptics) {}
                }
            },
        )[NavigateViewModel::class]
        runCurrent()
        vm.start()
        motion.at(0.0)
        vm.align()
        runCurrent()
        motion.at(17.0)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        val before = ticks(haptics)
        assertTrue(before > 0)
        store.clear()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(before, ticks(haptics))
    }

    @Test
    fun liveUpdatesFollowTheWalkAndAreDismissedWhenTheScreenGoes() = runTest(dispatcher) {
        val posted = mutableListOf<Pair<Int, LiveUpdatePlan>>()
        val dismissed = mutableListOf<Int>()
        val sink = object : LiveUpdateSink {
            override fun show(notificationId: Int, plan: LiveUpdatePlan) {
                posted += notificationId to plan
            }
            override fun dismiss(notificationId: Int) {
                dismissed += notificationId
            }
        }
        val motion = ManualMotion()
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                addInitializer(NavigateViewModel::class) {
                    NavigateViewModel(
                        Fixtures.L_ROUTE.routeId, routes(), motion, FakeHaptics(),
                        liveUpdates = LiveUpdateDirector(sink, 34),
                    ) {}
                }
            },
        )[NavigateViewModel::class]
        runCurrent()
        vm.start()
        motion.at(0.0)
        vm.align()
        runCurrent()
        motion.at(5.0)
        runCurrent()

        assertTrue(posted.isNotEmpty(), "no live update was posted")
        assertTrue(posted.all { it.first == NAV_LIVE_UPDATE_ID })
        assertEquals(Fixtures.L_ROUTE.name, posted.last().second.content.title)
        assertEquals("Navigating", posted.last().second.content.publicText)
        assertTrue(dismissed.isEmpty())

        store.clear()
        assertEquals(listOf(NAV_LIVE_UPDATE_ID), dismissed)
    }

    @Test
    fun heartbeatIsOffWhenSettingsTurnItOff() = runTest(dispatcher) {
        val r = rig(config = EchoConfig(turnHeartbeat = false))
        approachTheTurn(r)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, ticks(r.haptics))
        endWalk(r)
    }

    @Test
    fun noHeartbeatIsDueOutsideTheEightMetreWindowOrOffATurn() {
        fun ui(phase: NavPhase, distanceM: Double, turn: TurnDir?) = NavUi(
            phase = phase, legIndex = 0, instruction = "", distanceToNextM = distanceM,
            arrowRad = 0.0, nextTurn = turn, landmark = null, uncertaintyM = 0.5, progress01 = 0.0,
        )
        assertEquals(700L, heartbeatIntervalMs(ui(NavPhase.APPROACH, 3.0, TurnDir.RIGHT)))
        assertNull(heartbeatIntervalMs(ui(NavPhase.APPROACH, 8.5, TurnDir.RIGHT)))
        assertNull(heartbeatIntervalMs(ui(NavPhase.APPROACH, 3.0, null)))
        assertNull(heartbeatIntervalMs(ui(NavPhase.WALK, 3.0, TurnDir.RIGHT)))
        assertNull(heartbeatIntervalMs(ui(NavPhase.TURNING, 3.0, TurnDir.RIGHT)))
    }

    @Test
    fun everyCueGetsItsEarconButOnlyDecisionPointsAreAnnouncedEyesFree() = runTest(dispatcher) {
        val haptics = FakeHaptics()
        val cues = FakeCues(eyesFree = true)
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId,
            routes(),
            ReplayMotionSource(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)), this),
            haptics,
            cues = cues,
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        val watcher = launch {
            vm.ui.collect { ui ->
                if (ui != null && ui.legIndex == 2 && ui.distanceToNextM <= 2.0 && ui.phase != NavPhase.ARRIVED) {
                    vm.imHere()
                }
            }
        }
        advanceUntilIdle()
        watcher.cancel()

        val sequence = listOf(
            HapticPattern.RIGHT,
            HapticPattern.CONFIRM,
            HapticPattern.LEFT,
            HapticPattern.CONFIRM,
            HapticPattern.ARRIVE,
        )
        // Every cue is heard; the heartbeat TICK is only felt.
        assertEquals(sequence, cues.earcons)
        // Eyes-free: turns and arrival are announced, confirmations are not.
        assertEquals(3, cues.announced.size, "announced: ${cues.announced}")
    }

    @Test
    fun nothingIsAnnouncedWhenTheWalkerIsNotEyesFree() = runTest(dispatcher) {
        val r = rig(eyesFree = false)
        approachTheTurn(r)
        r.vm.lost()
        runCurrent()
        assertTrue(r.cues.announced.isEmpty())
        assertEquals(listOf(HapticPattern.RIGHT, HapticPattern.WARN), r.cues.earcons)
    }
}
