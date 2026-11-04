package app.simt.echo.ui.screens.arrived

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.data.TrialRepository
import app.simt.echo.model.NodeType
import app.simt.echo.model.Trial
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosSuccessState
import app.simt.echo.ui.components.CosmosTextField
import app.simt.echo.ui.components.CosmosTertiaryButton
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.copy.UNTITLED_WALK

/**
 * Arrived screen (plan §8.3 / W2-06, Cosmos W8-10): a Cosmos success state
 * — destination, time, distance, confirmations — with Done. The run's
 * stats were written by Navigate on arrival (W4-01); the §11 field-trial
 * form lives behind the "Field trial details" disclosure: still saved,
 * no longer the screen (M4). W13-11: the success mark is the arrival
 * bloom (luminous-freeze §4).
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
    var showTrialDetails by remember(trial?.trialId) { mutableStateOf(false) }

    val destination = remember(route) {
        route?.nodes?.lastOrNull { it.type == NodeType.DESTINATION }
            ?.label?.takeIf { it.isNotEmpty() }
            ?: route?.name?.ifEmpty { UNTITLED_WALK }
            ?: "Arrived"
    }
    // Route facts only when no trial ran: this screen observes no
    // confirmations, so it must not claim any (review 0001 R2).
    val summary = trial?.let { arrivedSummary(it) }
        ?: route?.let {
            "${oneDecimal(it.totalLengthM)} m · ${it.landmarks.size} landmarks on route"
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CosmosSuccessState(
            title = destination,
            body = summary,
            actionLabel = "Done",
            onAction = onDone,
            hero = { ArrivalBloom() },
            modifier = Modifier.fillMaxWidth(),
        )
        if (trial != null && trials != null) {
            CosmosTertiaryButton(
                label = if (showTrialDetails) "Hide field trial details" else "Field trial details",
                onClick = { showTrialDetails = !showTrialDetails },
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(
                visible = showTrialDetails,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                label = "trial-details",
            ) {
                TrialForm(trialId = trial.trialId, trials = trials)
            }
        }
    }
}

/** Success copy from the saved run: time, distance, confirmations. */
fun arrivedSummary(trial: Trial): String {
    val totalS = trial.durationS.toLong()
    val time = if (totalS >= 60) "${totalS / 60} min ${totalS % 60} s" else "$totalS s"
    return "$time · ${oneDecimal(trial.distanceM)} m · ${trial.confirmations} confirmations"
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

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = "Field trial (${existing.mode})", style = MaterialTheme.typography.titleMedium)
        CosmosTextField(
            label = "Lighting",
            value = lighting,
            onValueChange = { lighting = it; saved = false },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        CosmosTextField(
            label = "Holding style",
            value = holding,
            onValueChange = { holding = it; saved = false },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        CosmosTextField(
            label = "Mistaken confirmations",
            value = mistaken,
            onValueChange = { mistaken = it.filter(Char::isDigit); saved = false },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
        )
        CosmosTextField(
            label = "Notes",
            value = notes,
            onValueChange = { notes = it; saved = false },
            singleLine = false,
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
}
