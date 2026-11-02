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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.CosmosPetrol
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
 * Review route instrument (plan W8-08, freeze §2/§7): the walked path on
 * petrol map ground with the route in gold. The highlight node reads by
 * size + ring, never hue alone. Panel only — geometry stays sensor-owned
 * and read-only; this surface never edits it.
 */
@Composable
fun ReviewInstrumentMap(
    points: List<Offset>,
    highlightIndex: Int,
    modifier: Modifier = Modifier,
) {
    val gold = EchoExtras.gold
    Surface(
        shape = MaterialTheme.shapes.large,
        color = CosmosPetrol,
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
            val paddingPx = 16.dp.toPx()
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val scale = reviewMapScale(minX, maxX, minY, maxY, size.width, size.height, paddingPx)
            val toScreen = { p: Offset -> mapReviewPoint(p, minX, minY, scale, paddingPx) }
            for (i in 1 until points.size) {
                drawLine(gold, toScreen(points[i - 1]), toScreen(points[i]), strokeWidth = 6f)
            }
            points.forEachIndexed { index, point ->
                val center = toScreen(point)
                if (index == highlightIndex) {
                    drawCircle(color = gold, radius = 12f, center = center)
                    drawCircle(
                        color = gold,
                        radius = 18f,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                    )
                } else {
                    drawCircle(color = gold, radius = 8f, center = center, alpha = 0.6f)
                }
            }
        }
    }
}
