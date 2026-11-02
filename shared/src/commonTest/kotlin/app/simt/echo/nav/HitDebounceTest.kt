package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.nav.ghost.FingerprintHit
import app.simt.echo.nav.ghost.HitDebounce
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.MotionUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W12-05 review: fingerprint hits repeat for every frame that still sees
 * the landmark. Without a debounce each one reweights the filter and
 * ticks the phone again, so a soft 2:1 nudge compounds into a snap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitDebounceTest {

    private val second = 1_000_000_000L

    @Test
    fun aCandidateNeedsThreeSecondsAndTwoMetresBetweenHits() {
        val debounce = HitDebounce()
        assertTrue(debounce.accept(key = 20.0, tNs = 0L, odoM = 0.0))
        // Time alone, distance alone, neither: all suppressed.
        assertFalse(debounce.accept(20.0, 3 * second, 1.0))
        assertFalse(debounce.accept(20.0, 1 * second, 5.0))
        assertFalse(debounce.accept(20.0, 2 * second, 1.0))
        // Both satisfied.
        assertTrue(debounce.accept(20.0, 3 * second, 2.0))
        // The window restarts from the accepted hit.
        assertFalse(debounce.accept(20.0, 5 * second, 3.0))
    }

    @Test
    fun distinctCandidatesAreIndependent() {
        val debounce = HitDebounce()
        assertTrue(debounce.accept(20.0, 0L, 0.0))
        assertTrue(debounce.accept(35.0, 0L, 0.0))
        assertFalse(debounce.accept(20.0, 1 * second, 0.5))
    }

    @Test
    fun repeatedHitsOnOneLandmarkTickOncePerPrompt() = runTest {
        val nav = Navigator(Fixtures.L_ROUTE, EchoConfig(navigatorEngine = NavigatorEngine.GHOST))
        val ticks = mutableListOf<NavEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            nav.events.collect { if (it is NavEvent.Haptic && it.pattern == HapticPattern.TICK) ticks += it }
        }
        var t = 0L
        var d = 0.0
        fun walk(seconds: Int, metres: Double) {
            repeat(seconds * 10) {
                t += second / 10
                d += metres / (seconds * 10)
                nav.onInput(NavInput.Motion(MotionUpdate(t, d, 0.0, 0, true, null, "test")))
            }
        }
        val hit = FingerprintHit(nodeS = 20.0, similarity01 = 0.9, label = "Red pillar")
        nav.onInput(NavInput.Motion(MotionUpdate(0L, 0.0, 0.0, 0, false, null, "test")))
        nav.onInput(NavInput.Aligned)

        // 30 frames over 3 s while the camera still sees the pillar.
        repeat(30) {
            nav.onFingerprintHit(hit)
            t += second / 10
            d += 0.01
            nav.onInput(NavInput.Motion(MotionUpdate(t, d, 0.0, 0, true, null, "test")))
        }
        assertEquals(1, ticks.size, "one TICK for the prompt, not one per frame")

        // Later (>= 3 s and >= 2 m on) the same landmark may reweight again,
        // but the prompt is unchanged, so no second TICK.
        walk(seconds = 4, metres = 3.0)
        nav.onFingerprintHit(hit)
        assertEquals(1, ticks.size, "the same prompt does not tick again")

        // After the user dismisses it, a fresh sighting is a new prompt.
        nav.dismissFingerprint()
        walk(seconds = 4, metres = 3.0)
        nav.onFingerprintHit(hit)
        assertEquals(2, ticks.size)
        job.cancel()
    }
}
