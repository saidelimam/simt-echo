package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoCardRadius
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Center-bottom numeric readout (plan §8.3 Record screen, e.g. "23.4 m").
 * Tabular numerals so the width does not jitter while walking.
 */
@Composable
fun DistanceReadout(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.displayLarge.copy(
            fontFeatureSettings = "tnum",
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

/** Semantics tag for a landmark card's own image placeholder. */
const val LandmarkImageSlotTag = "LandmarkImageSlot"

/**
 * Landmark card (plan Review / Navigate screens): keyframe slot, title,
 * cue and stat chips. The image painter arrives with W2-01/W3-07; until
 * then the slot is a tinted placeholder, never a broken image.
 *
 * @param showImageSlot false when the caller already renders the crop
 * above this card (review 0013 F4: two empty slots stacked read as a
 * broken image).
 */
@Composable
fun LandmarkCard(
    title: String,
    cue: String,
    stats: String?,
    showImageSlot: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(EchoCardRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (showImageSlot) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .testTag(LandmarkImageSlotTag),
                ) {}
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = cue,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (stats != null) {
                Spacer(modifier = Modifier.height(4.dp))
                StatusChip(text = stats)
            }
        }
    }
}

/**
 * One-decimal metre formatting shared by Record, Receive and Navigate
 * ("23.4 m"). Truncates rather than rounds so the readout never shows
 * distance the walker has not covered yet.
 */
fun oneDecimal(value: Double): String {
    val tenths = (value.coerceAtLeast(0.0) * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Rounded one-decimal display for quantities that are not distances
 * covered (zoom, ratios). Sign-safe; locale-independent, unlike
 * `String.format`, which is JVM-only.
 */
fun roundedOneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    val magnitude = abs(tenths)
    return "${if (tenths < 0) "-" else ""}${magnitude / 10}.${magnitude % 10}"
}

/** Three-decimal display for the step constant (K ≈ 0.480). Same truncation pattern as [oneDecimal]. */
fun threeDecimals(value: Double): String {
    val thousandths = (value * 1000).toLong()
    val whole = thousandths / 1000
    val frac = (thousandths % 1000).toString().padStart(3, '0')
    return "$whole.$frac"
}

/**
 * Vertical route timeline (plan Review screen): one row per node chip,
 * e.g. "Turn left · 23 m". Stateless; progress highlighting is W2-06.
 */
@Composable
fun RouteTimeline(
    entries: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            StatusChip(
                text = entry,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
