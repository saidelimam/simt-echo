package app.simt.echo.ui.screens.share

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.components.QrCard
import app.simt.echo.ui.sigil.SigilColors
import app.simt.echo.ui.sigil.SigilGeometry
import app.simt.echo.ui.sigil.SigilPainter
import app.simt.echo.ui.sigil.color
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoQrMin
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrLogoPadding
import io.github.alexzhirkevich.qrose.options.QrLogoShape
import io.github.alexzhirkevich.qrose.options.QrOptions
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.circle
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

// Share handshake (plan W13-07, luminous-freeze §4/§6): the pairing QR
// wears the route's sigil at its centre — the receiver sees the same glyph
// when the route unfolds — with error correction High so the logo never
// costs a scan. Around the card, the beacon: while the offer is listening,
// rose echoes leave the card's edge (Rose = Share's social attention,
// Cosmos freeze §7); when the peer downloads, one success ring blooms out.
// Signals only: `listening` and `sent` come straight from ShareUiState.

/** The sigil covers this fraction of the QR side (≈ 5 % of the area, inside High's 30 % budget). */
const val SHARE_QR_LOGO_FRACTION = 0.22f

/** Void ink on the white card: the strongest scan contrast the palette has. */
private val QrInk = Color(0xFF120A14)

private const val BEACON_PERIOD_MS = 2_400
private val BEACON_REACH = 22.dp

@Composable
fun ShareQrCode(
    qrUri: String,
    listening: Boolean,
    sent: Boolean,
    modifier: Modifier = Modifier,
    sigil: SigilGeometry? = null,
    tier: CosmosMotionTier = cosmosMotionTier(),
) {
    val extras = EchoExtras
    val scheme = MaterialTheme.colorScheme
    val logo = remember(sigil, extras, scheme) {
        sigil?.let {
            SigilPainter(
                geometry = it,
                colors = SigilColors(
                    accent = it.accent.color(extras),
                    disc = Color(0xFF1D1220),
                    rim = Color(0x33FFFFFF),
                    ink = Color(0xFFFFF8F2),
                ),
            )
        }
    }
    val options = remember(logo) { shareQrOptions(logo) }
    val painter = rememberQrCodePainter(qrUri, options)
    val beacon = beaconPhase(active = listening && tier == CosmosMotionTier.FULL)
    val bloom = remember { Animatable(0f) }
    LaunchedEffect(sent, tier) {
        if (sent && tier == CosmosMotionTier.FULL) bloom.animateTo(1f, tween(900))
    }
    val social = extras.rose
    val success = extras.success
    val staticRing = listening && tier == CosmosMotionTier.REDUCED
    QrCard(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (staticRing) ring(0.35f, social.copy(alpha = 0.35f))
                if (beacon != null) {
                    val p = beacon.value
                    ring(p, social.copy(alpha = 0.5f * (1f - p)))
                    ring((p + 0.5f) % 1f, social.copy(alpha = 0.5f * (1f - (p + 0.5f) % 1f)))
                }
                val b = bloom.value
                if (b in 0.001f..0.999f) ring(b, success.copy(alpha = 0.8f * (1f - b)))
            },
    ) {
        Image(
            painter = painter,
            contentDescription = "Pairing QR code",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sizeIn(minWidth = EchoQrMin, minHeight = EchoQrMin)
                .testTag(ShareQrImageTag),
        )
    }
}

/**
 * The Share QR style: error correction High, rounded modules in void ink,
 * and — when given — the route's sigil as a circular centre logo covering
 * [SHARE_QR_LOGO_FRACTION] of the side. One definition for the screen and
 * the scan test.
 */
fun shareQrOptions(logo: Painter?): QrOptions = QrOptions {
    errorCorrectionLevel = QrErrorCorrectionLevel.High
    if (logo != null) {
        logo {
            painter = logo
            size = SHARE_QR_LOGO_FRACTION
            padding = QrLogoPadding.Natural(0.08f)
            shape = QrLogoShape.circle()
        }
    }
    shapes {
        darkPixel = QrPixelShape.roundCorners(0.45f)
        ball = QrBallShape.roundCorners(0.3f)
        frame = QrFrameShape.roundCorners(0.3f)
    }
    colors {
        dark = QrBrush.solid(QrInk)
    }
}

/** 0→1 every [BEACON_PERIOD_MS] while [active]; null otherwise (no loop runs). */
@Composable
private fun beaconPhase(active: Boolean): State<Float>? {
    if (!active) return null
    return rememberInfiniteTransition(label = "beacon").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BEACON_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "beaconPhase",
    )
}

/** One echo of the card's own outline, [progress] of the way out to [BEACON_REACH]. */
private fun DrawScope.ring(progress: Float, color: Color) {
    val grow = BEACON_REACH.toPx() * progress
    val corner = 28.dp.toPx() + grow
    drawRoundRect(
        color = color,
        topLeft = Offset(-grow, -grow),
        size = Size(size.width + 2 * grow, size.height + 2 * grow),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = 2.dp.toPx()),
    )
}
