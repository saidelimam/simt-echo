package app.simt.echo.ui.screens.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.simt.echo.media.CropBox
import app.simt.echo.model.Landmark
import app.simt.echo.model.NodeType
import app.simt.echo.platform.decodeImage
import app.simt.echo.ui.components.EchoButton
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
            NodeCard(
                reviewNode = reviewNode,
                onLabel = { viewModel.renameNode(reviewNode.node.id, it) },
                onCue = { viewModel.editCue(reviewNode.node.id, it) },
                onDraw = { viewModel.openDraw(reviewNode.node.id) },
                onEditLandmark = { id, title, cue -> viewModel.editLandmark(id, title, cue) },
                onDeleteLandmark = viewModel::deleteLandmark,
            )
        }
        ui.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
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
    onLabel: (String) -> Unit,
    onCue: (String) -> Unit,
    onDraw: () -> Unit,
    onEditLandmark: (String, String, String) -> Unit,
    onDeleteLandmark: (String) -> Unit,
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
    val bitmap = remember(bytes) { bytes?.let(::decodeImage) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    } else {
        Text(
            text = "No keyframe yet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.fillMaxWidth(),
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
