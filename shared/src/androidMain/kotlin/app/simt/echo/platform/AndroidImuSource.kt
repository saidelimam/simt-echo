package app.simt.echo.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.simt.echo.capture.ImuSample
import app.simt.echo.capture.MagSample
import app.simt.echo.capture.PdrEngine
import app.simt.echo.capture.StepCalibratable
import app.simt.echo.capture.PressureSample
import app.simt.echo.capture.RotSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Android sensors → [PdrEngine] (slice W1-01).
 *
 * This is ECHO's shipping `MotionSource` (`echo_builtin_pdr`) per the
 * W0-01 decision: §6.1–6.2 is implemented natively in `capture/`,
 * following the step/heading approach copied from the solved track
 * code in `simt-app` — vendored, ECHO-owned, no dependency. Accelerometer + game rotation
 * vector + magnetometer at game rate (~50 Hz), barometer at UI rate; sensor events feed
 * the engine directly, in delivery order.
 *
 * Threading (W10-02 G2): sensors register on a dedicated
 * `HandlerThread("echo-imu")`, so step detection never competes with
 * Compose for the main looper. Updates deliver through a buffered channel
 * — steps need every sample, so the buffer suspends under backpressure
 * instead of conflating or dropping.
 */
class BuiltInPdrMotionSource(context: Context) : MotionSource, StepCalibratable {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val engine = PdrEngine()

    private var imuThread: HandlerThread? = null
    private var imuHandler: Handler? = null

    private val pipe = Channel<MotionUpdate>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val updates: Flow<MotionUpdate> = pipe.receiveAsFlow()

    private val forwardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var forwardJob: Job? = null

    /** Calibration reaches the engine it wraps (W4-02): without this
     * forward, `CaptureSession`'s step-K push silently no-ops on the
     * shipping Android source. */
    override var stepK: Double
        get() = engine.stepK
        set(value) {
            engine.stepK = value
        }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> engine.onAccelerometer(
                    ImuSample(
                        tNs = event.timestamp,
                        ax = event.values[0].toDouble(),
                        ay = event.values[1].toDouble(),
                        az = event.values[2].toDouble(),
                    ),
                    wallNowNs = nowNs,
                )

                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    val matrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    engine.onRotation(RotSample(tNs = event.timestamp, r = matrix), wallNowNs = nowNs)
                }

                Sensor.TYPE_PRESSURE -> engine.onPressure(
                    PressureSample(tNs = event.timestamp, hPa = event.values[0].toDouble()),
                )

                Sensor.TYPE_MAGNETIC_FIELD -> engine.onMagnetic(
                    MagSample(
                        tNs = event.timestamp,
                        bx = event.values[0].toDouble(),
                        by = event.values[1].toDouble(),
                        bz = event.values[2].toDouble(),
                    ),
                )

                else -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun start() {
        engine.start()
        forwardJob?.cancel()
        forwardJob = forwardScope.launch { engine.updates.collect { pipe.send(it) } }
        val thread = HandlerThread("echo-imu").apply { start() }
        imuThread = thread
        val handler = Handler(thread.looper)
        imuHandler = handler
        register(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME, handler)
        register(Sensor.TYPE_GAME_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME, handler)
        register(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME, handler)
        register(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL, handler)
    }

    private fun register(type: Int, delay: Int, handler: Handler) {
        sensorManager.getDefaultSensor(type)?.let { sensor ->
            sensorManager.registerListener(listener, sensor, delay, handler)
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(listener)
        forwardJob?.cancel()
        forwardJob = null
        imuThread?.quitSafely()
        imuThread = null
        imuHandler = null
        engine.stop()
    }
}
