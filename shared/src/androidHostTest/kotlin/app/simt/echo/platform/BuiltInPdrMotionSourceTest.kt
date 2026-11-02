package app.simt.echo.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import app.simt.echo.capture.PdrEngine
import app.simt.echo.capture.RotSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

    @Test
    fun aPhoneWithoutAGyroscopeStillSeesItselfTurn() {
        // The Galaxy A16: accelerometer and magnetometer only, so the one
        // heading sensor on offer is the geomagnetic rotation vector.
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val geomagnetic = ShadowSensor.newInstance(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        shadowOf(manager).addSensor(geomagnetic)
        source.start()

        // Quaternion (x, y, z, w) about the vertical axis: first facing the start, then 90 deg round.
        fun turnedBy(degrees: Double) = SensorEvent::class.java.getDeclaredConstructor(Int::class.java).apply { isAccessible = true }
            .newInstance(5).also { event ->
                val half = Math.toRadians(degrees) / 2
                event.values[0] = 0f
                event.values[1] = 0f
                event.values[2] = sin(half).toFloat()
                event.values[3] = cos(half).toFloat()
                event.values[4] = -1f
                event.sensor = geomagnetic
                event.timestamp = 1_000L
            }
        shadowOf(manager).sendSensorEventToListeners(turnedBy(0.0))
        shadowOf(manager).sendSensorEventToListeners(turnedBy(90.0))

        val yaws = runBlocking { withTimeout(3_000L) { source.updates.take(2).map { it.yawRad }.toList() } }

        assertEquals(0.0, yaws.first(), 1e-6)
        assertEquals(PI / 2, abs(yaws.last()), 0.05)
    }
}
