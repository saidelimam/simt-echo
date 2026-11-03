package app.simt.echo.ui.screens.navigate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

// Morph-arrow glyph drawing (plan W13-08): straight chevron with an
// angle-proportional bend, U hook, and arrival check. Pure draw-scope
// helpers shared by every motion tier — tiers only change how the
// rotation/bend arrive, never the glyphs. Every glyph carries a
// contrasting outline so it stays legible over the σ aura at any σ.

/** Outline thickness around each glyph, per side. */
private const val GLYPH_OUTLINE_DP = 2f

/** Relative luminance where black and white outlines contrast equally with the glyph. */
private const val OUTLINE_LUMINANCE_PIVOT = 0.179f

/** Outline colour for [glyph]: black or white, whichever contrasts more (always ≥ 4.5:1). */
internal fun glyphOutlineColor(glyph: Color): Color =
    if (glyph.luminance() > OUTLINE_LUMINANCE_PIVOT) Color.Black else Color.White

/** Aura tint ceiling — a halo, never a disc the glyph can hide in. */
internal const val AURA_MAX_ALPHA = 0.25f

/** Calm mode scales the aura tint down (decorative chrome only). */
private const val AURA_CALM_FACTOR = 0.35f

/** Fraction of the aura radius held at full tint when softness is 0. */
private const val AURA_SOLID_CORE = 0.6f

/** Core tint alpha of the aura: ≤ [AURA_MAX_ALPHA], dimmer when softer or calm. */
internal fun auraAlphaFor(softness01: Float, dimChrome: Boolean): Float =
    AURA_MAX_ALPHA * (if (dimChrome) AURA_CALM_FACTOR else 1f) *
        (1f - 0.5f * softness01.coerceIn(0f, 1f))

/** Radius fraction where the aura starts fading; softer σ fades from nearer the centre. */
internal fun auraSolidStopFor(softness01: Float): Float =
    AURA_SOLID_CORE * (1f - softness01.coerceIn(0f, 1f))

/**
 * σ aura: a radial-gradient halo whose extent is [radiusPx] and whose
 * falloff follows [softness01]; UNCERTAIN adds a dashed rim (mark, not
 * hue). Never drawn past the canvas.
 */
internal fun DrawScope.drawSigmaAura(
    color: Color,
    radiusPx: Float,
    softness01: Float,
    dimChrome: Boolean,
    dashed: Boolean,
) {
    val radius = radiusPx.coerceAtMost(size.minDimension / 2f)
    val tint = color.copy(alpha = auraAlphaFor(softness01, dimChrome))
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to tint,
                auraSolidStopFor(softness01) to tint,
                1f to tint.copy(alpha = 0f),
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
    )
    if (dashed) {
        drawCircle(
            color = color,
            radius = radius,
            style = Stroke(
                width = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            ),
        )
    }
}

/**
 * Honest-light visual-lock ring inside the σ aura (plan W19-01): drawn only
 * when [confidence01] ≥ 0.5, with brightness (alpha) equal to [confidence01].
 */
internal fun DrawScope.drawVisualLockRing(
    color: Color,
    auraRadiusPx: Float,
    confidence01: Float,
) {
    if (!confidence01.isFinite() || confidence01 < 0.5f) return
    val maxR = size.minDimension / 2f
    val ringRadius = (auraRadiusPx.coerceAtMost(maxR) * 0.72f).coerceAtLeast(24.dp.toPx())
    drawCircle(
        color = color.copy(alpha = confidence01.coerceIn(0.5f, 1.0f)),
        radius = ringRadius,
        style = Stroke(width = 2.dp.toPx()),
    )
}

private fun DrawScope.fillOutlined(path: Path, color: Color) {
    drawPath(
        path,
        glyphOutlineColor(color),
        style = Stroke(width = 2 * GLYPH_OUTLINE_DP.dp.toPx(), join = StrokeJoin.Round),
    )
    drawPath(path, color)
}

private fun DrawScope.strokeOutlined(path: Path, color: Color, width: Float) {
    drawPath(
        path,
        glyphOutlineColor(color),
        style = Stroke(width = width + 2 * GLYPH_OUTLINE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
    fillOutlined(chevron, color)
}

internal fun DrawScope.drawUTurn(color: Color) {
    val w = size.width
    val h = size.height
    val arc = Path().apply {
        arcTo(
            rect = Rect(Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.55f)),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = true,
        )
    }
    strokeOutlined(arc, color, width = w * 0.09f)
    val head = Path().apply {
        moveTo(w * 0.5f, h * 0.12f)
        lineTo(w * 0.68f, h * 0.3f)
        lineTo(w * 0.5f, h * 0.3f)
        lineTo(w * 0.5f, h * 0.42f)
        lineTo(w * 0.32f, h * 0.42f)
        lineTo(w * 0.32f, h * 0.3f)
        close()
    }
    fillOutlined(head, color)
}

internal fun DrawScope.drawCheck(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.28f, h * 0.55f)
        lineTo(w * 0.45f, h * 0.72f)
        lineTo(w * 0.74f, h * 0.3f)
    }
    strokeOutlined(path, color, width = w * 0.09f)
}
