package app.simt.echo.ui.screens.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.simt.echo.ai.MaskSubpath
import kotlin.math.roundToInt

// Pure geometry and draw helpers for the Review story surfaces (plan
// W13-05): the keyframe inspector's fit + pan bounds, and the AI mask
// outline hoisted out of the per-frame trace.

/** Largest rect with [content]'s aspect that fits [viewport], centred (letterboxed). */
internal fun fitContain(content: Size, viewport: Size): Rect {
    if (content.width <= 0f || content.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) {
        return Rect.Zero
    }
    val scale = minOf(viewport.width / content.width, viewport.height / content.height)
    val size = Size(content.width * scale, content.height * scale)
    return Rect(
        offset = Offset((viewport.width - size.width) / 2f, (viewport.height - size.height) / 2f),
        size = size,
    )
}

/**
 * Pan limited to the zoomed bounds: [content] scaled by [scale] about the
 * viewport centre may slide only until its edge meets the viewport edge,
 * so at 1× (content fits) there is nothing to pan.
 */
internal fun clampPan(offset: Offset, scale: Float, content: Size, viewport: Size): Offset {
    val maxX = ((content.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((content.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** Draws [image] letterboxed at its own aspect — never stretched to the canvas. */
internal fun DrawScope.drawFitted(image: ImageBitmap) {
    val fit = fitContain(Size(image.width.toFloat(), image.height.toFloat()), size)
    drawImage(
        image = image,
        dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
        dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
    )
}

/**
 * The SAM mask as paths scaled to the canvas. Built once per canvas size
 * (not per animation frame), so the trace only measures and trims.
 */
internal class MaskOutline(
    private val mask: List<MaskSubpath>,
    private val maskW: Int,
    private val maskH: Int,
) {
    private var builtFor: Size = Size.Unspecified
    private var built: List<Path> = emptyList()

    fun pathsFor(size: Size): List<Path> {
        if (size != builtFor) {
            val sx = size.width / maskW.toFloat()
            val sy = size.height / maskH.toFloat()
            built = mask.map { subpath ->
                Path().apply {
                    subpath.points.forEachIndexed { i, point ->
                        if (i == 0) moveTo(point.x * sx, point.y * sy) else lineTo(point.x * sx, point.y * sy)
                    }
                    if (subpath.closed) close()
                }
            }
            builtFor = size
        }
        return built
    }
}
