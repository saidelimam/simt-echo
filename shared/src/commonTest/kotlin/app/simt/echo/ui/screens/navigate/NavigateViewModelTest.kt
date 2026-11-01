package app.simt.echo.ui.screens.navigate

import app.simt.echo.Fixtures
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.walkLog
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // walk turns twice (0° → 90° → 0°): each turn is approached (TICK,
        // once per leg) and anchored (CONFIRM), then ARRIVE — nothing else.
        assertEquals(
            listOf(
                HapticPattern.TICK,
                HapticPattern.CONFIRM,
                HapticPattern.TICK,
                HapticPattern.CONFIRM,
                HapticPattern.ARRIVE,
            ),
            haptics.played,
        )
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

    @Test
    fun unknownRouteHasNullUi() = runTest(dispatcher) {
        val source = ReplayMotionSource(walkLog(listOf(1.0 to 0.0)), this)
        val vm = NavigateViewModel("no-such-route", routesWithLRoute(), source, FakeHaptics()) {}
        advanceUntilIdle()

        assertNull(vm.ui.value)
    }
}
