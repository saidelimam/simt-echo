package app.simt.echo.ui.screens.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * can drive the real gesture path in androidHostTest.
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
    error: String? = null,
) {
    var box by remember(nodeId) { mutableStateOf<Rect?>(null) }
    var canvasWidthPx by remember { mutableStateOf(1f) }
    var canvasHeightPx by remember { mutableStateOf(1f) }
    val bitmap = remember(heroBytes) { heroBytes?.let(::decodeImage) }
    val current = box
    val helperCopy = when {
        bitmap == null -> "No keyframe is available for this spot yet."
        current == null -> "Drag a rectangle on the photo around the landmark."
        title.isBlank() -> "Enter a landmark name below to enable Use box."
        else -> "Landmark framed and named — tap Use box to save."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Draw on $nodeLabel",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = helperCopy,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.medium),
        ) {
            if (bitmap == null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                ) {
                    Text(
                        text = "No keyframe for this node yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                    val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                    canvasWidthPx = widthPx
                    canvasHeightPx = heightPx
                    var start by remember(nodeId) { mutableStateOf<Offset?>(null) }
                    val boxColor = MaterialTheme.colorScheme.primary
                    val boxFill = boxColor.copy(alpha = 0.20f)
                    val strokePx = with(density) { 3.dp.toPx() }
                    val handleRadiusPx = with(density) { 5.dp.toPx() }
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(DrawCanvasTag)
                            .pointerInput(nodeId) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        start = Offset(
                                            offset.x.coerceIn(0f, size.width.toFloat()),
                                            offset.y.coerceIn(0f, size.height.toFloat()),
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val s = start ?: return@detectDragGestures
                                        val p = Offset(
                                            change.position.x.coerceIn(0f, size.width.toFloat()),
                                            change.position.y.coerceIn(0f, size.height.toFloat()),
                                        )
                                        box = Rect(
                                            left = minOf(s.x, p.x),
                                            top = minOf(s.y, p.y),
                                            right = maxOf(s.x, p.x),
                                            bottom = maxOf(s.y, p.y),
                                        )
                                    },
                                    onDragEnd = { start = null },
                                    onDragCancel = {
                                        start = null
                                        box = null
                                    },
                                )
                            },
                    ) {
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                        box?.let { rect ->
                            drawRect(
                                color = boxFill,
                                topLeft = rect.topLeft,
                                size = rect.size,
                            )
                            drawRect(
                                color = boxColor,
                                topLeft = rect.topLeft,
                                size = rect.size,
                                style = Stroke(width = strokePx),
                            )
                            drawCircle(color = boxColor, radius = handleRadiusPx, center = rect.topLeft)
                            drawCircle(color = boxColor, radius = handleRadiusPx, center = rect.topRight)
                            drawCircle(color = boxColor, radius = handleRadiusPx, center = rect.bottomLeft)
                            drawCircle(color = boxColor, radius = handleRadiusPx, center = rect.bottomRight)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text(text = "Landmark name") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CosmosSecondaryButton(
                label = "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            CosmosSecondaryButton(
                label = "Clear",
                onClick = { box = null },
                enabled = current != null,
                modifier = Modifier.weight(1f),
            )
        }
        CosmosPrimaryButton(
            label = "Use box",
            onClick = {
                val rect = current ?: return@CosmosPrimaryButton
                onConfirm(nodeId, cropBoxFromRect(rect, canvasWidthPx, canvasHeightPx))
            },
            enabled = bitmap != null && current != null && title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
