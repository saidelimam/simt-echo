package app.simt.echo.ui.screens.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.simt.echo.media.CropBox
import app.simt.echo.media.cropBoxFromRect
import app.simt.echo.platform.decodeImage
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import kotlin.math.roundToInt

/** Semantics tag for the draw canvas (headless UI gate target). */
const val DrawCanvasTag = "drawCanvas"

/**
 * Full-screen draw overlay: drag a rectangle over the hero, name it,
 * confirm. Coordinates are normalised to 0..1 so the crop never depends
 * on screen size. Internal (not private) so the W6-03 headless UI gate
 * can drive the real gesture path in androidHostTest; behaviour unchanged.
 */
@Composable
internal fun DrawOverlay(
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
                        .testTag(DrawCanvasTag)
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
                    CosmosPrimaryButton(
                        label = "Use box",
                        onClick = {
                            val rect = current ?: return@CosmosPrimaryButton
                            onConfirm(nodeId, cropBoxFromRect(rect, widthPx, heightPx))
                        },
                        enabled = current != null && title.isNotBlank(),
                    )
                    CosmosSecondaryButton(label = "Clear", onClick = { box = null }, enabled = current != null)
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text(text = "Landmark name") },
            modifier = Modifier.fillMaxWidth(),
        )
        CosmosSecondaryButton(label = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}
