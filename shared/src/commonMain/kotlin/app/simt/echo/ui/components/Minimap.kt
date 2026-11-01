package app.simt.echo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoCardRadius

/**
 * Scales a walked [points] series into [boxWidth]×[boxHeight] with the
 * plan §8.1 16 dp padding. Pure so segmenter output and rendering agree.
 * Returns 1f for empty or degenerate input instead of dividing by zero.
 */
internal fun fitScale(
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

/**
 * Route minimap (plan Review screen): path scaled to fit with 16 dp
 * padding, nodes as dots, the highlighted node in the accent color.
 */
@Composable
fun Minimap(
    points: List<Offset>,
    highlightIndex: Int,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(EchoCardRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        Canvas(modifier = Modifier) {
            if (points.isEmpty()) return@Canvas
            val paddingPx = 16.dp.toPx()
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val scale = fitScale(minX, maxX, minY, maxY, size.width, size.height, paddingPx)
            val toScreen = { p: Offset ->
                Offset(
                    x = paddingPx + (p.x - minX) * scale,
                    y = paddingPx + (p.y - minY) * scale,
                )
            }
            for (i in 1 until points.size) {
                drawLine(onSurface, toScreen(points[i - 1]), toScreen(points[i]), strokeWidth = 6f, alpha = 0.6f)
            }
            points.forEachIndexed { index, point ->
                drawCircle(
                    color = if (index == highlightIndex) primary else onSurface,
                    radius = if (index == highlightIndex) 12f else 8f,
                    center = toScreen(point),
                )
            }
        }
    }
}
