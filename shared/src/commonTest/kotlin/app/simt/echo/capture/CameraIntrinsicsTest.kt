package app.simt.echo.capture

import kotlin.math.PI
import kotlin.math.atan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CameraIntrinsicsTest {
    @Test
    fun defaultIsExplicitly65DegreeHorizontalFov() {
        val intrinsics = CameraIntrinsics.defaultFor(240, 180)
        assertEquals(IntrinsicsSource.DEFAULT, intrinsics.source)
        assertEquals(65.0, 2.0 * atan(240 / (2.0 * intrinsics.fPx)) * 180.0 / PI, 1e-10)
        assertEquals(119.5, intrinsics.cx)
        assertEquals(89.5, intrinsics.cy)
        assertFailsWith<IllegalArgumentException> { CameraIntrinsics.defaultFor(0, 180) }
    }

    @Test
    fun physicalOpticsScaleToDecimatedPlaneAndAccountForAspectCrop() {
        val optics = CameraOptics(4.0, 6.0, 4000, 3000)
        val native = optics.forPlane(240, 180)
        assertEquals(IntrinsicsSource.CHARACTERISTICS, native.source)
        assertEquals(160.0, native.fPx, 1e-10)
        assertEquals(80.0, optics.forPlane(120, 90).fPx, 1e-10)
        assertEquals(160.0, optics.forPlane(240, 135).fPx, 1e-10)
        assertEquals(IntrinsicsSource.DEFAULT, optics.copy(sensorWidthMm = 0.0).forPlane(240, 180).source)
        assertEquals(IntrinsicsSource.DEFAULT, optics.copy(focalLengthMm = Double.NaN).forPlane(240, 180).source)
    }
}