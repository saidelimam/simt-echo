package app.simt.echo.platform

import app.simt.echo.capture.PdrEngine
import app.simt.echo.capture.RotSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** W10-02 G2: the IMU source's start/stop lifecycle never leaks a thread or a stale update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class BuiltInPdrMotionSourceTest {

    private val engine = PdrEngine()
    private lateinit var source: BuiltInPdrMotionSource

    // Identity device→world rotation: a valid game-rotation sample.
    private val rotation = RotSample(tNs = 1L, r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

    @Before
    fun setUp() {
        source = BuiltInPdrMotionSource(RuntimeEnvironment.getApplication(), engine)
        awaitImuThreads(0)
    }

    @After
    fun tearDown() = source.stop()

    private fun imuThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "echo-imu" && it.isAlive }

    /** Thread death after quitSafely is asynchronous: poll, never sleep blindly. */
    private fun awaitImuThreads(expected: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (imuThreads() != expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, imuThreads())
    }

    @Test
    fun startingTwiceQuitsTheFirstImuThread() {
        source.start()
        source.start()

        awaitImuThreads(1)
        source.stop()
        awaitImuThreads(0)
    }

    @Test
    fun theFirstSampleRightAfterStartReachesTheCollector() {
        source.start()
        // No replay upstream: only a forwarder already subscribed catches this.
        engine.onRotation(rotation)

        val update = runBlocking { withTimeout(2_000L) { source.updates.first() } }

        assertNotNull(update)
    }

    @Test
    fun stopDropsUpdatesBufferedForTheNextRun() {
        source.start()
        engine.onRotation(rotation)
        Thread.sleep(300) // let the forwarder move it into the channel
        source.stop()

        source.start()
        val stale = runBlocking { withTimeoutOrNull(300L) { source.updates.first() } }

        assertNull("an update from the previous run leaked into the next", stale)
    }
}
