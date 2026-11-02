package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.platform.MotionUpdate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W12-02 review: the Ghost heading likelihood is applied about once per
 * step, not once per ~100 Hz motion update — otherwise the same heading is
 * counted dozens of times per stride and the filter resamples constantly.
 */
class GhostHeadingGateTest {

    @Test
    fun headingIsObservedAtMostOncePerHalfMetreNotPerMotionUpdate() {
        val nav = Navigator(Fixtures.L_ROUTE, EchoConfig(navigatorEngine = NavigatorEngine.GHOST))
        nav.onInput(NavInput.Motion(MotionUpdate(TEST_T0_NS, 0.0, 0.0, 0, false, null, "test")))
        nav.onInput(NavInput.Aligned)
        // 20 m straight at 100 Hz: 2000 motion updates.
        var d = 0.0
        var t = TEST_T0_NS
        repeat(2_000) { i ->
            d += 0.01
            t += 10_000_000L
            nav.onInput(NavInput.Motion(MotionUpdate(t, d, 0.0, i, true, null, "test")))
        }
        val observationsAllowed = (20.0 / Navigator.HEADING_OBS_STEP_M).toInt()
        assertTrue(
            nav.ghostHeadingObservations in 1..observationsAllowed,
            "${nav.ghostHeadingObservations} heading observations over 2000 updates, " +
                "at most $observationsAllowed allowed",
        )
    }
}
