package app.simt.echo.platform

import android.hardware.Sensor
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The heading sensor is the best one the phone has, and a gyro-less phone still gets one. */
class HeadingSensorTypeTest {

    @Test
    fun theGyroFusedVectorWinsWhenThePhoneHasIt() {
        val all = setOf(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        assertEquals(Sensor.TYPE_GAME_ROTATION_VECTOR, headingSensorType { it in all })
    }

    @Test
    fun withoutAGameVectorTheRotationVectorIsNext() {
        val has = setOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        assertEquals(Sensor.TYPE_ROTATION_VECTOR, headingSensorType { it in has })
    }

    @Test
    fun aPhoneWithoutAGyroscopeFallsBackToTheGeomagneticVector() {
        assertEquals(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, headingSensorType { it == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR })
    }

    @Test
    fun noRotationSensorAtAllMeansNoHeading() {
        assertNull(headingSensorType { false })
    }
}
