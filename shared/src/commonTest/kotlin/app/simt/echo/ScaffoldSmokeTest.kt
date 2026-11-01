package app.simt.echo

import app.simt.echo.platform.MotionUpdate
import app.simt.echo.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

/** Scaffold wiring checks: the skeleton every slice builds on must hold. */
class ScaffoldSmokeTest {

    private class FakePlatform : Platform {
        override val name: String = "test"
        override val dataDir: String = "/tmp"
    }

    @Test
    fun appGraphRetainsPlatform() {
        val graph = AppGraph(platform = FakePlatform())
        assertEquals("test", graph.platform.name)
    }

    @Test
    fun motionUpdateCarriesSensorGeometry() {
        val update = MotionUpdate(
            tNs = 1_000L,
            distanceM = 12.5,
            yawRad = 1.57,
            stepCount = 16,
            isStep = true,
            pressureHpa = 1013.25,
            source = "test",
        )
        assertEquals(12.5, update.distanceM)
        assertEquals(16, update.stepCount)
    }
}
