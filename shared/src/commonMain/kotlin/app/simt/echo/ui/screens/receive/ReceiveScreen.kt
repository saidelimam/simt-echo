package app.simt.echo.ui.screens.receive

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.QrScannerView
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.oneDecimal

/**
 * Receive screen (plan §8.3 / W2-04): scanner with a rounded cutout, fetch
 * spinner, import sheet (name, length, landmarks, creator, expiry) as an
 * inline panel — no dialog windows — then Save and a Received badge.
 */
@Composable
fun ReceiveScreen(
    service: TransferService,
    onImported: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(service) { ReceiveViewModel(service) }
    val ui by viewModel.ui.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Receive", style = MaterialTheme.typography.titleLarge)
        val savedId = ui.savedRouteId
        val preview = ui.preview
        when {
            savedId != null -> {
                Text(text = "Received ✓", style = MaterialTheme.typography.titleLarge)
                EchoButton(
                    text = "Open route",
                    onClick = { onImported(savedId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            preview != null -> ImportSheet(
                preview = preview,
                onSave = { viewModel.save(onImported) },
                onRescan = viewModel::rescan,
            )

            else -> {
                if (!ui.fetching) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
                    ) {
                        androidx.compose.runtime.key(ui.generation) {
                            QrScannerView(
                                onScanned = viewModel::onScanned,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = "Scan the QR on the other phone",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (ui.fetching) {
                    CircularProgressIndicator()
                    Text(text = "Fetching…", style = MaterialTheme.typography.bodyLarge)
                }
                val error = ui.error
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    EchoButton(
                        text = "Scan again",
                        onClick = viewModel::rescan,
                        modifier = Modifier.fillMaxWidth(),
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
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text = preview.name, style = MaterialTheme.typography.titleLarge)
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
                text = "Expires $expiresAt",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        EchoButton(text = "Save", onClick = onSave, modifier = Modifier.fillMaxWidth())
        EchoButton(text = "Scan again", onClick = onRescan, modifier = Modifier.fillMaxWidth())
    }
}
