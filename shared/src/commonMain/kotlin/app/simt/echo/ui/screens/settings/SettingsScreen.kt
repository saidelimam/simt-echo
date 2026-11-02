package app.simt.echo.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simt.echo.ui.components.CosmosDestructiveButton
import app.simt.echo.ui.components.CosmosDialog
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosRecordRow
import app.simt.echo.ui.components.CosmosRecordRows
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosTextField
import app.simt.echo.ui.components.threeDecimals
import app.simt.echo.ui.copy.cloudAiStatusCopy

/**
 * Settings in Cosmos (plan W8-11, freeze §5/§7): sectioned rows on the
 * record grammar — dividers, never nested cards. Step length & calibrate,
 * Trials & baseline & CSV, Navigation, Privacy & data (Delete all behind
 * a canopy confirm), Landmark AI status (W7-07 wording, untouched).
 *
 * W9-07: the Advanced local-model section is removed for v1 — local
 * inference (on-phone `.litertlm`, LAN server) was never verified on a
 * device here, so no UI may imply its availability. The provider seam
 * (`EchoConfig`/`AppGraph`) stays for a future slice.
 *
 * W8-05: the shell owns the Settings heading and the Close chrome —
 * [onBack] stays for hosts that render this screen outside the shell.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        CosmosDialog(
            title = "Delete everything?",
            body = "Routes, drafts, trials and exported files leave this phone. This cannot be undone.",
            primaryLabel = "Delete all",
            onPrimary = {
                viewModel.deleteAll()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
            secondaryLabel = "Keep",
            onSecondary = { confirmDelete = false },
            destructive = true,
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsSection(title = "Step length") {
            CosmosRecordRows(
                rows = listOf(
                    {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "K = ${threeDecimals(ui.stepK)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Walk 10 m, enter the reading",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            CosmosTextField(
                                label = "Measured metres",
                                value = ui.measuredM,
                                onValueChange = viewModel::onMeasuredChange,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CosmosPrimaryButton(label = "Calibrate", onClick = viewModel::calibrate)
                                CosmosSecondaryButton(label = "Reset", onClick = viewModel::resetK)
                            }
                            ui.calibrationNote?.let { Text(text = it) }
                            Text(
                                text = strideLearnedLabel(ui.strideLearnedLegs),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                ),
            )
        }

        SettingsSection(title = "Trials") {
            CosmosRecordRows(
                rows = listOf(
                    {
                        CosmosRecordRow(
                            title = "Baseline mode",
                            subtitle = "Hides landmark cards: turns and distance only.",
                            trailing = {
                                Switch(
                                    checked = ui.baselineMode,
                                    onCheckedChange = viewModel::setBaselineMode,
                                )
                            },
                        )
                    },
                    {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            CosmosPrimaryButton(label = "Export CSV", onClick = viewModel::exportTrials)
                            ui.exportNote?.let { Text(text = it) }
                        }
                    },
                ),
            )
        }

        SettingsSection(title = "Navigation") {
            CosmosRecordRows(
                rows = listOf(
                    {
                        CosmosRecordRow(
                            title = "Camera overlay",
                            subtitle = "Live camera behind the navigation HUD, with the landmark crop and mask.",
                            trailing = {
                                Switch(
                                    checked = ui.cameraOverlayEnabled,
                                    onCheckedChange = viewModel::setCameraOverlay,
                                )
                            },
                        )
                    },
                ),
            )
        }

        SettingsSection(title = "Privacy & data") {
            CosmosRecordRows(
                rows = listOf(
                    {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "Removes routes, drafts, trials and exported files from this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            CosmosDestructiveButton(
                                label = "Delete all",
                                onClick = { confirmDelete = true },
                            )
                            ui.deleteNote?.let { Text(text = it) }
                        }
                    },
                ),
            )
        }

        SettingsSection(title = "Landmark AI") {
            CosmosRecordRows(
                rows = listOf(
                    {
                        CosmosRecordRow(
                            title = "Cloud status",
                            subtitle = cloudAiStatusCopy(ui.cloudAiConfigured),
                        )
                    },
                ),
            )
        }
    }
}

/** One settings section: section-title heading over record rows. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}
