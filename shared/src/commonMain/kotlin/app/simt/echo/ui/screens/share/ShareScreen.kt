package app.simt.echo.ui.screens.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.Haptics
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.QrCard
import app.simt.echo.ui.components.StatusChip
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * Share screen (plan §8.3 / W2-04): white QR card with the offer URI,
 * "Waiting for the other phone…" until the peer downloads, then "Sent ✓".
 * Serving stops when the screen leaves ("Share as file" is W2-07).
 */
@Composable
fun ShareScreen(
    routeId: String,
    service: TransferService,
    haptics: Haptics,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(routeId) { ShareViewModel(routeId, service, haptics) }
    val ui by viewModel.ui.collectAsState()
    DisposableEffect(service) {
        onDispose { service.stopOffer() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Share", style = MaterialTheme.typography.titleLarge)
        val qrUri = ui.qrUri
        if (qrUri != null) {
            QrCard(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = rememberQrCodePainter(qrUri),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val error = ui.error
        when {
            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )

            ui.sent -> StatusChip(text = "Sent ✓")
            qrUri != null -> StatusChip(text = "Waiting for the other phone…")
            else -> StatusChip(text = "Starting offer…")
        }
        if (ui.relayAvailable && !ui.relayOffered && qrUri != null) {
            EchoButton(
                text = "Use internet relay",
                onClick = viewModel::useInternetRelay,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ui.relayOffered) {
            StatusChip(text = "Relay code in QR — works off this Wi-Fi")
        }
        EchoButton(text = "Back", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}
