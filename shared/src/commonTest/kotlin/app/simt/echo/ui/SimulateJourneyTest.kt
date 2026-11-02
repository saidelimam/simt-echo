package app.simt.echo.ui

import app.simt.echo.Fixtures
import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.capture.captureLogForRoute
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.nav.NavPhase
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.ui.screens.navigate.NavigateViewModel
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
import kotlin.math.PI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W7-04 journey: fixture route Home → RouteDetail → Align → Navigate →
 * ARRIVED on [ReplayMotionSource].
 *
 * The compose legs (RouteDetail entries, Align confirmation) are pinned
 * in `androidHostTest` against the real screens; this suite pins the
 * host's flag flow end-to-end (RouteDetail's Simulate choice survives
 * Align into Navigate) plus the arrival the replay must produce. W7-09
 * may extend it into a single compose journey.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulateJourneyTest {

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
        override fun play(pattern: HapticPattern) = Unit
    }

    private fun routesWithLRoute(): RouteRepository {
        val routes = RouteRepository(FileStore(FakeFileSystem(), "/data".toPath()))
        routes.save(Fixtures.L_ROUTE)
        return routes
    }

    @Test
    fun navigateHopCarriesLiveChoice() {
        assertEquals(
            EchoRoute.Navigate("r1", simulate = false),
            navigateAfterAlign(alignForRoute("r1", simulate = false)),
        )
    }

    @Test
    fun simulateHopCarriesSimulateFlag() {
        assertEquals(
            EchoRoute.Navigate("r1", simulate = true),
            navigateAfterAlign(alignForRoute("r1", simulate = true)),
        )
    }

    @Test
    fun replayLogFollowsSavedGeometry() {
        val log = captureLogForRoute(Fixtures.L_ROUTE)
        assertTrue(log.samples.size > 2, "replay has samples")
        assertEquals(0.0, log.samples.first().d, 1e-9)
        assertEquals(Fixtures.L_ROUTE.totalLengthM, log.samples.last().d, 1e-9)
        // L route: north 20 m, east 15 m, north 10 m.
        assertEquals(0.0, log.samples.first().yaw, 1e-9)
        val east = log.samples.first { it.d > 20.0 && it.d < 35.0 }
        assertEquals(PI / 2, east.yaw, 1e-9)
        val northAgain = log.samples.first { it.d > 35.0 }
        assertEquals(0.0, northAgain.yaw, 1e-9)
    }

    @Test
    fun simulateLoopReachesArrived() = runTest(dispatcher) {
        val routes = routesWithLRoute()
        val routeId = Fixtures.L_ROUTE.routeId

        // The exact hops EchoNavHost performs: RouteDetail Simulate →
        // Align(simulate) → Navigate(simulate), then the host's replay
        // source built from the saved route.
        val navigate = navigateAfterAlign(alignForRoute(routeId, simulate = true))
        assertTrue(navigate.simulate, "simulate flag honoured into Navigate")
        val route = routes.get(navigate.routeId) ?: error("fixture route missing")
        val source = ReplayMotionSource(captureLogForRoute(route), this)

        var arrived: String? = null
        val vm = NavigateViewModel(routeId, routes, source, FakeHaptics()) {
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
        assertEquals(routeId, arrived)
    }
}
