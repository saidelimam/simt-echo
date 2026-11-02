package app.simt.echo.ui.screens.navigate

import app.simt.echo.capture.ReplayMotionSource
import app.simt.echo.capture.captureLogForRoute
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.nav.NavPhase
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
import kotlin.test.assertTrue

/**
 * W13-13 gate: the guided practice tour replays the bundled fixture walk
 * through the real [NavigateViewModel] instrument, labelled Practice and
 * never listed among routes — and it writes neither `routes/` nor
 * `trials/`.
 *
 * The tour is wired exactly as [app.simt.echo.ui.EchoNavHost] wires it:
 * ephemeral route + null trial sink on an empty store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeTourTest {

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

    /** Empty store: the practice walk is bundled code, never saved. */
    private fun emptyStore(): FileStore = FileStore(FakeFileSystem(), "/data".toPath())

    /** The tour as the host wires it: ephemeral route, null trial sink. */
    private fun kotlinx.coroutines.test.TestScope.tourViewModel(
        store: FileStore,
        onArrived: (String) -> Unit,
    ): NavigateViewModel {
        val routes = RouteRepository(store)
        val practice = practiceRoute()
        return NavigateViewModel(
            PRACTICE_ROUTE_ID,
            routes,
            ReplayMotionSource(captureLogForRoute(practice), this),
            FakeHaptics(),
            trials = null,
            ephemeralRoute = practice,
            onArrived = onArrived,
        )
    }

    /** Replays the tour to the end, tapping "I'm here" on the last leg. */
    private fun kotlinx.coroutines.test.TestScope.driveTour(vm: NavigateViewModel) {
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
    }

    @Test
    fun tourReachesFirstTurnThenArrival() = runTest(dispatcher) {
        var arrived: String? = null
        val vm = tourViewModel(emptyStore()) { arrived = it }
        runCurrent()

        val seen = mutableSetOf<NavPhase>()
        val recorder = launch { vm.ui.collect { ui -> ui?.let { seen += it.phase } } }
        driveTour(vm)
        recorder.cancel()

        assertTrue(
            seen.contains(NavPhase.TURNING) || seen.contains(NavPhase.APPROACH),
            "tour passes the first turn, saw $seen",
        )
        assertEquals(NavPhase.ARRIVED, vm.ui.value?.phase)
        assertEquals(PRACTICE_ROUTE_ID, arrived)
    }

    @Test
    fun tourWritesNeitherRoutesNorTrials() = runTest(dispatcher) {
        val store = emptyStore()
        var arrived: String? = null
        val vm = tourViewModel(store) { arrived = it }
        runCurrent()
        driveTour(vm)

        assertEquals(NavPhase.ARRIVED, vm.ui.value?.phase)
        assertEquals(PRACTICE_ROUTE_ID, arrived)
        // The no-write guarantee (S review): the tour never persists —
        // no route directory, no trial file, on a store that started empty.
        assertTrue(store.list("routes").isEmpty(), "tour wrote to routes/")
        assertTrue(store.list("trials").isEmpty(), "tour wrote to trials/")
        assertTrue(RouteRepository(store).list().isEmpty(), "tour walk is listed")
    }

    @Test
    fun practiceWalkIsNeverListedAmongRoutes() {
        val store = emptyStore()
        assertEquals(PRACTICE_ROUTE_ID, practiceRoute().routeId)
        assertTrue(
            RouteRepository(store).list().none { it.routeId == PRACTICE_ROUTE_ID },
            "Practice must never appear among saved routes",
        )
    }
}
