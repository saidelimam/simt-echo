package app.simt.echo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.platform.TransferProgress
import kotlin.math.roundToInt

const val TransferProgressTag = "TransferProgress"

private const val PARTICLE_COUNT = 6

/** Trail spacing behind the lead particle, in track units. */
private const val TRAIL_GAP = 0.06f

/**
 * Particle offsets behind the lead, as a pure function of the measured
 * [fraction]: the lead sits exactly where the bytes are, the trail marks
 * where they just were. No clock is read here, so zero bytes/s is
 * structurally frozen — nothing on this strip moves without bytes.
 */
fun transferParticleOffsets(fraction: Float, count: Int = PARTICLE_COUNT): List<Float> =
    List(count) { i -> (fraction - i * TRAIL_GAP).coerceIn(0f, 1f) }
        .filter { it > 0f }

/**
 * Spoken state of the strip: the percent when the total is known, "in
 * progress" when it is not, and "stalled" once no bytes are moving —
 * the same signal that dims the particles, never an animation.
 */
fun transferDescription(progress: TransferProgress, bytesPerSecond: Double): String {
    val base = if (progress.totalBytes > 0L) {
        "Transfer ${(progress.fraction * 100f).roundToInt()} percent"
    } else {
        "Transfer in progress"
    }
    return if (progress.isComplete || bytesPerSecond > 0.0) base else "$base, stalled"
}

/**
 * Transfer particle flow (plan W13-14, honest light): two phone glyphs
 * with a track between them. The filled track is [progress.fraction] and
 * the particles ride it; their brightness follows the measured
 * [bytesPerSecond] — dim when stalled, lit while bytes move. Completed
 * transfers rest lit at full.
 */
@Composable
fun TransferParticles(
    progress: TransferProgress,
    bytesPerSecond: Double,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceVariant
    val filled = scheme.primary
    val glyph = scheme.onSurface
    val lit = scheme.tertiary.copy(alpha = 1f)
    val dim = scheme.tertiary.copy(alpha = 0.35f)
    val dots = if (bytesPerSecond > 0.0 || progress.isComplete) lit else dim
    val description = transferDescription(progress, bytesPerSecond)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .testTag(TransferProgressTag)
            .semantics { contentDescription = description },
    ) {
        val phoneW = 26.dp.toPx()
        val phoneH = 48.dp.toPx()
        val cy = size.height / 2f
        val corner = 6.dp.toPx()
        val stroke = 2.dp.toPx()
        // Sender glyph (left) and receiver glyph (right).
        drawRoundRect(
            color = glyph,
            topLeft = Offset(0f, cy - phoneH / 2f),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = glyph,
            topLeft = Offset(size.width - phoneW, cy - phoneH / 2f),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = stroke),
        )
        // Track between the glyphs, filled to the measured fraction.
        val x0 = phoneW + 8.dp.toPx()
        val x1 = size.width - phoneW - 8.dp.toPx()
        val bar = 4.dp.toPx()
        drawLine(track, Offset(x0, cy), Offset(x1, cy), strokeWidth = bar, cap = StrokeCap.Round)
        val head = x0 + (x1 - x0) * progress.fraction
        if (head > x0) {
            drawLine(filled, Offset(x0, cy), Offset(head, cy), strokeWidth = bar, cap = StrokeCap.Round)
        }
        // Particles ride the measured offsets — never ahead of the bytes.
        transferParticleOffsets(progress.fraction).forEach { offset ->
            drawCircle(dots, radius = 3.dp.toPx(), center = Offset(x0 + (x1 - x0) * offset, cy))
        }
    }
}
