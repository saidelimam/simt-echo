package app.simt.echo.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.PillButton
import app.simt.echo.ui.components.threeDecimals

/**
 * Settings: step-K calibration + baseline default (plan §8.3, W4-02).
 *
 * One action per group, words few (§8.4 copy style): the taped 10 m walk
 * corrects the step constant for every future capture, and the baseline
 * toggle hides landmark cards for honest sensor-only trials (W4-01).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.titleLarge)

        Text(text = "Step length", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "K = ${threeDecimals(ui.stepK)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "Walk 10 m, enter the reading",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = ui.measuredM,
            onValueChange = viewModel::onMeasuredChange,
            label = { Text(text = "Measured metres") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EchoButton(text = "Calibrate", onClick = viewModel::calibrate)
            PillButton(text = "Reset", onClick = viewModel::resetK)
        }
        ui.calibrationNote?.let { Text(text = it) }

        Text(text = "Trials", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Baseline mode",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = ui.baselineMode,
                onCheckedChange = viewModel::setBaselineMode,
            )
        }
        Text(
            text = "Hides landmark cards: turns and distance only.",
            style = MaterialTheme.typography.bodyMedium,
        )
        EchoButton(text = "Export CSV", onClick = viewModel::exportTrials)
        ui.exportNote?.let { Text(text = it) }

        Text(text = "Navigation", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Camera overlay",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = ui.cameraOverlayEnabled,
                onCheckedChange = viewModel::setCameraOverlay,
            )
        }
        Text(
            text = "Live camera behind the navigation HUD, with the landmark crop and mask.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(text = "Advanced", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Local model (optional). A vision server on your own network, " +
                "or a model file on this phone. Nothing is sent to the cloud when it runs.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = ui.localModelBaseUrl,
            onValueChange = viewModel::onLocalModelBaseUrlChange,
            label = { Text(text = "LAN server URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ui.localModelName,
            onValueChange = viewModel::onLocalModelNameChange,
            label = { Text(text = "Model name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Prefer local model",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = ui.preferLocalModel,
                onCheckedChange = viewModel::setPreferLocalModel,
            )
        }
        Text(
            text = if (ui.onPhoneModel != null) {
                "On-phone model found: ${ui.onPhoneModel}"
            } else {
                "No on-phone model file found."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        EchoButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
