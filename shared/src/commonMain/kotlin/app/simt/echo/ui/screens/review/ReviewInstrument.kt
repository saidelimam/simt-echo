package app.simt.echo.ui.screens.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoExtras
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Semantics tag for the Review route instrument (host UI gate target). */
const val ReviewMapTag = "ReviewMap"

/**
 * Pure fit of a walked series into a [boxWidth]×[boxHeight] canvas with
 * [paddingPx] on every side. Returns 1f for empty or degenerate input
 * instead of dividing by zero, so the instrument never collapses.
 */
fun reviewMapScale(
    minX: Float,
    maxX: Float,
    minY: Float,
    maxY: Float,
    boxWidth: Float,
    boxHeight: Float,
    paddingPx: Float,
): Float {
    val spanX = maxX - minX
    val spanY = maxY - minY
    if (spanX <= 0f && spanY <= 0f) return 1f
    val scaleX = if (spanX > 0f) (boxWidth - 2f * paddingPx) / spanX else Float.MAX_VALUE
    val scaleY = if (spanY > 0f) (boxHeight - 2f * paddingPx) / spanY else Float.MAX_VALUE
    return minOf(scaleX, scaleY).coerceAtLeast(0f)
}

/** Pure walked-point → instrument-canvas mapping for [reviewMapScale]. */
fun mapReviewPoint(
    point: Offset,
    minX: Float,
    minY: Float,
    scale: Float,
    paddingPx: Float,
): Offset = Offset(
    x = paddingPx + (point.x - minX) * scale,
    y = paddingPx + (point.y - minY) * scale,
)

/**
 * Pure landscape + centered layout for a walked series inside a
 * [boxWidth]×[boxHeight] box with [paddingPx] on every side. When the
 * vertical span exceeds the horizontal span, the series is rotated 90° so
 * the long axis lies horizontally across the wide container, then scaled and
 * centered on both axes.
 */
fun layoutReviewPoints(
    points: List<Offset>,
    boxWidth: Float,
    boxHeight: Float,
    paddingPx: Float,
): List<Offset> {
    if (points.isEmpty()) return emptyList()
    val rawMinX = points.minOf { it.x }
    val rawMaxX = points.maxOf { it.x }
    val rawMinY = points.minOf { it.y }
    val rawMaxY = points.maxOf { it.y }
    val rotateToHorizontal = (rawMaxY - rawMinY) > (rawMaxX - rawMinX)
    val oriented = if (rotateToHorizontal) {
        points.map { Offset(-it.y, it.x) }
    } else {
        points
    }
    val minX = oriented.minOf { it.x }
    val maxX = oriented.maxOf { it.x }
    val minY = oriented.minOf { it.y }
    val maxY = oriented.maxOf { it.y }
    val scale = reviewMapScale(minX, maxX, minY, maxY, boxWidth, boxHeight, paddingPx)
    val spanX = maxX - minX
    val spanY = maxY - minY
    val offsetX = ((boxWidth - spanX * scale) * 0.5f).coerceAtLeast(paddingPx)
    val offsetY = ((boxHeight - spanY * scale) * 0.5f).coerceAtLeast(paddingPx)
    return oriented.map { p ->
        Offset(
            x = offsetX + (p.x - minX) * scale,
            y = offsetY + (p.y - minY) * scale,
        )
    }
}

/**
 * Review route instrument (plan W8-08, freeze §2/§7): the walked path on
 * the theme's hull container with distinct Start (cyan), Active spot (gold
 * reticle), and End/Destination (rose/ember target) markers, directional
 * chevrons along the trajectory, and an illuminated progress trail up to the
 * selected spot.
 */
@Composable
fun ReviewInstrumentMap(
    points: List<Offset>,
    highlightIndex: Int,
    modifier: Modifier = Modifier,
    nodePathIndices: List<Int> = emptyList(),
    selectedPositionLabel: String? = null,
    onSelectNodeIndex: ((Int) -> Unit)? = null,
) {
    val containerTop = MaterialTheme.colorScheme.surfaceContainerLow
    val containerBottom = MaterialTheme.colorScheme.surfaceContainerHigh
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val trackMuted = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val trackActive = MaterialTheme.colorScheme.primary
    val startColor = EchoExtras.cyan
    val endColor = EchoExtras.rose
    val nodeColor = EchoExtras.emberSoft
    val highlightColor = EchoExtras.gold
    val surfaceColor = MaterialTheme.colorScheme.surface

    var lastMapped by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerBottom,
        modifier = modifier
            .fillMaxWidth()
            .height(188.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .testTag(ReviewMapTag)
            .semantics {
                contentDescription = when {
                    points.isEmpty() -> "Route map unavailable"
                    selectedPositionLabel != null -> "Route map, selected $selectedPositionLabel"
                    else -> "Route map"
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(containerTop, containerBottom))),
        ) {
            // Top HUD legend bar: makes Start, Active spot, and End unmistakable.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MapLegendItem(color = startColor, label = "Start")
                    MapLegendItem(color = highlightColor, label = "Selected")
                    MapLegendItem(color = endColor, label = "End")
                }
                if (selectedPositionLabel != null) {
                    Text(
                        text = selectedPositionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = highlightColor,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (points.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = "No walked path recorded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(points, nodePathIndices, onSelectNodeIndex) {
                                if (onSelectNodeIndex == null || nodePathIndices.isEmpty()) return@pointerInput
                                detectTapGestures { tap ->
                                    val mapped = lastMapped
                                    if (mapped.isEmpty()) return@detectTapGestures
                                    val best = nodePathIndices.indices.minByOrNull { idx ->
                                        val ptIndex = nodePathIndices[idx].coerceIn(0, mapped.lastIndex)
                                        val p = mapped[ptIndex]
                                        hypot(tap.x - p.x, tap.y - p.y)
                                    }
                                    if (best != null) {
                                        onSelectNodeIndex(best)
                                    }
                                }
                            },
                    ) {
                        val paddingPx = 28.dp.toPx()
                        val mapped = layoutReviewPoints(points, size.width, size.height, paddingPx)
                        lastMapped = mapped
                        if (mapped.isEmpty()) return@Canvas

                        // 1. Tactical instrument grid
                        drawInstrumentGrid(gridColor)

                        val safeHighlight = highlightIndex.coerceIn(0, mapped.lastIndex)

                        // 2. Full route polyline (glow + dashed future track)
                        val fullPath = Path().apply {
                            moveTo(mapped.first().x, mapped.first().y)
                            for (i in 1 until mapped.size) {
                                lineTo(mapped[i].x, mapped[i].y)
                            }
                        }
                        drawPath(
                            path = fullPath,
                            color = trackActive.copy(alpha = 0.14f),
                            style = Stroke(width = 16f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                        drawPath(
                            path = fullPath,
                            color = trackMuted,
                            style = Stroke(
                                width = 5f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                            ),
                        )

                        // 3. Illuminated progress path from Start (0) to current selected spot
                        if (safeHighlight > 0) {
                            val activePath = Path().apply {
                                moveTo(mapped.first().x, mapped.first().y)
                                for (i in 1..safeHighlight) {
                                    lineTo(mapped[i].x, mapped[i].y)
                                }
                            }
                            drawPath(
                                path = activePath,
                                color = trackActive,
                                style = Stroke(width = 6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }

                        // 4. Directional chevrons showing walk direction Start -> End
                        drawDirectionChevrons(mapped, highlightColor.copy(alpha = 0.75f))

                        // 5. Intermediate node waypoints
                        val checkpointIndices = if (nodePathIndices.isNotEmpty()) {
                            nodePathIndices.map { it.coerceIn(0, mapped.lastIndex) }.distinct()
                        } else {
                            emptyList()
                        }
                        for (ptIdx in checkpointIndices) {
                            if (ptIdx == 0 || ptIdx == mapped.lastIndex || ptIdx == safeHighlight) continue
                            val c = mapped[ptIdx]
                            drawCircle(color = surfaceColor, radius = 7f, center = c)
                            drawCircle(color = nodeColor, radius = 5f, center = c)
                        }

                        // 6. Start marker (index 0): Cyan beacon with outer ring
                        val startPt = mapped.first()
                        drawCircle(color = startColor.copy(alpha = 0.24f), radius = 16f, center = startPt)
                        drawCircle(color = surfaceColor, radius = 9f, center = startPt)
                        drawCircle(color = startColor, radius = 6.5f, center = startPt)

                        // 7. End / Destination marker (last index): Rose bullseye diamond/target
                        if (mapped.size > 1) {
                            val endPt = mapped.last()
                            drawCircle(color = endColor.copy(alpha = 0.24f), radius = 16f, center = endPt)
                            drawCircle(
                                color = endColor,
                                radius = 11f,
                                center = endPt,
                                style = Stroke(width = 2.5f),
                            )
                            drawCircle(color = surfaceColor, radius = 6f, center = endPt)
                            drawCircle(color = endColor, radius = 4f, center = endPt)
                        }

                        // 8. Active selected cursor reticle (Gold ring + crosshair ticks)
                        val activePt = mapped[safeHighlight]
                        drawCircle(
                            color = highlightColor.copy(alpha = 0.18f),
                            radius = 24f,
                            center = activePt,
                        )
                        drawCircle(
                            color = highlightColor,
                            radius = 16f,
                            center = activePt,
                            style = Stroke(width = 3f),
                        )
                        val tickInner = 18f
                        val tickOuter = 23f
                        drawLine(
                            color = highlightColor,
                            start = Offset(activePt.x - tickOuter, activePt.y),
                            end = Offset(activePt.x - tickInner, activePt.y),
                            strokeWidth = 2.5f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = highlightColor,
                            start = Offset(activePt.x + tickInner, activePt.y),
                            end = Offset(activePt.x + tickOuter, activePt.y),
                            strokeWidth = 2.5f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = highlightColor,
                            start = Offset(activePt.x, activePt.y - tickOuter),
                            end = Offset(activePt.x, activePt.y - tickInner),
                            strokeWidth = 2.5f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = highlightColor,
                            start = Offset(activePt.x, activePt.y + tickInner),
                            end = Offset(activePt.x, activePt.y + tickOuter),
                            strokeWidth = 2.5f,
                            cap = StrokeCap.Round,
                        )
                        drawCircle(color = surfaceColor, radius = 8f, center = activePt)
                        drawCircle(color = highlightColor, radius = 5.5f, center = activePt)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLegendItem(
    color: Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.size(1.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DrawScope.drawInstrumentGrid(gridColor: Color) {
    val cols = 4
    val rows = 3
    for (c in 1 until cols) {
        val x = size.width * c / cols
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
    }
    for (r in 1 until rows) {
        val y = size.height * r / rows
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawDirectionChevrons(
    mapped: List<Offset>,
    chevronColor: Color,
) {
    if (mapped.size < 2) return
    var accumulated = 0f
    val spacingPx = 72f
    var nextAt = spacingPx * 0.6f
    val wingLen = 7f
    val wingAngle = 2.55f // ~146 degrees backward

    for (i in 1 until mapped.size) {
        val a = mapped[i - 1]
        val b = mapped[i]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val segLen = hypot(dx, dy)
        if (segLen < 1f) continue
        val angle = atan2(dy, dx)
        while (accumulated + segLen >= nextAt) {
            val t = (nextAt - accumulated) / segLen
            val tip = Offset(a.x + dx * t, a.y + dy * t)
            val leftWing = Offset(
                tip.x + wingLen * cos(angle + wingAngle),
                tip.y + wingLen * sin(angle + wingAngle),
            )
            val rightWing = Offset(
                tip.x + wingLen * cos(angle - wingAngle),
                tip.y + wingLen * sin(angle - wingAngle),
            )
            drawLine(chevronColor, leftWing, tip, strokeWidth = 2.5f, cap = StrokeCap.Round)
            drawLine(chevronColor, rightWing, tip, strokeWidth = 2.5f, cap = StrokeCap.Round)
            nextAt += spacingPx
        }
        accumulated += segLen
    }
}
