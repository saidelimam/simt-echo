package app.simt.echo.ui.screens.receive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.CameraPermissionGate
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.NfcPairingController
import app.simt.echo.platform.QrScannerView
import app.simt.echo.platform.UnavailableNfcPairingController
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.transfer.PairingFailure
import app.simt.echo.transfer.PairingMethod
import app.simt.echo.transfer.PairingRole
import app.simt.echo.transfer.PairingSource
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.components.CosmosErrorState
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosSuccessState
import app.simt.echo.ui.components.NfcTapPanel
import app.simt.echo.ui.components.PairingLifecycle
import app.simt.echo.ui.components.PairingMethodSelector
import app.simt.echo.ui.components.TransferParticles
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.components.pairingFailureCopy
import app.simt.echo.ui.copy.PairingCopy
import app.simt.echo.ui.copy.UNTITLED_WALK
import app.simt.echo.ui.copy.expiryCopy
import app.simt.echo.ui.sigil.RouteSigil
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace24
import app.simt.echo.ui.theme.EchoSpace8

const val ReceiveScannerTag = "ReceiveScanner"
const val ReceiveReticleTag = "ReceiveReticle"
const val ReceiveImportSheetTag = "ReceiveImportSheet"

/**
 * Receive screen in Cosmos (plan §8.3 / W2-04, slices W8-09 and W21-03).
 *
 * The method selector sits above everything — including the camera gate, so
 * it stays available when the camera is denied. QR is the default and keeps
 * the full-bleed scanner with the reticle motif; choosing NFC disposes the
 * scanner and puts the tap panel in its place, with no camera permission
 * involved at all. Either source ends in the same verified import canopy and
 * the same explicit Save.
 *
 * W8-05: the shell owns the Receive heading and the Close chrome.
 */
@Composable
fun ReceiveScreen(
    service: TransferService,
    sharing: FileSharing,
    onImported: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Route-scoped NFC controller; null keeps the honest unavailable one. */
    pairing: NfcPairingController? = null,
    /** Opens the system NFC settings screen (never toggles the adapter). */
    onOpenNfcSettings: () -> Unit = {},
    /** Contact cues for a real tap; the platform's own by default. */
    haptics: Haptics = rememberHaptics(),
) {
    val controller = pairing ?: remember { UnavailableNfcPairingController() }
    val viewModel = remember(service, sharing, controller, haptics) {
        ReceiveViewModel(service, sharing, haptics = haptics, pairing = controller)
    }
    val ui by viewModel.ui.collectAsState()
    PairingLifecycle(
        onResume = viewModel::onResumed,
        onPause = viewModel::onPaused,
        onDispose = viewModel::close,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EchoSpace24),
        verticalArrangement = Arrangement.spacedBy(EchoSpace16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PairingMethodSelector(
            role = PairingRole.RECEIVE,
            selected = ui.method,
            enabled = !ui.methodLocked,
            onSelect = viewModel::selectMethod,
            modifier = Modifier.fillMaxWidth(),
        )
        val savedId = ui.savedRouteId
        val preview = ui.preview
        when {
            savedId != null -> CosmosSuccessState(
                title = "Received ✓",
                actionLabel = "Open route",
                onAction = { onImported(savedId) },
            )

            preview != null -> Column(
                verticalArrangement = Arrangement.spacedBy(EchoSpace8),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The selected method stays visible above the sheet: what
                // arrived is indented under how it arrived.
                MethodIndicator(method = ui.method)
                ImportSheet(
                    preview = preview,
                    onSave = { viewModel.save(onImported) },
                    onCancel = viewModel::cancel,
                )
            }

            else -> {
                if (!ui.fetching) {
                    when (ui.method) {
                        PairingMethod.QR -> {
                            // The gate wraps only QR capture: NFC never asks
                            // for the camera.
                            CameraPermissionGate {
                                ReceiveScannerFrame(modifier = Modifier.fillMaxWidth()) {
                                    key(ui.generation) {
                                        QrScannerView(
                                            onScanned = viewModel::onScanned,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = PairingCopy.RECEIVE_SCAN_PROMPT,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        PairingMethod.NFC -> NfcTapPanel(
                            role = PairingRole.RECEIVE,
                            readiness = ui.nfcReadiness,
                            listening = ui.nfcListening,
                            tapRead = false,
                            failure = ui.pairingFailure,
                            onOpenNfcSettings = onOpenNfcSettings,
                            onUseQrInstead = { viewModel.selectMethod(PairingMethod.QR) },
                            modifier = Modifier.fillMaxWidth(),
                            readingTap = ui.readingTap,
                            onRetryTap = viewModel::rescan,
                        )
                    }
                }
                if (ui.method == PairingMethod.QR) {
                    Text(
                        text = PairingCopy.RECEIVE_CHOOSE_NFC,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.fetching) {
                    // A tap already handed over the invitation: the route is
                    // coming over the LAN, so the phones need not stay
                    // together any more (R14-08).
                    if (ui.lastSource == PairingSource.NFC) {
                        Text(
                            text = PairingCopy.RECEIVE_CONNECTED_SEPARATE,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    // The live-region text below already announces fetching;
                    // an unlabelled spinner would only add an empty node.
                    CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
                    Text(
                        text = PairingCopy.RECEIVE_FETCHING,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    // W13-14: particle flow between the two phones, driven
                    // only by measured bytes — frozen until bytes arrive.
                    val progress = ui.progress
                    if (progress != null) {
                        TransferParticles(
                            progress = progress,
                            bytesPerSecond = ui.bytesPerSecond,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val error = ui.error
                if (error != null) {
                    CosmosErrorState(
                        message = error,
                        actionLabel = PairingCopy.RECEIVE_SCAN_AGAIN,
                        onAction = viewModel::rescan,
                    )
                } else if (ui.method == PairingMethod.QR) {
                    ui.pairingFailure?.let { failure ->
                        QrFailureState(failure = failure, onRescan = viewModel::rescan)
                    }
                }
                CosmosSecondaryButton(
                    label = PairingCopy.RECEIVE_IMPORT_FILE,
                    onClick = viewModel::importFile,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = PairingCopy.SAME_NETWORK,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A rejected QR invitation (R14-06): a corrupt or foreign code keeps its own
 * typed copy and one action back. "Scan again" advances the generation, which
 * remounts the scanner, so the failure is never a dead end.
 */
@Composable
internal fun QrFailureState(
    failure: PairingFailure,
    onRescan: () -> Unit,
) {
    CosmosErrorState(
        message = pairingFailureCopy(PairingRole.RECEIVE, failure),
        actionLabel = PairingCopy.RECEIVE_SCAN_AGAIN,
        onAction = onRescan,
    )
}

/** One line naming how this route is arriving (plan 0005 §2). */
@Composable
private fun MethodIndicator(method: PairingMethod) {
    Text(
        text = if (method == PairingMethod.NFC) PairingCopy.NFC_LABEL else PairingCopy.QR_SCAN_LABEL,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Full-bleed scanner surface with the reticle motif: the live frame owns
 * the width (no fixed dp box), corner marks in the live cyan channel
 * frame the scan area, and the analysis view fills it. Extracted so the
 * frame geometry is instrument-testable without a camera.
 */
@Composable
internal fun ReceiveScannerFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val frameShape = RoundedCornerShape(EchoSpace24)
    val live = EchoExtras.cyan
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ReceiveScannerTag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(frameShape)
                .border(2.dp, MaterialTheme.colorScheme.primary, frameShape),
        ) {
            content()
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ReceiveReticleTag),
            ) {
                val arm = 28.dp.toPx()
                val inset = 12.dp.toPx()
                val stroke = 3.dp.toPx()
                val w = size.width
                val h = size.height
                // Four L-shaped corner marks; pairs share the live channel so
                // the frame state never rides on hue alone (marks + border).
                val corners = listOf(
                    Offset(inset, inset) to Pair(1f, 1f),
                    Offset(w - inset, inset) to Pair(-1f, 1f),
                    Offset(inset, h - inset) to Pair(1f, -1f),
                    Offset(w - inset, h - inset) to Pair(-1f, -1f),
                )
                corners.forEach { (origin, dir) ->
                    drawLine(
                        color = live,
                        start = origin,
                        end = origin + Offset(dir.first * arm, 0f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = live,
                        start = origin,
                        end = origin + Offset(0f, dir.second * arm),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportSheet(
    preview: ImportPreview,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CanopyPanel(
        variant = CanopyVariant.Sheet,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ReceiveImportSheetTag),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(EchoSpace12),
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace24),
        ) {
            // Unfold (W13-07, luminous-freeze §4): the received walk traces
            // itself in — the same sigil the sender's QR carried.
            preview.sigil?.let {
                RouteSigil(
                    geometry = it,
                    sigilSize = 96.dp,
                    trace = true,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Text(
                text = preview.name.ifEmpty { UNTITLED_WALK },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "${oneDecimal(preview.lengthM)} m · ${preview.turns} turns · " +
                    "${preview.landmarkCount} landmarks",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "From ${preview.creatorLabel}",
                style = MaterialTheme.typography.bodyLarge,
            )
            val expiresAt = preview.expiresAtEpochMs
            if (expiresAt != null) {
                Text(
                    text = expiryCopy(expiresAt),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            CosmosPrimaryButton(
                label = PairingCopy.RECEIVE_SAVE,
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            )
            CosmosSecondaryButton(
                label = PairingCopy.RECEIVE_CANCEL,
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
