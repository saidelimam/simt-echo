package app.simt.echo.platform

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import app.simt.echo.capture.FrameConsumer
import app.simt.echo.capture.FrameRateGovernor
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.IntrinsicsSource
import app.simt.echo.capture.TimestampAlignment
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTapSourceTest {
    private class Image(val tNs: Long) {
        var closed = 0
        var planeReads = 0
        private val plane = object : ImageProxy.PlaneProxy {
            override fun getBuffer(): ByteBuffer = ByteBuffer.allocate(64 * 64)
            override fun getRowStride(): Int = 64
            override fun getPixelStride(): Int = 1
        }
        private val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) {
                "getTimestamp" -> tNs
                else -> error("unexpected ImageInfo.${method.name}")
            }
        } as ImageInfo
        val proxy = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getWidth", "getHeight" -> 64
                "getImageInfo" -> info
                "getPlanes" -> { planeReads++; arrayOf(plane) }
                "close" -> { closed++; null }
                else -> error("unexpected ImageProxy.${method.name}")
            }
        } as ImageProxy
    }

    @Test
    fun uninterestedConsumersSkipDecimationAndAlwaysCloseImage() {
        val source = FrameTapSource(FrameRateGovernor(1L))
        source.addConsumer(object : FrameConsumer {
            override fun wantsFrame(tNs: Long) = false
            override fun onFrame(sample: FrameSample) = error("not requested")
        })
        val image = Image(1L)
        source.analyzer.analyze(image.proxy)
        assertEquals(0, image.planeReads)
        assertEquals(1, image.closed)
    }

    @Test
    fun nonRealtimeSourceCannotPublishAUsableVisualChannel() {
        val source = FrameTapSource(FrameRateGovernor(1L), alignmentNow = { TimestampAlignment(false, 0L) })
        source.setConsumer { error("untrusted clock") }
        val image = Image(1L)
        source.analyzer.analyze(image.proxy)
        assertEquals(0, image.planeReads)
        assertEquals(1, image.closed)
    }

    @Test
    fun schedulerCannotPauseRecordingButCanPauseAfterLedgerDetaches() {
        val source = FrameTapSource(FrameRateGovernor(125L), recordingIntervalNs = 125L)
        var ledgerCalls = 0
        var guestCalls = 0
        var aligned = false
        var provenance: IntrinsicsSource? = null
        source.setConsumer {
            ledgerCalls++
            aligned = it.visualClockAligned
            provenance = it.intrinsics?.source
        }
        source.addConsumer(FrameConsumer { guestCalls++ })
        source.setBaseInterval(0L)
        val image = Image(1L)
        source.analyzer.analyze(image.proxy)
        assertEquals(1, ledgerCalls)
        assertEquals(1, guestCalls)
        assertTrue(aligned)
        assertEquals(IntrinsicsSource.DEFAULT, provenance)
        assertEquals(1, image.closed)
        source.setConsumer(null)
        val paused = Image(1_000L)
        source.analyzer.analyze(paused.proxy)
        assertEquals(0, paused.planeReads)
        assertEquals(1, guestCalls)
        assertEquals(1, paused.closed)
    }
}