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
import app.simt.echo.transfer.ExportSummary
import app.simt.echo.transfer.LANDMARK_TEXT_DISCLOSURE
import app.simt.echo.transfer.START_PHOTO_ITEM_ID
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.PillButton
import app.simt.echo.ui.components.QrCard
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.copy.UNTITLED_WALK
import app.simt.echo.ui.copy.createdCopy
import app.simt.echo.ui.copy.expiryCopy
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
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Share", style = MaterialTheme.typography.titleLarge)
        ExportPreview(
            items = ui.exportItems,
            selection = ui.selection,
            summary = ui.summary,
            onToggle = viewModel::toggleAsset,
            onExpiry = viewModel::setExpiry,
            modifier = Modifier.fillMaxWidth(),
        )
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
        if (error != null && qrUri == null) {
            EchoButton(
                text = "Try again",
                onClick = viewModel::retryOffer,
                modifier = Modifier.fillMaxWidth(),
            )
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
    summary: ExportSummary?,
    onToggle: (String, Boolean) -> Unit,
    onExpiry: (ExpiryOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "What others receive", style = MaterialTheme.typography.titleMedium)
        if (summary != null) {
            Text(
                text = "${summary.routeName.ifEmpty { UNTITLED_WALK }} · by ${summary.creatorLabel} · " +
                    createdCopy(summary.createdAtEpochMs),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${summary.nodeCount} nodes · ${summary.pathPointCount} path points · ${summary.totalLengthM} m",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = "Start: ${summary.startDescription}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (summary.hasAlignmentHint) "Alignment hint included" else "No alignment hint",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (summary.hasStartPhoto) "Start photo included" else "Start photo not included",
                style = MaterialTheme.typography.bodyMedium,
            )
            summary.landmarks.forEach { landmark ->
                Text(
                    text = "Landmark: ${landmark.title} — ${landmark.cue}" +
                        (landmark.signText?.let { " · sign: $it" } ?: "") +
                        when {
                            !landmark.hasCrop -> " · text only"
                            landmark.cropIncluded -> " · photo included"
                            else -> " · photo off, text still included"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(text = LANDMARK_TEXT_DISCLOSURE, style = MaterialTheme.typography.bodyMedium)
            summary.ai?.let { ai ->
                Text(
                    text = "AI suggestions included (${ai.scoutModel} / ${ai.groundModel})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = expiryCopy(summary.expiresAtEpochMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
        val expiryOptions = buildList {
            if (selection.expiry == ExpiryOption.KEEP_STORED) add(ExpiryOption.KEEP_STORED)
            add(ExpiryOption.NONE)
            add(ExpiryOption.HOURS_24)
            add(ExpiryOption.DAYS_7)
        }
        expiryOptions.forEach { option ->
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
    ExpiryOption.KEEP_STORED -> "Keep existing expiry"
}
