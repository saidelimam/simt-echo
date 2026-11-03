package app.simt.echo.ui.screens.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoExtras

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
 * the theme's hull container with the route in ember/primary and the active
 * node in gold. The highlight node reads by size + ring, never hue alone.
 * Panel only — geometry stays sensor-owned and read-only; this surface never
 * edits it.
 */
@Composable
fun ReviewInstrumentMap(
    points: List<Offset>,
    highlightIndex: Int,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val trackColor = MaterialTheme.colorScheme.primary
    val nodeColor = EchoExtras.emberSoft
    val highlightColor = EchoExtras.gold
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .testTag(ReviewMapTag)
            .semantics {
                contentDescription = if (points.isEmpty()) "Route map unavailable" else "Route map"
            },
    ) {
        Canvas(modifier = Modifier) {
            if (points.isEmpty()) return@Canvas
            val paddingPx = 20.dp.toPx()
            val mapped = layoutReviewPoints(points, size.width, size.height, paddingPx)
            for (i in 1 until mapped.size) {
                drawLine(
                    color = trackColor,
                    start = mapped[i - 1],
                    end = mapped[i],
                    strokeWidth = 6f,
                    cap = StrokeCap.Round,
                )
            }
            mapped.forEachIndexed { index, center ->
                if (index == highlightIndex) {
                    drawCircle(color = highlightColor, radius = 12f, center = center)
                    drawCircle(
                        color = highlightColor,
                        radius = 18f,
                        center = center,
                        style = Stroke(width = 3f),
                    )
                } else {
                    drawCircle(color = nodeColor, radius = 7f, center = center, alpha = 0.8f)
                }
            }
        }
    }
}
