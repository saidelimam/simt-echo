package app.simt.echo.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import app.simt.echo.capture.ImuSample
import app.simt.echo.capture.PdrEngine
import app.simt.echo.capture.PressureSample
import app.simt.echo.capture.RotSample
import kotlinx.coroutines.flow.Flow

/**
 * Android sensors → [PdrEngine] (slice W1-01).
 *
 * This is ECHO's shipping `MotionSource` (`echo_builtin_pdr`) per the
 * W0-01 decision: §6.1–6.2 is implemented natively in `capture/`,
 * following the step/heading approach copied from the solved track
 * code in `simt-app` — vendored, ECHO-owned, no dependency. Accelerometer + game rotation
 * vector at game rate (~50 Hz), barometer at UI rate; sensor events feed
 * the engine directly, in delivery order.
 */
class BuiltInPdrMotionSource(context: Context) : MotionSource {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val engine = PdrEngine()

    override val updates: Flow<MotionUpdate> = engine.updates

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

                else -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun start() {
        engine.start()
        register(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
        register(Sensor.TYPE_GAME_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME)
        register(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun register(type: Int, delay: Int) {
        sensorManager.getDefaultSensor(type)?.let { sensor ->
            sensorManager.registerListener(listener, sensor, delay)
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(listener)
        engine.stop()
    }
}
