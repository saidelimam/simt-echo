package app.simt.echo.ui.screens.arrived

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.NodeType
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.copy.UNTITLED_WALK

/**
 * Arrived screen (plan §8.3 / W2-06): check mark, destination, summary,
 * Done. The run's stats were written by Navigate on arrival (W4-01);
 * this form completes the §11 fields the sensors cannot know (lighting,
 * holding style, mistaken confirmations, notes) and re-saves the trial.
 */
@Composable
fun ArrivedScreen(
    routeId: String,
    routes: RouteRepository,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    trials: TrialRepository? = null,
) {
    val route = remember(routeId) { routes.get(routeId) }
    val trial = remember(routeId, trials) {
        trials?.list()?.filter { it.routeId == routeId }?.maxByOrNull { it.startedAtEpochMs }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "✓", style = MaterialTheme.typography.displayLarge)
        if (route == null) {
            Text(text = "Arrived", style = MaterialTheme.typography.titleLarge)
        } else {
            val destination = route.nodes.lastOrNull { it.type == NodeType.DESTINATION }
                ?.label?.takeIf { it.isNotEmpty() } ?: route.name.ifEmpty { UNTITLED_WALK }
            Text(text = destination, style = MaterialTheme.typography.titleLarge)
            // Route facts only: this screen observes no confirmations, so it
            // must not claim any (review 0001 R2).
            Text(
                text = "${oneDecimal(route.totalLengthM)} m · " +
                    "${route.landmarks.size} landmarks on route",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (trial != null && trials != null) {
            TrialForm(trialId = trial.trialId, trials = trials)
        }
        CosmosPrimaryButton(label = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}

/** §11 completion form for the trial Navigate just wrote. */
@Composable
private fun TrialForm(trialId: String, trials: TrialRepository) {
    val existing = remember(trialId) { trials.get(trialId) } ?: return
    var lighting by remember(trialId) { mutableStateOf(existing.lighting) }
    var holding by remember(trialId) { mutableStateOf(existing.holdingStyle) }
    var mistaken by remember(trialId) { mutableStateOf(existing.mistakenConfirmations.toString()) }
    var notes by remember(trialId) { mutableStateOf(existing.notes) }
    var saved by remember(trialId) { mutableStateOf(false) }

    Text(text = "Field trial (${existing.mode})", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = lighting,
        onValueChange = { lighting = it; saved = false },
        label = { Text("Lighting") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = holding,
        onValueChange = { holding = it; saved = false },
        label = { Text("Holding style") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = mistaken,
        onValueChange = { mistaken = it.filter(Char::isDigit); saved = false },
        label = { Text("Mistaken confirmations") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it; saved = false },
        label = { Text("Notes") },
        modifier = Modifier.fillMaxWidth(),
    )
    CosmosSecondaryButton(
        label = if (saved) "Trial saved" else "Save trial",
        onClick = {
            trials.save(
                existing.copy(
                    lighting = lighting,
                    holdingStyle = holding,
                    mistakenConfirmations = mistaken.toIntOrNull() ?: 0,
                    notes = notes,
                ),
            )
            saved = true
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
