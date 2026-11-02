package app.simt.echo.ui.screens.navigate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.PI

// Morph-arrow glyph drawing (plan W13-08): straight chevron with an
// angle-proportional bend, U hook, and arrival check. Pure draw-scope
// helpers shared by every motion tier — tiers only change how the
// rotation/bend arrive, never the glyphs.

internal fun wrapPiLocal(angleRad: Float): Float {
    var a = angleRad % (2f * PI.toFloat())
    if (a > PI) a -= 2f * PI.toFloat()
    if (a < -PI) a += 2f * PI.toFloat()
    return a
}

internal fun DrawScope.drawBentChevron(
    color: Color,
    bend: Float,
) {
    val w = size.width
    val h = size.height
    val tipX = w * 0.5f + bend * w * 0.22f
    val chevron = Path().apply {
        moveTo(tipX, h * 0.12f)
        lineTo(w * 0.82f, h * 0.62f)
        lineTo(w * 0.62f, h * 0.62f)
        lineTo(w * 0.62f + bend * w * 0.1f, h * 0.88f)
        lineTo(w * 0.38f + bend * w * 0.1f, h * 0.88f)
        lineTo(w * 0.38f, h * 0.62f)
        lineTo(w * 0.18f, h * 0.62f)
        close()
    }
    drawPath(chevron, color)
    drawCircle(
        color = color,
        radius = w * 0.05f,
        center = Offset(w * 0.5f, h * 0.5f),
        alpha = 0.25f,
    )
}

internal fun DrawScope.drawUTurn(color: Color) {
    val w = size.width
    val h = size.height
    drawArc(
        color = color,
        startAngle = 90f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.2f, h * 0.2f),
        size = Size(w * 0.6f, h * 0.55f),
        style = Stroke(width = (w * 0.09f), cap = StrokeCap.Round),
    )
    val head = Path().apply {
        moveTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.68f, h * 0.3f)
        lineTo(w * 0.5f, h * 0.3f)
        lineTo(w * 0.5f, h * 0.42f)
        lineTo(w * 0.32f, h * 0.42f)
        lineTo(w * 0.32f, h * 0.3f)
        close()
    }
    drawPath(head, color)
}

internal fun DrawScope.drawCheck(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.28f, h * 0.55f)
        lineTo(w * 0.45f, h * 0.72f)
        lineTo(w * 0.74f, h * 0.3f)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = w * 0.09f, cap = StrokeCap.Round),
    )
}
