package app.simt.echo.ui.screens.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.Haptics
import app.simt.echo.transfer.ExpiryOption
import app.simt.echo.transfer.ExportItem
import app.simt.echo.transfer.ExportSelection
import app.simt.echo.transfer.START_PHOTO_ITEM_ID
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.PillButton
import app.simt.echo.ui.components.QrCard
import app.simt.echo.ui.components.StatusChip
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * Share screen (plan §8.3 / W2-04): white QR card with the offer URI,
 * "Waiting for the other phone…" until the peer downloads, then "Sent ✓".
 * "Share as file" (W2-07) raises the plain-file privacy warning first and
 * only writes the `.echo` container after confirmation. Serving stops when
 * the screen leaves.
 */
@Composable
fun ShareScreen(
    routeId: String,
    service: TransferService,
    haptics: Haptics,
    sharing: FileSharing,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(routeId, sharing) {
        ShareViewModel(routeId, service, haptics, sharing)
    }
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
        if (ui.exportItems.isNotEmpty()) {
            ExportPreview(
                items = ui.exportItems,
                selection = ui.selection,
                onToggle = viewModel::toggleAsset,
                onExpiry = viewModel::setExpiry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
        if (ui.fileWarning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Share as file?",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = FILE_WARNING_COPY,
                    style = MaterialTheme.typography.bodyLarge,
                )
                EchoButton(
                    text = "Share anyway",
                    onClick = viewModel::confirmFileShare,
                    modifier = Modifier.fillMaxWidth(),
                )
                PillButton(
                    text = "Cancel",
                    onClick = viewModel::dismissFileShare,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (ui.fileShared) {
            StatusChip(text = "File shared ✓")
        }
        if (!ui.fileShared) {
            PillButton(
                text = "Share as file",
                onClick = viewModel::requestFileShare,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EchoButton(text = "Back", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Export preview sheet (plan W3-08): every asset the bundle will hold,
 * with a toggle each, plus the link-expiry selector. Inline, not a
 * dialog — it is the first thing on the Share screen, before the QR.
 */
@Composable
private fun ExportPreview(
    items: List<ExportItem>,
    selection: ExportSelection,
    onToggle: (String, Boolean) -> Unit,
    onExpiry: (ExpiryOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "What others receive", style = MaterialTheme.typography.titleMedium)
        items.forEach { item ->
            val included = if (item.id == START_PHOTO_ITEM_ID) {
                selection.includeStartPhoto
            } else {
                item.id !in selection.excludedCropIds
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
                    if (item.privacyRisk) {
                        Text(
                            text = "May show people or screens — off by default",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Switch(checked = included, onCheckedChange = { onToggle(item.id, it) })
            }
        }
        Text(text = "Link expires", style = MaterialTheme.typography.titleMedium)
        listOf(ExpiryOption.NONE, ExpiryOption.HOURS_24, ExpiryOption.DAYS_7).forEach { option ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selection.expiry == option,
                    onClick = { onExpiry(option) },
                )
                Text(text = expiryLabel(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(text = RAW_VIDEO_NOTE, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun expiryLabel(option: ExpiryOption): String = when (option) {
    ExpiryOption.NONE -> "Never"
    ExpiryOption.HOURS_24 -> "After 24 hours"
    ExpiryOption.DAYS_7 -> "After 7 days"
}
