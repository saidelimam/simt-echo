package app.simt.echo.ui.screens.share

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.simt.echo.Fixtures
import app.simt.echo.transfer.QrPayload
import app.simt.echo.ui.sigil.SigilColors
import app.simt.echo.ui.sigil.SigilPainter
import app.simt.echo.ui.sigil.routeSigil
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.alexzhirkevich.qrose.QrCodePainter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W13-07: the route's sigil at the QR centre never costs a scan. The real
 * Share options ([shareQrOptions]) are rendered with native graphics and
 * decoded by an independent decoder (ZXing), with and without the logo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], manifest = Config.NONE)
class ShareQrScanTest {

    /** A pairing URI at the realistic length of the V2 §5.4 format (≤ 200 chars). */
    private val uri = "simt-echo://r?v=1&h=192.168.43.17&p=48217&t=Qm9vdHN0cmFwVG9rZW4xMjM0NTY" +
        "&k=c2lndWxhci1rZXktbWF0ZXJpYWwtMzItYnl0ZXMhIQ&s=f3a9c2d1&n=Lobby%20to%20lift%20B"

    private fun render(painter: Painter, px: Int = 600, margin: Int = 48): IntArray {
        val bitmap = ImageBitmap(px, px)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
            drawRect(Color.White)
            translate(margin.toFloat(), margin.toFloat()) {
                with(painter) { draw(Size((px - 2 * margin).toFloat(), (px - 2 * margin).toFloat())) }
            }
        }
        val pixels = IntArray(px * px)
        bitmap.readPixels(pixels)
        return pixels
    }

    private fun decode(pixels: IntArray, px: Int = 600): String =
        MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(px, px, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text

    private fun sigilLogo(): Painter {
        val sigil = routeSigil(Fixtures.L_ROUTE)
        return SigilPainter(
            geometry = sigil,
            colors = SigilColors(
                accent = Color(0xFFFF8C42),
                disc = Color(0xFF1D1220),
                rim = Color(0x33FFFFFF),
                ink = Color(0xFFFFF8F2),
            ),
        )
    }

    @Test
    fun plainShareQrScans() {
        assertEquals(uri, decode(render(QrCodePainter(uri, shareQrOptions(logo = null)))))
    }

    @Test
    fun shareQrWithTheSigilLogoStillScans() {
        assertEquals(uri, decode(render(QrCodePainter(uri, shareQrOptions(logo = sigilLogo())))))
    }

    @Test
    fun aLongUnicodeNameStillScans() {
        // W21-03: the code must still read back with the longest name the
        // 40-character contract allows, with its sigil, not only ASCII.
        val name = "Café 圖書館 🏛️ 三樓梯間轉角 🚀 ok"
        val payload = QrPayload(
            host = "192.168.43.17",
            port = 48217,
            token = ByteArray(16) { (it + 1).toByte() },
            key = ByteArray(32) { (it + 1).toByte() },
            sha8Hex = "f3a9c2d1e0b74658",
            name = name,
        ).build()

        val decoded = decode(render(QrCodePainter(payload, shareQrOptions(logo = sigilLogo())), px = 900), px = 900)

        assertEquals(payload, decoded)
        assertEquals(name, QrPayload.parse(decoded).getOrThrow().name)
    }

    @Test
    fun logoStaysInsideTheFreezeBudget() {
        val area = SHARE_QR_LOGO_FRACTION * SHARE_QR_LOGO_FRACTION
        assertTrue(area <= 0.15f, "sigil covers ${area * 100} % of the code; the plan caps it at 15 %")
    }
}
