package app.simt.echo.ui.screens.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.simt.echo.model.NodeType
import app.simt.echo.ui.components.CosmosBadge
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.CosmosLoadingState
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.copy.MISSING_DRAFT_ERROR
import app.simt.echo.ui.copy.UNTITLED_WALK
import kotlin.math.roundToInt

/**
 * Review + manual landmark editor (plan W2-01) in Cosmos (plan W8-08):
 * the segmented path stays read-only on the route instrument, the film
 * strip and map cursor drive a single active checkpoint card below (never
 * dumping all frames at once), and "Draw landmark" crops a creator-verified
 * landmark out of the selected node's keyframe.
 *
 * W8-05: the shell owns the Review heading and the Back chrome — the
 * missing-draft recovery action below is a body action, not a top bar.
 *
 * Two exits, and only one of them publishes: "Save ECHO" exports the walk
 * to `routes/` and deletes the draft; "Save draft" leaves the draft on this
 * phone and returns Home — every edit is already persisted per change
 * ([ReviewViewModel.mutate]), so it never re-exports anything.
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    onSaveDraft: () -> Unit,
    onFindLandmarks: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    val drawNode = ui.nodes.firstOrNull { it.node.id == ui.drawNodeId }
    val inspectorNode = ui.nodes.firstOrNull { it.node.id == ui.inspectorNodeId }
    if (inspectorNode != null) {
        KeyframeInspector(
            nodeId = inspectorNode.node.id,
            title = inspectorNode.node.label.ifEmpty { "Frame at ${inspectorNode.node.atDistanceM.roundToInt()} m" },
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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

        val frames = filmStripFrames(ui.nodes)
        val selectedIndex = scrubSelectedIndex(frames, ui.selectedNodeId)
        val activeNodeId = frames.getOrNull(selectedIndex)?.nodeId ?: ui.selectedNodeId
        val activeNode = ui.nodes.firstOrNull { it.node.id == activeNodeId } ?: ui.nodes.firstOrNull()
        val selectedDistance = activeNode?.node?.atDistanceM ?: 0.0
        val totalDistanceM = (ui.path.lastOrNull()?.d ?: ui.nodes.maxOfOrNull { it.node.atDistanceM } ?: 0.0).roundToInt()
        val totalLandmarks = ui.nodes.sumOf { it.landmarks.size }

        // Route overview banner
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = ui.routeName.ifEmpty { UNTITLED_WALK },
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CosmosBadge(text = "$totalDistanceM m")
                    CosmosBadge(text = "${ui.nodes.size} spots")
                    CosmosBadge(text = "$totalLandmarks landmarks")
                }
                ui.polishSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ui.health?.let { health ->
            RouteHealthHeader(health = health, onNudge = viewModel::jumpToNudge)
        }

        // Walk-forward is drawn up: path y grows forward, screen y grows down.
        // The strip, the minimap cursor and the active card share one selection.
        ReviewInstrumentMap(
            points = ui.path.map { Offset(it.x.toFloat(), -it.y.toFloat()) },
            highlightIndex = pathIndexForDistance(ui.path, selectedDistance),
            nodePathIndices = frames.map { pathIndexForDistance(ui.path, it.atDistanceM) },
            selectedPositionLabel = if (frames.isNotEmpty()) {
                "Spot ${selectedIndex + 1}/${frames.size} · ${selectedDistance.roundToInt()} m"
            } else {
                null
            },
            onSelectNodeIndex = { idx ->
                frames.getOrNull(idx)?.let { viewModel.scrubTo(it.nodeId) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        FilmStrip(
            frames = frames,
            heroes = ui.nodes.associate { it.node.id to it.heroBytes },
            selectedNodeId = activeNode?.node?.id ?: ui.selectedNodeId,
            onScrub = viewModel::scrubTo,
            onInspect = viewModel::openInspector,
            landmarkCounts = ui.nodes.associate { it.node.id to it.landmarks.size },
        )

        if (frames.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CosmosSecondaryButton(
                    label = "Previous",
                    onClick = {
                        val prev = frames.getOrNull(selectedIndex - 1) ?: return@CosmosSecondaryButton
                        viewModel.scrubTo(prev.nodeId)
                    },
                    enabled = selectedIndex > 0,
                )
                Text(
                    text = "Spot ${selectedIndex + 1} of ${frames.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CosmosSecondaryButton(
                    label = "Next",
                    onClick = {
                        val next = frames.getOrNull(selectedIndex + 1) ?: return@CosmosSecondaryButton
                        viewModel.scrubTo(next.nodeId)
                    },
                    enabled = selectedIndex < frames.lastIndex,
                )
            }
        }

        // Render ONLY the currently selected node's details, never every frame at once.
        if (activeNode != null) {
            if (activeNode.node.type == NodeType.START) {
                StartCard(
                    description = ui.startDescription,
                    heroBytes = activeNode.heroBytes,
                    onDescription = viewModel::editStartDescription,
                    selected = true,
                    onInspect = { viewModel.openInspector(activeNode.node.id) },
                )
            } else {
                val nodeSuggestions = ui.suggestions[activeNode.node.id].orEmpty()
                val selectedSuggestion = nodeSuggestions.getOrNull(ui.suggestionIndex[activeNode.node.id] ?: 0)
                NodeCard(
                    suggestionImage = selectedSuggestion?.let { ui.suggestionImages[it.candidateId] },
                    reviewNode = activeNode,
                    suggestion = selectedSuggestion,
                    suggestionPosition = (ui.suggestionIndex[activeNode.node.id] ?: 0) + 1,
                    suggestionCount = nodeSuggestions.size,
                    onLabel = { viewModel.renameNode(activeNode.node.id, it) },
                    onCue = { viewModel.editCue(activeNode.node.id, it) },
                    onDraw = { viewModel.openDraw(activeNode.node.id) },
                    onEditLandmark = { id, title, cue, risky -> viewModel.editLandmark(id, title, cue, risky) },
                    onDeleteLandmark = viewModel::deleteLandmark,
                    onUseSuggestion = { viewModel.useSuggestion(activeNode.node.id) },
                    onNextSuggestion = { viewModel.nextSuggestion(activeNode.node.id) },
                    onSkipSuggestions = { viewModel.skipSuggestions(activeNode.node.id) },
                    selected = true,
                    onInspect = { viewModel.openInspector(activeNode.node.id) },
                )
            }
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
        CosmosSecondaryButton(
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
        // Secondary on purpose: keeping the walk as a draft is a real exit,
        // never a competing primary. Nothing is written here — the draft
        // already holds every edit — so a save in flight just disables it.
        CosmosSecondaryButton(
            label = "Save draft",
            onClick = onSaveDraft,
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.saving && ui.savedRouteId == null,
        )
    }
}
