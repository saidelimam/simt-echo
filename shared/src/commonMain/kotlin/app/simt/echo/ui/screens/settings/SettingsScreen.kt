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
                            title = "Visual assist",
                            subtitle = "Uses the camera while you walk, analysed on the phone, no images stored or sent; the route stores only tiny non-image profiles.",
                            trailing = {
                                Switch(
                                    checked = ui.visualAssist,
                                    onCheckedChange = viewModel::setVisualAssist,
                                )
                            },
                        )
                    },
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
                    {
                        CosmosRecordRow(
                            title = "Turn heartbeat",
                            subtitle = "A tick that quickens over the last 8 m to a turn.",
                            trailing = {
                                Switch(
                                    checked = ui.turnHeartbeat,
                                    onCheckedChange = viewModel::setTurnHeartbeat,
                                )
                            },
                        )
                    },
                    {
                        CosmosRecordRow(
                            title = "Sound cues",
                            subtitle = "Short tones at turns, panned to the arrow. Silent while a screen reader is on.",
                            trailing = {
                                Switch(
                                    checked = ui.earcons,
                                    onCheckedChange = viewModel::setEarcons,
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
                        CosmosRecordRow(
                            title = "Mobile data for landmarks",
                            subtitle = "When you choose to find landmarks while you walk, upload over mobile data " +
                                "too, not only Wi-Fi. Never while roaming.",
                            trailing = {
                                Switch(
                                    checked = ui.readyUploadMobileData,
                                    onCheckedChange = viewModel::setReadyUploadMobileData,
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
                            Text(
                                text = "Removes routes, drafts, trials and exported files from this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // Nothing to erase, nothing to offer: enabled
                            // only while at least one item survives.
                            CosmosDestructiveButton(
                                label = "Delete all",
                                onClick = { confirmDelete = true },
                                enabled = ui.canDeleteAll,
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

        // Copyright footer: the ownership facts a legal page needs, in the
        // same section grammar as everything above, then the copyright line
        // in the quietest type on the screen so it reads as a colophon and
        // not as another setting.
        SettingsSection(title = "About") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsFact(label = "Company", value = "Simt Space")
                SettingsFact(label = "Parent organisation", value = "Quazar Media")
                SettingsFact(label = "Founder", value = "Said Elimam")
                Text(
                    text = "ECHO is designed and built by Simt Space, a company of Quazar Media. " +
                        "© 2026 Simt Space. All rights reserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One footer fact: muted label left, value right-aligned on the same line.
 * The label takes the weight so a long label can never push the value into
 * a second line on a narrow screen.
 */
@Composable
private fun SettingsFact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
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
