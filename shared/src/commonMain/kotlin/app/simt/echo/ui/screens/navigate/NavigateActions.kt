package app.simt.echo.ui.screens.navigate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosTertiaryButton

/**
 * Contextual Navigate actions (plan §8.3 / W8-10): the head of the
 * presented list renders as the primary command, the rest as
 * secondaries (never two primaries). Shared by the plain and
 * camera-HUD Navigate bodies.
 */
@Composable
fun NavigateActions(
    presentation: NavigatePhasePresentation,
    viewModel: NavigateViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // Phase honesty is decided in [navigatePhasePresentation]; here each
        // action only wires to the ViewModel input of the same name. The
        // head of the list is the primary command (never two primaries).
        val primary = presentation.actions.firstOrNull()
        val secondaries = presentation.actions.drop(1)
        if (primary != null) {
            CosmosPrimaryButton(
                label = actionLabel(primary),
                onClick = { viewModel.navigate(primary) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        for (action in secondaries) {
            when (action) {
                NavigateAction.AT_MARKER ->
                    CosmosTertiaryButton(
                        label = actionLabel(action),
                        onClick = { viewModel.navigate(action) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                else -> CosmosSecondaryButton(
                    label = actionLabel(action),
                    onClick = { viewModel.navigate(action) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun actionLabel(action: NavigateAction): String = when (action) {
    NavigateAction.SEE_LANDMARK -> "I see it"
    NavigateAction.NOT_YET -> "Not yet"
    NavigateAction.LOST -> "Lost"
    NavigateAction.GO_BACK_TO_LAST -> "Go back to last"
    NavigateAction.RESTART_FROM_START -> "Restart from start"
    NavigateAction.IM_HERE -> "I'm here"
    NavigateAction.AT_MARKER -> "At marker (trial)"
}

/** Single wiring point from a presented action to its ViewModel input. */
private fun NavigateViewModel.navigate(action: NavigateAction) {
    when (action) {
        NavigateAction.SEE_LANDMARK -> seeLandmark()
        NavigateAction.NOT_YET -> notYet()
        NavigateAction.LOST -> lost()
        NavigateAction.GO_BACK_TO_LAST -> restartFromLast()
        NavigateAction.RESTART_FROM_START -> restartFromStart()
        NavigateAction.IM_HERE -> imHere()
        NavigateAction.AT_MARKER -> atMarker()
    }
}
