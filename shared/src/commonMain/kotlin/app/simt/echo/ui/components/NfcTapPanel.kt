package app.simt.echo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.transfer.NfcReadiness
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingRole
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.sigil.RouteSigil
import app.simt.echo.ui.sigil.SigilGeometry
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace24
import app.simt.echo.ui.copy.PairingCopy
import app.simt.echo.ui.theme.EchoSpace8

/** Stable tags for the tap surface and its recovery area (plan 0005 W21-03). */
const val NfcTapPanelTag = "NfcTapPanel"
const val NfcTapPanelStatusTag = "NfcTapPanelStatus"
const val PairingRecoveryTag = "PairingRecovery"

/**
 * The tap surface that stands in for the QR while NFC is selected
 * (plan 0005 §2).
 *
 * Two understated phone outlines with the route's sigil between them, a
 * status line that never claims more than the platform confirmed, and the
 * recovery actions this phone actually has. Outlines are decorative and
 * carry no content description; the status line is a polite live region so
 * "Ready to tap", a read and a failure are each announced once — byte
 * progress is announced by its own surface, never here.
 */
@Composable
fun NfcTapPanel(
    role: PairingRole,
    readiness: NfcReadiness,
    listening: Boolean,
    tapRead: Boolean,
    failure: PairingFailure?,
    onOpenNfcSettings: () -> Unit,
    onUseQrInstead: () -> Unit,
    modifier: Modifier = Modifier,
    /** A real reader connected and the invitation is arriving (R14-08). */
    readingTap: Boolean = false,
    /** In-place retry for a failure that stopped polling (the 20 s window). */
    onRetryTap: (() -> Unit)? = null,
    sigil: SigilGeometry? = null,
    tier: CosmosMotionTier = cosmosMotionTier(),
    /**
     * Shorter form for a phone that cannot do this role at all: no outlines,
     * no tap instructions, just why and what to do instead — so the QR that
     * *can* serve stays on screen rather than below a panel for a tap that
     * could never happen.
     */
    compact: Boolean = false,
) {
    val armed = listening && readiness == NfcReadiness.ARMED
    val pulse = armedEcho(active = armed && tier == CosmosMotionTier.FULL)
    val echoColor = EchoExtras.rose
    val staticRing = armed && tier == CosmosMotionTier.REDUCED

    CanopyPanel(
        variant = CanopyVariant.Sheet,
        modifier = modifier
            .fillMaxWidth()
            .testTag(NfcTapPanelTag)
            .drawBehind {
                if (staticRing) echoRing(1f, echoColor.copy(alpha = 0.25f))
                val phase = pulse?.value
                if (phase != null) echoRing(phase, echoColor.copy(alpha = 0.35f * (1f - phase)))
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace24),
            verticalArrangement = Arrangement.spacedBy(EchoSpace12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!compact) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhoneOutline()
                    androidx.compose.foundation.layout.Spacer(Modifier.width(EchoSpace16))
                    if (sigil != null) {
                        RouteSigil(geometry = sigil, sigilSize = 56.dp)
                    } else {
                        Box(Modifier.size(56.dp))
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(EchoSpace16))
                    PhoneOutline()
                }
            }
            val status = statusCopy(role, readiness, tapRead, failure, readingTap)
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .testTag(NfcTapPanelStatusTag)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!compact) {
                Text(
                    text = instructionCopy(role),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val failureCopy = failure?.let { pairingFailureCopy(role, it) }
            if (failureCopy != null) {
                Text(
                    text = failureCopy,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val canSettings = readiness == NfcReadiness.DISABLED
            val canSwitch = readiness == NfcReadiness.UNAVAILABLE || canSettings
            // A failure that stopped polling is recoverable in this method:
            // retrying must not require a trip through another screen.
            val retry = onRetryTap.takeIf { failure != null && !listening }
            if (canSettings || canSwitch || retry != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PairingRecoveryTag),
                    verticalArrangement = Arrangement.spacedBy(EchoSpace8),
                ) {
                    if (canSettings) {
                        CosmosSecondaryButton(
                            label = PairingCopy.OPEN_NFC_SETTINGS,
                            onClick = onOpenNfcSettings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    retry?.let {
                        CosmosPrimaryButton(
                            label = PairingCopy.TRY_TAP_AGAIN,
                            onClick = it,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (canSwitch) {
                        CosmosPrimaryButton(
                            label = PairingCopy.USE_QR_INSTEAD,
                            onClick = onUseQrInstead,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (retry != null) {
                        CosmosSecondaryButton(
                            label = PairingCopy.USE_QR_INSTEAD,
                            onClick = onUseQrInstead,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun statusCopy(
    role: PairingRole,
    readiness: NfcReadiness,
    tapRead: Boolean,
    failure: PairingFailure?,
    readingTap: Boolean = false,
): String = when {
    // The idle window closed without a tap: say so above its retry.
    failure == PairingFailure.Timeout -> PairingCopy.TAP_TIMED_OUT

    readiness == NfcReadiness.UNAVAILABLE ->
        if (role == PairingRole.SHARE) PairingCopy.SHARE_BY_QR else PairingCopy.NFC_UNAVAILABLE

    readiness == NfcReadiness.DISABLED -> PairingCopy.TURN_ON_NFC

    // Contact happened: the invitation is arriving, so the honest status is
    // what to do with the phones, not "ready" (R14-08).
    readingTap && role == PairingRole.RECEIVE -> PairingCopy.RECEIVE_HOLD_STILL

    // Details supplied, route not received: never "sent".
    tapRead && role == PairingRole.SHARE -> PairingCopy.SHARE_TAP_READ

    readiness == NfcReadiness.ARMED && role == PairingRole.SHARE -> PairingCopy.SHARE_READY

    readiness == NfcReadiness.ARMED -> PairingCopy.RECEIVE_READY

    role == PairingRole.SHARE -> PairingCopy.SHARE_PREPARING

    else -> PairingCopy.RECEIVE_PREPARING
}

private fun instructionCopy(role: PairingRole): String =
    if (role == PairingRole.SHARE) PairingCopy.SHARE_TAP_INSTRUCTION else PairingCopy.RECEIVE_TAP_INSTRUCTION

/**
 * Typed failure → human copy (plan 0005 §2). Verification never becomes
 * network advice, and a wrong tag never becomes "hold still".
 */
fun pairingFailureCopy(role: PairingRole, failure: PairingFailure): String = when (failure) {
    PairingFailure.Unsupported ->
        if (role == PairingRole.SHARE) PairingCopy.SHARE_BY_QR else PairingCopy.NFC_UNAVAILABLE

    PairingFailure.Disabled -> PairingCopy.TURN_ON_NFC
    PairingFailure.Interrupted -> PairingCopy.TAP_INTERRUPTED
    PairingFailure.Timeout -> PairingCopy.NO_TAP_YET
    PairingFailure.InvalidInvitation -> PairingCopy.NOT_AN_ECHO_SHARE
    PairingFailure.OfferExpired -> PairingCopy.OFFER_EXPIRED
    PairingFailure.Network -> PairingCopy.TAP_NETWORK_FAILED
    PairingFailure.Verification -> PairingCopy.UNVERIFIED
    PairingFailure.Internal -> PairingCopy.SOMETHING_WRONG
}

/** One decorative phone outline: two rounded rects, no semantics at all. */
@Composable
private fun PhoneOutline() {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.onSurface.copy(alpha = 0.55f)
    val screen = scheme.primary.copy(alpha = 0.25f)
    Canvas(
        modifier = Modifier
            .size(width = 44.dp, height = 76.dp)
            .clearAndSetSemantics {},
    ) {
        val radius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = outline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = radius,
            style = Stroke(width = stroke),
        )
        val inset = 7.dp.toPx()
        drawRoundRect(
            color = screen,
            topLeft = Offset(inset, inset * 1.8f),
            size = Size(size.width - 2 * inset, size.height - inset * 3.4f),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
        )
    }
}

/** 0→1 while armed in FULL motion; null otherwise, so no loop ever runs. */
@Composable
private fun armedEcho(active: Boolean): State<Float>? {
    if (!active) return null
    return rememberInfiniteTransition(label = "nfcEcho").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_200, easing = LinearEasing), RepeatMode.Restart),
        label = "nfcEchoPhase",
    )
}

/** One soft echo just outside the panel's own outline. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.echoRing(
    progress: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val grow = 10.dp.toPx() * progress
    drawRoundRect(
        color = color,
        topLeft = Offset(-grow, -grow),
        size = Size(size.width + 2 * grow, size.height + 2 * grow),
        cornerRadius = CornerRadius(28.dp.toPx() + grow, 28.dp.toPx() + grow),
        style = Stroke(width = 2.dp.toPx()),
    )
}
