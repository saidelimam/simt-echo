package app.simt.echo.ui.screens.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.simt.echo.ai.AiCandidate
import app.simt.echo.ai.CandidateMerger
import app.simt.echo.ai.MaskPath
import app.simt.echo.media.CropBox
import app.simt.echo.model.Landmark
import app.simt.echo.model.NodeType
import app.simt.echo.platform.decodeImage
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.components.Minimap
import app.simt.echo.ui.components.PillButton
import kotlin.math.roundToInt

/**
 * Review + manual landmark editor (plan W2-01): the segmented path stays
 * read-only (sensor-owned geometry is never edited), every card edits
 * names and cues, and "Draw landmark" crops a creator-verified landmark
 * out of the node's keyframe. Save writes the ECHO and leaves.
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
    if (drawNode != null) {
        DrawOverlay(
            nodeLabel = drawNode.node.label.ifEmpty { drawNode.node.id },
            heroBytes = drawNode.heroBytes,
            title = ui.drawTitle,
            onTitle = viewModel::editDrawTitle,
            onConfirm = viewModel::confirmDraw,
            onCancel = viewModel::closeDraw,
            nodeId = drawNode.node.id,
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
            Text(text = "Loading draft…", style = MaterialTheme.typography.titleLarge)
            return@Column
        }
        if (ui.missing) {
            Text(text = "No draft route found.", style = MaterialTheme.typography.titleLarge)
            EchoButton(text = "Back home", onClick = onBack, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        Text(text = ui.routeName.ifEmpty { "Untitled walk" }, style = MaterialTheme.typography.titleLarge)
        // Walk-forward is drawn up: path y grows forward, screen y grows down.
        Minimap(
            points = ui.path.map { Offset(it.x.toFloat(), -it.y.toFloat()) },
            highlightIndex = 0,
            modifier = Modifier.fillMaxWidth(),
        )
        val startNode = ui.nodes.firstOrNull { it.node.type == NodeType.START }
        if (startNode != null) {
            StartCard(
                description = ui.startDescription,
                heroBytes = startNode.heroBytes,
                onDescription = viewModel::editStartDescription,
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
                aiModels = ui.aiModels,
                onLabel = { viewModel.renameNode(reviewNode.node.id, it) },
                onCue = { viewModel.editCue(reviewNode.node.id, it) },
                onDraw = { viewModel.openDraw(reviewNode.node.id) },
                onEditLandmark = { id, title, cue -> viewModel.editLandmark(id, title, cue) },
                onDeleteLandmark = viewModel::deleteLandmark,
                onUseSuggestion = { viewModel.useSuggestion(reviewNode.node.id) },
                onNextSuggestion = { viewModel.nextSuggestion(reviewNode.node.id) },
                onSkipSuggestions = { viewModel.skipSuggestions(reviewNode.node.id) },
            )
        }
        ui.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        EchoButton(
            text = "Find landmarks",
            onClick = onFindLandmarks,
            modifier = Modifier.fillMaxWidth(),
        )
        EchoButton(
            text = if (ui.saving) "Saving…" else "Save ECHO",
            onClick = { viewModel.save(onSaved) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !ui.saving && ui.savedRouteId == null,
        )
        PillButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StartCard(
    description: String,
    heroBytes: ByteArray?,
    onDescription: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Start", style = MaterialTheme.typography.titleMedium)
        HeroImage(bytes = heroBytes, contentDescription = "Start photo")
        OutlinedTextField(
            value = description,
            onValueChange = onDescription,
            label = { Text(text = "Start description") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NodeCard(
    reviewNode: ReviewNode,
    suggestion: AiCandidate?,
    suggestionImage: ByteArray? = null,
    suggestionPosition: Int,
    suggestionCount: Int,
    aiModels: String?,
    onLabel: (String) -> Unit,
    onCue: (String) -> Unit,
    onDraw: () -> Unit,
    onEditLandmark: (String, String, String) -> Unit,
    onDeleteLandmark: (String) -> Unit,
    onUseSuggestion: () -> Unit,
    onNextSuggestion: () -> Unit,
    onSkipSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = reviewNode.node
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "${node.atDistanceM.roundToInt()} m · ${node.type.name.lowercase().replace('_', ' ')}",
            style = MaterialTheme.typography.titleMedium,
        )
        HeroImage(bytes = reviewNode.heroBytes, contentDescription = node.label.ifEmpty { "Node keyframe" })
        OutlinedTextField(
            value = node.label,
            onValueChange = onLabel,
            label = { Text(text = "Label") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = node.cue,
            onValueChange = onCue,
            label = { Text(text = "Spoken cue") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestion != null) {
            SuggestionCard(
                candidate = suggestion,
                heroBytes = suggestionImage ?: reviewNode.heroBytes,
                position = suggestionPosition,
                count = suggestionCount,
                aiModels = aiModels,
                onUse = onUseSuggestion,
                onNext = onNextSuggestion,
                onSkip = onSkipSuggestions,
            )
        }
        reviewNode.landmarks.forEach { landmark ->
            LandmarkRow(
                landmark = landmark,
                onEdit = onEditLandmark,
                onDelete = onDeleteLandmark,
            )
        }
        PillButton(text = "Draw landmark", onClick = onDraw, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * One preselected AI suggestion (plan W3-07): the keyframe with the
 * candidate box (2 dp accent stroke) and the SAM mask at 35 % accent fill,
 * stat chips when SAM ran, the model footnote, and Use / Next / Skip.
 * Unreviewed suggestions never enter the route, so Skip needs no export
 * filter downstream.
 */
@Composable
private fun SuggestionCard(
    candidate: AiCandidate,
    heroBytes: ByteArray?,
    position: Int,
    count: Int,
    aiModels: String?,
    onUse: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val bitmap = remember(heroBytes) { heroBytes?.let(::decodeImage) }
        val accent = MaterialTheme.colorScheme.primary
        val accentFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
        if (bitmap != null) {
            val box = CandidateMerger.candidateCropBox(candidate)
            val mask = candidate.maskSvgPath
                ?.takeIf { candidate.maskW != null && candidate.maskH != null }
                ?.let { MaskPath.parseSvgPath(it) }
            Canvas(
                modifier = Modifier.fillMaxWidth().height(160.dp).semantics {
                    contentDescription = "AI suggestion: ${candidate.title}"
                },
            ) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
                // Box: normalised crop mapped onto the canvas.
                drawRect(
                    color = accent,
                    topLeft = Offset(box.left * size.width, box.top * size.height),
                    size = Size(
                        (box.right - box.left) * size.width,
                        (box.bottom - box.top) * size.height,
                    ),
                    style = Stroke(width = strokePx),
                )
                // Mask: SVG raster px → keyframe px (kfW/maskW, kfH/maskH) →
                // canvas px. An unreadable mask draws nothing (box-only).
                val maskW = candidate.maskW
                val maskH = candidate.maskH
                if (mask != null && maskW != null && maskH != null && maskW > 0 && maskH > 0) {
                    for (subpath in mask) {
                        val path = Path()
                        subpath.points.forEachIndexed { i, point ->
                            // Float scaling: mask raster → canvas directly
                            // (the raster covers the frame the canvas shows).
                            val x = point.x * (size.width / maskW.toFloat())
                            val y = point.y * (size.height / maskH.toFloat())
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        if (subpath.closed) path.close()
                        drawPath(path = path, color = accentFill, style = Fill)
                    }
                }
            }
        }
        Text(
            text = "AI suggestion${
                if (count > 1) " $position of $count" else ""
            }: ${candidate.title}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = candidate.cue, style = MaterialTheme.typography.bodyMedium)
        CandidateMerger.statLine(candidate)?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
        aiModels?.let {
            Text(text = "Suggested by $it", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EchoButton(text = "Use", onClick = onUse)
            if (count > 1) PillButton(text = "Next option", onClick = onNext)
            PillButton(text = "Skip", onClick = onSkip)
        }
    }
}

@Composable
private fun LandmarkRow(
    landmark: Landmark,
    onEdit: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = landmark.title,
            onValueChange = { onEdit(landmark.id, it, landmark.cue) },
            label = { Text(text = "Landmark") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = landmark.cue,
            onValueChange = { onEdit(landmark.id, landmark.title, it) },
            label = { Text(text = "Landmark cue") },
            modifier = Modifier.fillMaxWidth(),
        )
        PillButton(text = "Delete", onClick = { onDelete(landmark.id) })
    }
}

@Composable
private fun HeroImage(
    bytes: ByteArray?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    CropImage(
        bytes = bytes,
        contentDescription = contentDescription,
        modifier = modifier.height(160.dp),
    ) {
        Text(
            text = "No keyframe yet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Full-screen draw overlay: drag a rectangle over the hero, name it,
 * confirm. Coordinates are normalised to 0..1 so the crop never depends
 * on screen size.
 */
@Composable
private fun DrawOverlay(
    nodeId: String,
    nodeLabel: String,
    heroBytes: ByteArray?,
    title: String,
    onTitle: (String) -> Unit,
    onConfirm: (String, CropBox) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var box by remember { mutableStateOf<Rect?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Draw on $nodeLabel", style = MaterialTheme.typography.titleLarge)
        val bitmap = remember(heroBytes) { heroBytes?.let(::decodeImage) }
        if (bitmap == null) {
            Text(text = "No keyframe for this node yet.")
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                var start by remember { mutableStateOf<Offset?>(null) }
                val boxColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(nodeId) {
                            detectDragGestures(
                                onDragStart = { start = it },
                                onDrag = { change, _ -> box = start?.let { s -> Rect(s, change.position) } },
                                onDragEnd = { start = null },
                                onDragCancel = { start = null; box = null },
                            )
                        },
                ) {
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    )
                    box?.let {
                        drawRect(
                            color = boxColor,
                            topLeft = it.topLeft,
                            size = it.size,
                            style = Stroke(width = 6f),
                        )
                    }
                }
                val current = box
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EchoButton(
                        text = "Use box",
                        onClick = {
                            val rect = current ?: return@EchoButton
                            val w = widthPx.coerceAtLeast(1f)
                            val h = heightPx.coerceAtLeast(1f)
                            onConfirm(
                                nodeId,
                                CropBox(
                                    left = (minOf(rect.left, rect.right) / w).coerceIn(0f, 1f),
                                    top = (minOf(rect.top, rect.bottom) / h).coerceIn(0f, 1f),
                                    right = (maxOf(rect.left, rect.right) / w).coerceIn(0f, 1f),
                                    bottom = (maxOf(rect.top, rect.bottom) / h).coerceIn(0f, 1f),
                                ),
                            )
                        },
                        enabled = current != null && title.isNotBlank(),
                    )
                    PillButton(text = "Clear", onClick = { box = null }, enabled = current != null)
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text(text = "Landmark name") },
            modifier = Modifier.fillMaxWidth(),
        )
        PillButton(text = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}
