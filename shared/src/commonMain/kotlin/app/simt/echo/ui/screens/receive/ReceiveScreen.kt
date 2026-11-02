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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.CameraPermissionGate
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.QrScannerView
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.components.CosmosErrorState
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosSuccessState
import app.simt.echo.ui.components.TransferParticles
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.copy.UNTITLED_WALK
import app.simt.echo.ui.copy.expiryCopy
import app.simt.echo.ui.sigil.RouteSigil
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace24

const val ReceiveScannerTag = "ReceiveScanner"
const val ReceiveReticleTag = "ReceiveReticle"
const val ReceiveImportSheetTag = "ReceiveImportSheet"

/**
 * Receive screen in Cosmos (plan §8.3 / W2-04, slice W8-09): a full-bleed
 * scanner surface with the reticle motif, fetch spinner, then the import
 * sheet (name, length, landmarks, creator, expiry) as a canopy sheet —
 * no dialog windows. "Import file" (W2-07) feeds a picked plain `.echo`
 * into the same sheet.
 *
 * Presentation only: scan/fetch/save ViewModel behaviour is untouched.
 *
 * W8-05: the shell owns the Receive heading and the Close chrome.
 */
@Composable
fun ReceiveScreen(
    service: TransferService,
    sharing: FileSharing,
    onImported: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(service, sharing) { ReceiveViewModel(service, sharing) }
    val ui by viewModel.ui.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EchoSpace24),
        verticalArrangement = Arrangement.spacedBy(EchoSpace16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val savedId = ui.savedRouteId
        val preview = ui.preview
        when {
            savedId != null -> {
                CosmosSuccessState(
                    title = "Received ✓",
                    actionLabel = "Open route",
                    onAction = { onImported(savedId) },
                )
            }

            preview != null -> ImportSheet(
                preview = preview,
                onSave = { viewModel.save(onImported) },
                onRescan = viewModel::rescan,
            )

            else -> {
                if (!ui.fetching) {
                    CameraPermissionGate {
                        ReceiveScannerFrame(modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.runtime.key(ui.generation) {
                                QrScannerView(
                                    onScanned = viewModel::onScanned,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    Text(
                        text = "Scan the other phone's QR",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    CosmosSecondaryButton(
                        label = "Import file",
                        onClick = viewModel::importFile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ui.fetching) {
                    CircularProgressIndicator()
                    Text(text = "Fetching…", style = MaterialTheme.typography.bodyLarge)
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
                        actionLabel = "Scan again",
                        onAction = viewModel::rescan,
                    )
                }
            }
        }
    }
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
    onRescan: () -> Unit,
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
            CosmosPrimaryButton(label = "Save", onClick = onSave, modifier = Modifier.fillMaxWidth())
            CosmosSecondaryButton(label = "Scan again", onClick = onRescan, modifier = Modifier.fillMaxWidth())
        }
    }
}
