package app.simt.echo.platform

import app.simt.echo.capture.FrameTapMux
import app.simt.echo.capture.NoOpFrameTap
import app.simt.echo.capture.PdrEngine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BuiltInVisualMotionSourceTest {
    @Test
    fun optionalCapabilityJoinsOnlyAvailableTapAndDetachesWithoutClearingLedger() {
        val source = BuiltInPdrMotionSource(RuntimeEnvironment.getApplication(), PdrEngine())
        val tap = FrameTapMux()
        assertFalse(source.attachVisual(tap, enabled = false))
        assertFalse(tap.hasConsumers)
        assertFalse(source.attachVisual(NoOpFrameTap, enabled = true))
        assertFalse(source.visualAssistActive)
        assertTrue(source.attachVisual(tap, enabled = true))
        assertTrue(tap.hasConsumers)
        assertFalse(source.visualAssistActive)
        source.detachVisual()
        assertFalse(tap.hasConsumers)
        tap.setConsumer {}
        assertTrue(source.attachVisual(tap, enabled = true))
        source.detachVisual()
        assertTrue(tap.hasConsumers)
        tap.setConsumer(null)
    }
}
