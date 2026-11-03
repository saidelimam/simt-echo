package app.simt.echo.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.simt.echo.model.NodeType
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.CosmosLoadingState
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.copy.MISSING_DRAFT_ERROR
import app.simt.echo.ui.copy.UNTITLED_WALK

/**
 * Review + manual landmark editor (plan W2-01) in Cosmos (plan W8-08):
 * the segmented path stays read-only on the petrol-and-gold instrument,
 * nodes read as timeline records, every card edits names and cues, and
 * "Draw landmark" crops a creator-verified landmark out of the node's
 * keyframe. AI suggestions render keyframe + mask with stat chips and
 * Use / Next / Skip; the models that ran are named once in the
 * provenance footnote. Save writes the ECHO and leaves.
 *
 * W8-05: the shell owns the Review heading and the Back chrome — the
 * missing-draft recovery action below is a body action, not a top bar.
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    onFindLandmarks: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    val drawNode = ui.nodes.firstOrNull { it.node.id == ui.drawNodeId }
    val inspectorNode = ui.nodes.firstOrNull { it.node.id == ui.inspectorNodeId }
    if (inspectorNode != null) {
        KeyframeInspector(
            nodeId = inspectorNode.node.id,
            title = inspectorNode.node.label.ifEmpty { "Frame at ${inspectorNode.node.atDistanceM} m" },
            heroBytes = inspectorNode.heroBytes,
            onClose = viewModel::closeInspector,
            modifier = modifier,
        )
        return
    }
    if (drawNode != null) {
        DrawOverlay(
            nodeLabel = drawNode.node.label.ifEmpty { "this point" },
            heroBytes = drawNode.heroBytes,
            title = ui.drawTitle,
            onTitle = viewModel::editDrawTitle,
            onConfirm = viewModel::confirmDraw,
            onCancel = viewModel::closeDraw,
            nodeId = drawNode.node.id,
            error = ui.error,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.loading) {
            CosmosLoadingState(title = "Loading draft…")
            return@Column
        }
        if (ui.missing) {
            CosmosEmptyState(
                title = MISSING_DRAFT_ERROR.whatHappened,
                actionLabel = "Back home",
                onAction = onBack,
                body = "${MISSING_DRAFT_ERROR.whatsSafe} ${MISSING_DRAFT_ERROR.nextAction}",
            )
            return@Column
        }
        Text(text = ui.routeName.ifEmpty { UNTITLED_WALK }, style = MaterialTheme.typography.titleLarge)
        ui.health?.let { health ->
            RouteHealthHeader(health = health, onNudge = viewModel::jumpToNudge)
        }
        // Walk-forward is drawn up: path y grows forward, screen y grows down.
        // The strip, the minimap cursor and the timeline share one
        // selection: scrubbing the strip moves the cursor and the cards.
        val frames = filmStripFrames(ui.nodes)
        val selectedDistance = ui.nodes.firstOrNull { it.node.id == ui.selectedNodeId }
            ?.node?.atDistanceM ?: 0.0
        ReviewInstrumentMap(
            points = ui.path.map { Offset(it.x.toFloat(), -it.y.toFloat()) },
            highlightIndex = pathIndexForDistance(ui.path, selectedDistance),
            modifier = Modifier.fillMaxWidth(),
        )
        FilmStrip(
            frames = frames,
            heroes = ui.nodes.associate { it.node.id to it.heroBytes },
            selectedNodeId = ui.selectedNodeId,
            onScrub = viewModel::scrubTo,
            onInspect = viewModel::openInspector,
        )
        val startNode = ui.nodes.firstOrNull { it.node.type == NodeType.START }
        if (startNode != null) {
            StartCard(
                description = ui.startDescription,
                heroBytes = startNode.heroBytes,
                onDescription = viewModel::editStartDescription,
                selected = startNode.node.id == ui.selectedNodeId,
            )
        }
        ui.nodes.filter { it.node.type != NodeType.START }.forEach { reviewNode ->
            val nodeSuggestions = ui.suggestions[reviewNode.node.id].orEmpty()
            val selected = nodeSuggestions.getOrNull(ui.suggestionIndex[reviewNode.node.id] ?: 0)
            NodeCard(
                suggestionImage = selected?.let { ui.suggestionImages[it.candidateId] },
                reviewNode = reviewNode,
                suggestion = selected,
                suggestionPosition = (ui.suggestionIndex[reviewNode.node.id] ?: 0) + 1,
                suggestionCount = nodeSuggestions.size,
                onLabel = { viewModel.renameNode(reviewNode.node.id, it) },
                onCue = { viewModel.editCue(reviewNode.node.id, it) },
                onDraw = { viewModel.openDraw(reviewNode.node.id) },
                onEditLandmark = { id, title, cue, risky -> viewModel.editLandmark(id, title, cue, risky) },
                onDeleteLandmark = viewModel::deleteLandmark,
                onUseSuggestion = { viewModel.useSuggestion(reviewNode.node.id) },
                onNextSuggestion = { viewModel.nextSuggestion(reviewNode.node.id) },
                onSkipSuggestions = { viewModel.skipSuggestions(reviewNode.node.id) },
                selected = reviewNode.node.id == ui.selectedNodeId,
            )
        }
        ui.aiModels?.let {
            Text(
                text = "Suggested by $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        CosmosPrimaryButton(
            label = "Find landmarks",
            onClick = onFindLandmarks,
            modifier = Modifier.fillMaxWidth(),
        )
        CosmosPrimaryButton(
            label = "Save ECHO",
            onClick = { viewModel.save(onSaved) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.saving && ui.savedRouteId == null,
            loading = ui.saving,
        )
    }
}
