package app.simt.echo.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// ECHO icon set (plan W13-02): the few chrome and deck marks the shell
// needs, drawn on a 24-unit grid with 2-unit round strokes so they share
// the Echo Step mark's soft geometry (luminous-freeze §2). Icons are
// tinted by the caller (Icon uses LocalContentColor); content
// descriptions live on the command, never on the vector.

private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    ).build()

object EchoIcons {
    /** Back: a step back along the path. */
    val Back: ImageVector = icon("EchoBack") {
        moveTo(19f, 12f)
        lineTo(5f, 12f)
        moveTo(11f, 6f)
        lineTo(5f, 12f)
        lineTo(11f, 18f)
    }

    /** Close: dismiss a transient surface. */
    val Close: ImageVector = icon("EchoClose") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }

    /** Settings: three tuning sliders. */
    val Settings: ImageVector = icon("EchoSettings") {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(20f, 18f)
        // Slider knobs as short open arcs over each rail.
        moveTo(9f, 4f); lineTo(9f, 8f)
        moveTo(15f, 10f); lineTo(15f, 14f)
        moveTo(7f, 16f); lineTo(7f, 20f)
    }

    /** Record: a step with its first echo. */
    val Record: ImageVector = icon("EchoRecord") {
        // Inner dot (the step) as a tiny closed circle path.
        moveTo(12f, 9.5f)
        arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 14.5f)
        arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 9.5f)
        close()
        // The echo: an open ring around it.
        moveTo(17.66f, 6.34f)
        arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 6.34f, y1 = 6.34f)
    }

    /** Receive: a scan frame waiting for a code. */
    val Receive: ImageVector = icon("EchoReceive") {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
        moveTo(8f, 12f); lineTo(16f, 12f)
    }
}
