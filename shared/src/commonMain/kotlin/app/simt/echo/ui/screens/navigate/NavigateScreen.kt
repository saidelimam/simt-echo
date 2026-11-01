package app.simt.echo.ui.screens.navigate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.EchoConfig
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import app.simt.echo.platform.KeepScreenOn
import app.simt.echo.platform.rememberHaptics
import app.simt.echo.platform.rememberMotionSource
import app.simt.echo.platform.rememberOnline
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.components.DirectionArrow
import app.simt.echo.ui.components.DistanceReadout
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.LandmarkCard
import app.simt.echo.ui.components.PillButton
import app.simt.echo.ui.components.StatusChip
import app.simt.echo.ui.components.oneDecimal

/**
 * Navigate screen (plan §8.3 / W2-06): arrow, instruction + distance,
 * landmark card, progress, contextual actions. Renders [NavUi] directly —
 * the 1:1 phase mapping (especially UNCERTAIN) is structural, not asserted
 * per branch. Always drives the live motion source; replay coverage lives
 * in the engine tests (W2-05) and the ViewModel gate below.
 */
@Composable
fun NavigateScreen(
    routeId: String,
    routes: RouteRepository,
    onArrived: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    config: EchoConfig = EchoConfig(),
) {
    val motion = rememberMotionSource()
    val haptics = rememberHaptics()
    val online = rememberOnline()
    KeepScreenOn()
    // Scoped to this navigation back-stack entry, not `remember`: popping
    // the destination must call onCleared() so motion stops and the
    // collectors die with the screen (review 0001 R2).
    val viewModel: NavigateViewModel = viewModel(
        key = routeId,
        factory = viewModelFactory {
            addInitializer(NavigateViewModel::class) {
                NavigateViewModel(routeId, routes, motion, haptics, config, onArrived)
            }
        },
    )
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.start()
        viewModel.align()
    }

    val nav = ui
    if (nav == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "No saved route $routeId", style = MaterialTheme.typography.titleLarge)
            EchoButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!online) StatusChip(text = "Offline ✓")
            StatusChip(text = "±${oneDecimal(nav.uncertaintyM)} m")
        }
        DirectionArrow(angleRad = nav.arrowRad.toFloat())
        Text(text = nav.instruction, style = MaterialTheme.typography.titleLarge)
        DistanceReadout(text = "${oneDecimal(maxOf(0.0, nav.distanceToNextM))} m")
        nav.landmark?.let { landmark ->
            val cropBytes = remember(landmark.cropAssetId) {
                landmark.cropAssetId?.let { routes.readAsset(routeId, it) }
            }
            if (cropBytes != null) {
                CropImage(bytes = cropBytes, contentDescription = landmark.title)
            }
            LandmarkCard(
                title = landmark.title,
                cue = landmark.cue,
                stats = "in ~${oneDecimal(maxOf(0.0, nav.distanceToNextM))} m",
            )
        }
        LinearProgressIndicator(
            progress = { nav.progress01.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Leg ${nav.legIndex + 1} of ${viewModel.legCount}",
            style = MaterialTheme.typography.bodyMedium,
        )
        NavigateActions(nav = nav, viewModel = viewModel)
    }
}

@Composable
private fun NavigateActions(
    nav: NavUi,
    viewModel: NavigateViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        when (nav.phase) {
            NavPhase.CONFIRM -> {
                EchoButton(text = "I see it", onClick = viewModel::seeLandmark, modifier = Modifier.fillMaxWidth())
                PillButton(text = "Not yet", onClick = viewModel::notYet, modifier = Modifier.fillMaxWidth())
            }

            NavPhase.UNCERTAIN -> {
                EchoButton(text = "I see it", onClick = viewModel::seeLandmark, modifier = Modifier.fillMaxWidth())
                PillButton(text = "Lost", onClick = viewModel::lost, modifier = Modifier.fillMaxWidth())
            }

            NavPhase.OFF_ROUTE -> {
                EchoButton(text = "Go back to last", onClick = viewModel::restartFromLast, modifier = Modifier.fillMaxWidth())
                PillButton(text = "Restart from start", onClick = viewModel::restartFromStart, modifier = Modifier.fillMaxWidth())
            }

            NavPhase.WALK, NavPhase.APPROACH, NavPhase.TURNING -> {
                if (nav.landmark != null) {
                    EchoButton(text = "I see it", onClick = viewModel::seeLandmark, modifier = Modifier.fillMaxWidth())
                }
                if (nav.phase == NavPhase.WALK && viewModel.isLastLeg(nav)) {
                    EchoButton(text = "I'm here", onClick = viewModel::imHere, modifier = Modifier.fillMaxWidth())
                }
                PillButton(text = "Lost", onClick = viewModel::lost, modifier = Modifier.fillMaxWidth())
            }

            NavPhase.ALIGN, NavPhase.ARRIVED -> Unit
        }
    }
}
