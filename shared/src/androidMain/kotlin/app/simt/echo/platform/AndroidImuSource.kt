package app.simt.echo.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.simt.echo.capture.GyroSample
import app.simt.echo.capture.ImuSample
import app.simt.echo.capture.MagSample
import app.simt.echo.capture.PdrEngine
import app.simt.echo.capture.StepCalibratable
import app.simt.echo.capture.PressureSample
import app.simt.echo.capture.RotSample
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.visual.VisualImuHistory
import app.simt.echo.capture.visual.VisualMotionConsumer
import app.simt.echo.capture.visual.VisualMotionFrame
import app.simt.echo.capture.visual.VisualMotionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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
 *
 * Lifecycle: [start] is idempotent (a second call tears the first run down
 * before beginning), the forwarder subscribes to the engine before any
 * sensor is registered (the engine's flow does not replay, so a late
 * subscriber would lose the first samples), and [stop] drains the channel
 * so stale updates never reach the next run.
 */
class BuiltInPdrMotionSource internal constructor(
    context: Context,
    private val engine: PdrEngine,
) : MotionSource, StepCalibratable, VisualMotionSource {

    constructor(context: Context) : this(context, PdrEngine())

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var imuThread: HandlerThread? = null

    private val pipe = Channel<MotionUpdate>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val updates: Flow<MotionUpdate> = pipe.receiveAsFlow()

    private val forwardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class VisualSession(val input: VisualImuHistory, val consumer: VisualMotionConsumer)
    @Volatile private var visualSession: VisualSession? = null

    override val visualAssistActive: Boolean
        get() = visualSession?.consumer?.hasUsableFrame == true

    override fun attachVisual(tap: FrameTap, enabled: Boolean, intervalNs: Long): Boolean {
        detachVisual()
        engine.visualAssist = enabled
        if (!enabled) return false
        val input = VisualImuHistory()
        val consumer = VisualMotionConsumer(
            tap,
            input,
            intervalNs = intervalNs,
            // Frame-time delivery is the ordering that makes the step gate
            // work: the engine judges a candidate on the IMU thread, so it
            // must already hold this frame. Feeding the engine only from
            // drainVisual (after the step was emitted) left the gate blind.
            onEgo = { ego, tNs, reason -> engine.onVisualEgo(ego, tNs, reason = reason) },
        )
        if (!consumer.start()) {
            engine.visualAssist = false
            return false
        }
        visualSession = VisualSession(input, consumer)
        return true
    }

    override fun detachVisual() {
        val session = visualSession
        visualSession = null
        engine.visualAssist = false
        session?.consumer?.stop()
    }

    /**
     * The newest ego at or before [tNs], for presentation and capture readers
     * (the live frame, the parallax profiler). It deliberately does NOT feed
     * [PdrEngine.onVisualEgo]: the consumer already delivered every ego at
     * frame time, and a second delivery would double-count one observation.
     */
    override fun drainVisual(tNs: Long): VisualMotionFrame? =
        visualSession?.consumer?.drain(tNs)

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
                Sensor.TYPE_ACCELEROMETER -> {
                    engine.onAccelerometer(
                        ImuSample(
                            tNs = event.timestamp,
                            ax = event.values[0].toDouble(),
                            ay = event.values[1].toDouble(),
                            az = event.values[2].toDouble(),
                        ),
                        wallNowNs = nowNs,
                    )
                    visualSession?.input?.onAccelerometer(
                        event.timestamp, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(),
                    )
                }

                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                    val matrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    engine.onRotation(RotSample(tNs = event.timestamp, r = matrix), wallNowNs = nowNs)
                    visualSession?.input?.onRotation(event.timestamp, matrix)
                }

                Sensor.TYPE_GYROSCOPE -> engine.onGyro(
                    GyroSample(
                        tNs = event.timestamp,
                        wx = event.values[0].toDouble(),
                        wy = event.values[1].toDouble(),
                        wz = event.values[2].toDouble(),
                    ),
                    wallNowNs = nowNs,
                )

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
        teardown()
        engine.start()
        // UNDISPATCHED: subscribe on this thread, before any sensor can
        // produce a sample, instead of whenever Default gets scheduled.
        forwardScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.updates.collect { pipe.send(it) }
        }
        val thread = HandlerThread("echo-imu").apply { start() }
        imuThread = thread
        val handler = Handler(thread.looper)
        register(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME, handler)
        register(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME, handler)
        headingSensorType { sensorManager.getDefaultSensor(it) != null }
            ?.let { register(it, SensorManager.SENSOR_DELAY_GAME, handler) }
        register(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME, handler)
        register(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL, handler)
    }

    private fun register(type: Int, delay: Int, handler: Handler) {
        sensorManager.getDefaultSensor(type)?.let { sensor ->
            sensorManager.registerListener(listener, sensor, delay, handler)
        }
    }

    override fun stop() {
        teardown()
        engine.stop()
        detachVisual()
    }

    /** Ends any previous run: sensors, IMU thread, forwarder, buffered updates. */
    private fun teardown() {
        sensorManager.unregisterListener(listener)
        imuThread?.quitSafely()
        imuThread = null
        forwardScope.coroutineContext.cancelChildren()
        while (pipe.tryReceive().getOrNull() != null) { /* drop the stale update */ }
    }
}

/**
 * The sensor heading comes from, best first: the gyro-fused game rotation
 * vector, then the rotation vector, then the geomagnetic one, which fuses
 * only accelerometer and magnetometer. Phones without a gyroscope (the
 * Galaxy A16 among them) offer only the last; without it their yaw never
 * left 0 and no turn was ever seen. Null when there is no heading sensor.
 */
internal fun headingSensorType(has: (Int) -> Boolean): Int? = listOf(
    Sensor.TYPE_GAME_ROTATION_VECTOR,
    Sensor.TYPE_ROTATION_VECTOR,
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
).firstOrNull(has)
