package app.simt.echo.ui.screens.navigate

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import app.simt.echo.Fixtures
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.capture.visual.VisualGateCounters
import app.simt.echo.capture.visual.VisualMotionFrame
import app.simt.echo.capture.visual.VisualMotionSource
import app.simt.echo.capture.visual.VisualScheduler
import app.simt.echo.capture.visual.withParallax
import app.simt.echo.data.ConfigRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteMemoryRepository
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.TrialMode
import app.simt.echo.model.ViewStrip
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavWearState
import app.simt.echo.nav.WearNavSync
import app.simt.echo.nav.endedWearState
import app.simt.echo.nav.walkLog
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.MotionSource
import app.simt.echo.sim.SimWalk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W2-06 gate: replaying a capture through [NavigateViewModel] reaches
 * ARRIVED with the haptic sequence, and engine phases (notably UNCERTAIN)
 * surface 1:1 in [NavigateViewModel.ui] — the S-reviewed mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigateViewModelTest {

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

    private class CountingVisualMotion(
        private val inner: ReplayMotionSource,
        private val strip: ByteArray? = null,
    ) : MotionSource by inner, VisualMotionSource {
        val intervals = mutableListOf<Long>()
        override val visualAssistActive = true
        override val usability01 = 1.0
        override var batterySaver = false
        override val gateCounters = VisualGateCounters(7, 3, 2, 1)
        private val frame = VisualMotionFrame().apply {
            ego.confidence01 = 1.0
            usability01 = 1.0
            if (strip != null) {
                strip.copyInto(viewStrip)
                hasViewStrip = true
            }
        }
        override fun attachVisual(tap: FrameTap, enabled: Boolean, intervalNs: Long) = true
        override fun detachVisual() = Unit
        override fun setVisualInterval(intervalNs: Long) {
            intervals += intervalNs
        }
        override fun drainVisual(tNs: Long): VisualMotionFrame? =
            if (strip == null) null else frame.also { it.tNs = tNs }
    }

    private fun routesWithLRoute(): RouteRepository {
        val routes = RouteRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        routes.save(Fixtures.L_ROUTE)
        return routes
    }

    @Test
    fun replayToArrivedFiresHapticSequence() = runTest(dispatcher) {
        val haptics = FakeHaptics()
        var arrived: String? = null
        val source = ReplayMotionSource(
            walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)),
            this,
        )
        val vm = NavigateViewModel(Fixtures.L_ROUTE.routeId, routesWithLRoute(), source, haptics) {
            arrived = it
        }
        runCurrent()
        vm.start()
        vm.align()
        val watcher = launch {
            vm.ui.collect { ui ->
                if (ui != null && ui.legIndex == 2 && ui.distanceToNextM <= 2.0 &&
                    ui.phase != NavPhase.ARRIVED
                ) {
                    vm.imHere()
                }
            }
        }
        advanceUntilIdle()
        watcher.cancel()

        assertEquals(NavPhase.ARRIVED, vm.ui.value?.phase)
        assertEquals(Fixtures.L_ROUTE.routeId, arrived)
        // The acceptance criterion is the sequence, not its members. This
        // walk turns twice (0° → 90° → 0°): each turn is approached with
        // its direction cue (RIGHT, then LEFT — W10-08 turn language, once
        // per leg) and anchored (CONFIRM), then ARRIVE — nothing else but
        // the heartbeat TICKs filtered out below.
        assertEquals(
            listOf(
                HapticPattern.RIGHT,
                HapticPattern.CONFIRM,
                HapticPattern.LEFT,
                HapticPattern.CONFIRM,
                HapticPattern.ARRIVE,
            ),
            haptics.played.filter { it != HapticPattern.TICK },
        )
        // W13-09: the distance heartbeat ticks (and only ticks) in between.
        assertTrue(haptics.played.any { it == HapticPattern.TICK }, "no heartbeat ticks in ${haptics.played}")
    }

    @Test
    fun visualSchedulerDrivesTheTapRate() = runTest(dispatcher) {
        val haptics = FakeHaptics()
        val source = CountingVisualMotion(
            ReplayMotionSource(walkLog(listOf(5.0 to 0.0)), this),
        )
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId,
            routesWithLRoute(),
            source,
            haptics,
            config = EchoConfig(visualAssist = true),
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        advanceUntilIdle()

        assertTrue(source.intervals.isNotEmpty(), "scheduler never updated the visual tap")
        assertEquals(
            VisualScheduler.INTERVAL_2_HZ_NS,
            source.intervals.first(),
            "a walker who has not stepped yet keeps the 2 Hz standing witness",
        )
        assertTrue(
            source.intervals.contains(VisualScheduler.INTERVAL_1_HZ_NS),
            "a straight low-σ walk must drop to the 1 Hz probe: ${source.intervals}",
        )
    }

    @Test
    fun batterySaverHalvesTheWalkingProbeRate() = runTest(dispatcher) {
        val source = CountingVisualMotion(
            ReplayMotionSource(walkLog(listOf(5.0 to 0.0)), this),
        ).apply { batterySaver = true }
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId,
            routesWithLRoute(),
            source,
            FakeHaptics(),
            config = EchoConfig(visualAssist = true),
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        advanceUntilIdle()

        assertTrue(
            source.intervals.contains(VisualScheduler.INTERVAL_HALF_HZ_NS),
            "Battery Saver must halve the 1 Hz walking probe: ${source.intervals}",
        )
    }

    @Test
    fun anchoredLegPersistsTheLearnedStride() = runTest(dispatcher) {
        val configs = ConfigRepository(FileStore(FakeFileSystem(), "/cfg".toPath()))
        // The follower's odometer reads 14 m for the 20 m opening leg
        // (r = 1.43, inside the stride learner's outlier gate).
        val source = ReplayMotionSource(walkLog(listOf(14.0 to 0.0)), this)
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId, routesWithLRoute(), source, FakeHaptics(), configs = configs,
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        advanceUntilIdle()
        assertEquals(0, configs.get().learnedStrideLegs)

        vm.seeLandmark()
        runCurrent()

        assertEquals(1, configs.get().learnedStrideLegs)
        val learnedK = configs.get().learnedStepK ?: 0.0
        assertTrue(learnedK in 1.2..1.5, "learned K was $learnedK")
    }

    @Test
    fun lostSurfacesOffRouteWithWarn() = runTest(dispatcher) {
        val haptics = FakeHaptics()
        val source = ReplayMotionSource(walkLog(listOf(5.0 to 0.0)), this)
        val vm = NavigateViewModel(Fixtures.L_ROUTE.routeId, routesWithLRoute(), source, haptics) {}
        runCurrent()
        vm.start()
        vm.align()
        runCurrent()

        vm.lost()
        runCurrent()

        assertEquals(NavPhase.OFF_ROUTE, vm.ui.value?.phase)
        assertEquals(listOf(HapticPattern.WARN), haptics.played)
    }

    @Test
    fun overshootSurfacesUncertain() = runTest(dispatcher) {
        val haptics = FakeHaptics()
        val source = ReplayMotionSource(walkLog(listOf(27.0 to 0.0)), this)
        val vm = NavigateViewModel(Fixtures.L_ROUTE.routeId, routesWithLRoute(), source, haptics) {}
        runCurrent()
        vm.start()
        vm.align()
        advanceUntilIdle()

        assertEquals(NavPhase.UNCERTAIN, vm.ui.value?.phase)
        assertTrue(haptics.played.contains(HapticPattern.WARN))
    }

    /** Replay the two-turn walk to ARRIVED, tapping "I'm here" on the last leg. */
    private fun kotlinx.coroutines.test.TestScope.driveToArrived(vm: NavigateViewModel): kotlinx.coroutines.Job {
        val watcher = launch {
            vm.ui.collect { ui ->
                if (ui != null && ui.legIndex == 2 && ui.distanceToNextM <= 2.0 &&
                    ui.phase != NavPhase.ARRIVED
                ) {
                    vm.imHere()
                }
            }
        }
        advanceUntilIdle()
        watcher.cancel()
        return watcher
    }

    @Test
    fun arrivalWritesTrialWithSessionStats() = runTest(dispatcher) {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val trials = TrialRepository(store)
        val source = ReplayMotionSource(
            walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)),
            this,
        )
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId, routes, source, FakeHaptics(),
            trials = trials, device = "pixel-8", nowMs = { 5_000L },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        driveToArrived(vm)

        assertEquals(NavPhase.ARRIVED, vm.ui.value?.phase)
        val trial = trials.list().single()
        assertEquals(Fixtures.L_ROUTE.routeId, trial.routeId)
        assertEquals(TrialMode.ECHO, trial.mode)
        assertEquals("pixel-8", trial.device)
        assertTrue(trial.arrived)
        assertTrue(trial.anchors >= 1, "turn anchors counted, got ${trial.anchors}")
        assertEquals(0, trial.confirmations)
        assertTrue(trial.distanceM > 0.0)
    }

    @Test
    fun arrivalWritesVisualGateCounters() = runTest(dispatcher) {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val trials = TrialRepository(store)
        val source = CountingVisualMotion(
            ReplayMotionSource(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)), this),
        )
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId, routes, source, FakeHaptics(),
            trials = trials, device = "pixel-8", nowMs = { 5_000L },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        driveToArrived(vm)

        val trial = trials.list().single()
        assertEquals(7, trial.visualGateAccepted)
        assertEquals(3, trial.visualGateRejected)
        assertEquals(2, trial.visualGateUnwitnessed)
        assertEquals(1, trial.visualGateSuspects)
    }

    @Test
    fun baselineRunIsSensorOnlyAndSeeLandmarkDoesNotCount() = runTest(dispatcher) {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val trials = TrialRepository(store)
        val source = ReplayMotionSource(
            walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)),
            this,
        )
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId, routes, source, FakeHaptics(),
            trials = trials, device = "pixel-8", baselineMode = true, nowMs = { 5_000L },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        vm.seeLandmark()
        driveToArrived(vm)

        val trial = trials.list().single()
        assertEquals(TrialMode.SENSOR_ONLY, trial.mode)
        assertEquals(0, trial.confirmations)
    }

    /** A 6 m straight route whose parallax profile carries [strips]. */
    private fun routeWithMemoryStrips(strips: List<ViewStrip>, lengthM: Double = 6.0): EchoRoute {
        val buckets = (lengthM / ParallaxProfile.STEP_M).toInt() + 1
        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(buckets) { ParallaxProfiler.quantiseRho(0.2) }),
            trackability = Base64.encode(ByteArray(buckets) { 220.toByte() }),
            viewStrips = strips,
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
        return SimWalk.straightRoute(lengthM).withParallax(profile)
    }

    /** "I'm here" once the walker is inside the 2 m arrival prompt. */
    private fun kotlinx.coroutines.test.TestScope.driveToArrivalPrompt(vm: NavigateViewModel) {
        val watcher = launch {
            vm.ui.collect { ui ->
                if (ui != null && ui.distanceToNextM <= 2.0 && ui.phase != NavPhase.ARRIVED) {
                    vm.imHere()
                }
            }
        }
        advanceUntilIdle()
        watcher.cancel()
    }

    @Test
    fun successfulFollowStoresTheFollowerStripAtItsConfirmedPosition() = runTest(dispatcher) {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val memory = RouteMemoryRepository(store)
        val strip = ByteArray(ViewStrip.BYTES) { i -> (i * 5 - 37).toByte() }
        val route = routeWithMemoryStrips(listOf(ViewStrip(5.0, Base64.encode(strip))))
            .copy(routeId = "route-memory-follow")
        val routes = RouteRepository(store)
        routes.save(route)
        val vm = NavigateViewModel(
            route.routeId,
            routes,
            CountingVisualMotion(ReplayMotionSource(walkLog(listOf(6.0 to 0.0)), this), strip = strip),
            FakeHaptics(),
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            routeMemory = memory,
            nowMs = { 5_000L },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        driveToArrivalPrompt(vm)

        assertEquals(NavPhase.ARRIVED, vm.ui.value?.phase)
        val entry = assertNotNull(memory.get(route.routeId, nowMs = 5_000L)).entries.single()
        assertEquals(5.0, entry.atDistanceM, 1e-6)
        assertContentEquals(strip, Base64.decode(entry.dataB64))
    }

    @Test
    fun storedOverlayStripsEnableFixesOnARouteWithoutItsOwnStrips() = runTest(dispatcher) {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val memory = RouteMemoryRepository(store)
        val strip = ByteArray(ViewStrip.BYTES) { i -> (i * 7 - 54).toByte() }
        val route = routeWithMemoryStrips(emptyList()).copy(routeId = "route-memory-overlay")
        val routes = RouteRepository(store)
        routes.save(route)
        assertTrue(
            memory.recordFollow(
                route = route,
                arrivalSigmaM = 1.0,
                visualLockRatio01 = 0.9,
                observedStrips = listOf(ViewStrip(5.0, Base64.encode(strip))),
                nowMs = 1_000L,
            ),
        )
        val vm = NavigateViewModel(
            route.routeId,
            routes,
            CountingVisualMotion(ReplayMotionSource(walkLog(listOf(6.0 to 0.0)), this), strip = strip),
            FakeHaptics(),
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            routeMemory = memory,
            nowMs = { 5_000L },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        driveToArrivalPrompt(vm)

        val entries = assertNotNull(memory.get(route.routeId, nowMs = 5_000L)).entries
        val latest = assertNotNull(entries.maxByOrNull { it.recordedAtEpochMs })
        assertEquals(5_000L, latest.recordedAtEpochMs, "the walk must re-record from the overlay-only match")
        assertEquals(5.0, latest.atDistanceM, 1e-6)
    }

    @Test
    fun aWalkWithoutVisualFramesStoresNoRouteMemory() = runTest(dispatcher) {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val memory = RouteMemoryRepository(store)
        val strip = ByteArray(ViewStrip.BYTES) { i -> (i * 3 - 11).toByte() }
        val route = routeWithMemoryStrips(listOf(ViewStrip(5.0, Base64.encode(strip))))
            .copy(routeId = "route-memory-blind")
        val routes = RouteRepository(store)
        routes.save(route)
        val vm = NavigateViewModel(
            route.routeId,
            routes,
            CountingVisualMotion(ReplayMotionSource(walkLog(listOf(6.0 to 0.0)), this)),
            FakeHaptics(),
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            routeMemory = memory,
            nowMs = { 5_000L },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        driveToArrivalPrompt(vm)

        assertNull(memory.get(route.routeId, nowMs = 5_000L))
    }

    @Test
    fun unknownRouteHasNullUi() = runTest(dispatcher) {
        val source = ReplayMotionSource(walkLog(listOf(1.0 to 0.0)), this)
        val vm = NavigateViewModel("no-such-route", routesWithLRoute(), source, FakeHaptics()) {}
        advanceUntilIdle()

        assertNull(vm.ui.value)
    }

    @Test
    fun wearMirrorReceivesMappedSnapshots() = runTest(dispatcher) {
        val mirrored = mutableListOf<NavWearState>()
        val source = ReplayMotionSource(walkLog(listOf(5.0 to 0.0)), this)
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId, routesWithLRoute(), source, FakeHaptics(),
            wearNav = { mirrored += it },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        advanceUntilIdle()

        assertTrue(mirrored.isNotEmpty(), "mirror sink saw no snapshots")
        val ui = vm.ui.value
        assertEquals(ui?.phase?.name, mirrored.last().phase)
        assertEquals(ui?.instruction?.take(WearNavSync.MAX_CUE_CHARS), mirrored.last().cue)
    }

    @Test
    fun leavingTheScreenSendsTheEndedSnapshotToTheWatch() = runTest(dispatcher) {
        val mirrored = mutableListOf<NavWearState>()
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                addInitializer(NavigateViewModel::class) {
                    NavigateViewModel(
                        Fixtures.L_ROUTE.routeId, routesWithLRoute(),
                        ReplayMotionSource(walkLog(listOf(5.0 to 0.0)), this@runTest), FakeHaptics(),
                        wearNav = { mirrored += it },
                    ) {}
                }
            },
        )[NavigateViewModel::class]
        runCurrent()
        vm.start()
        vm.align()
        advanceUntilIdle()
        assertTrue(mirrored.none { it.phase == WearNavSync.PHASE_ENDED })

        store.clear()

        assertEquals(endedWearState(), mirrored.last())
    }

    @Test
    fun wearMirrorFailureNeverBreaksNavigation() = runTest(dispatcher) {
        val source = ReplayMotionSource(walkLog(listOf(5.0 to 0.0)), this)
        val vm = NavigateViewModel(
            Fixtures.L_ROUTE.routeId, routesWithLRoute(), source, FakeHaptics(),
            wearNav = { throw IllegalStateException("no radio") },
        ) {}
        runCurrent()
        vm.start()
        vm.align()
        advanceUntilIdle()

        assertTrue(vm.ui.value != null, "navigation survived a throwing mirror sink")
    }
}
