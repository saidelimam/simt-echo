package app.simt.echo.ui.screens.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.Haptics
import app.simt.echo.transfer.ExpiryOption
import app.simt.echo.transfer.ExportItem
import app.simt.echo.transfer.ExportSelection
import app.simt.echo.transfer.ExportSummary
import app.simt.echo.transfer.LANDMARK_TEXT_DISCLOSURE
import app.simt.echo.transfer.START_PHOTO_ITEM_ID
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosTertiaryButton
import app.simt.echo.ui.components.QrCard
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.copy.UNTITLED_WALK
import app.simt.echo.ui.copy.createdCopy
import app.simt.echo.ui.copy.expiryCopy
import app.simt.echo.ui.theme.EchoQrMin
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace24
import app.simt.echo.ui.theme.EchoSpace8
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

const val ShareQrTag = "ShareQr"
const val ShareQrImageTag = "ShareQrImage"
const val ShareExportSectionTag = "ShareExportSection"
const val ShareExportToggleTag = "ShareExportToggle"

/**
 * Share screen in Cosmos (plan §8.3 / W2-04, slice W8-09): the QR is the
 * first read — [QrCard] on its light surface at the frozen ≥260 dp size —
 * with the export preview collapsed into a canopy section below it.
 * "Waiting for the other phone…" until the peer downloads, then "Sent ✓".
 * "Share as file" (W2-07) raises the plain-file privacy warning first and
 * only writes the `.echo` container after confirmation. Serving stops when
 * the screen leaves.
 *
 * Presentation only: offer/selection/relay/file ViewModel behaviour is
 * untouched — only the read order and surfaces moved.
 *
 * W8-05: the shell owns the Share heading and the Close chrome — [onClose]
 * stays for hosts that render this screen outside the shell.
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
            .verticalScroll(rememberScrollState())
            .padding(EchoSpace24),
        verticalArrangement = Arrangement.spacedBy(EchoSpace16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // First read: the QR on its light surface, at the frozen size.
        val qrUri = ui.qrUri
        if (qrUri != null) {
            QrCard(modifier = Modifier
                .fillMaxWidth()
                .testTag(ShareQrTag)) {
                Image(
                    painter = rememberQrCodePainter(qrUri),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .sizeIn(minWidth = EchoQrMin, minHeight = EchoQrMin)
                        .testTag(ShareQrImageTag),
                )
            }
        }
        // Collapsed by default: the preview no longer buries the QR.
        ExportSection(
            items = ui.exportItems,
            selection = ui.selection,
            summary = ui.summary,
            onToggle = viewModel::toggleAsset,
            onExpiry = viewModel::setExpiry,
            modifier = Modifier.fillMaxWidth(),
        )
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
            CosmosPrimaryButton(
                label = "Try again",
                onClick = viewModel::retryOffer,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ui.relayAvailable && !ui.relayOffered && qrUri != null) {
            CosmosPrimaryButton(
                label = "Use internet relay",
                onClick = viewModel::useInternetRelay,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ui.relayOffered) {
            StatusChip(text = "Relay code in QR — works off this Wi-Fi")
        }
        if (ui.fileWarning) {
            CanopyPanel(
                variant = CanopyVariant.Floating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(EchoSpace16),
                    verticalArrangement = Arrangement.spacedBy(EchoSpace8),
                ) {
                    Text(
                        text = "Share as file?",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = FILE_WARNING_COPY,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    CosmosPrimaryButton(
                        label = "Share anyway",
                        onClick = viewModel::confirmFileShare,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CosmosSecondaryButton(
                        label = "Cancel",
                        onClick = viewModel::dismissFileShare,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (ui.fileShared) {
            StatusChip(text = "File shared ✓")
        }
        if (!ui.fileShared) {
            CosmosSecondaryButton(
                label = "Share as file",
                onClick = viewModel::requestFileShare,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Export preview as a collapsed canopy section (plan W3-08): one honest
 * summary line while collapsed, the full asset toggles + link-expiry
 * selector when opened. Inline, not a dialog.
 */
@Composable
private fun ExportSection(
    items: List<ExportItem>,
    selection: ExportSelection,
    summary: ExportSummary?,
    onToggle: (String, Boolean) -> Unit,
    onExpiry: (ExpiryOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    CanopyPanel(
        variant = CanopyVariant.Flat,
        modifier = modifier.testTag(ShareExportSectionTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace16),
            verticalArrangement = Arrangement.spacedBy(EchoSpace8),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "What others receive",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                CosmosTertiaryButton(
                    label = if (open) "Hide details" else "Show details",
                    onClick = { open = !open },
                    modifier = Modifier.testTag(ShareExportToggleTag),
                )
            }
            if (!open) {
                Text(
                    text = collapsedSummary(summary, items.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExportPreview(
                    items = items,
                    selection = selection,
                    summary = summary,
                    onToggle = onToggle,
                    onExpiry = onExpiry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun collapsedSummary(summary: ExportSummary?, itemCount: Int): String {
    if (summary == null) return if (itemCount == 0) "Loading export…" else "$itemCount items"
    val name = summary.routeName.ifEmpty { UNTITLED_WALK }
    return "$name · ${summary.nodeCount} nodes · ${summary.landmarkCountText()}"
}

private fun ExportSummary.landmarkCountText(): String {
    val n = landmarks.size
    return if (n == 1) "1 landmark" else "$n landmarks"
}

/**
 * Export preview rows (plan W3-08): every asset the bundle will hold,
 * with a toggle each, plus the link-expiry selector.
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
        verticalArrangement = Arrangement.spacedBy(EchoSpace8),
    ) {
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
                horizontalArrangement = Arrangement.spacedBy(EchoSpace8),
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
        Text(
            text = "Link expires",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val expiryOptions = buildList {
            if (selection.expiry == ExpiryOption.KEEP_STORED) add(ExpiryOption.KEEP_STORED)
            add(ExpiryOption.NONE)
            add(ExpiryOption.HOURS_24)
            add(ExpiryOption.DAYS_7)
        }
        expiryOptions.forEach { option ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EchoSpace8),
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
