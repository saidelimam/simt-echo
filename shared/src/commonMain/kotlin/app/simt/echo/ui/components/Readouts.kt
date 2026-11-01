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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoCardRadius

/**
 * Center-bottom numeric readout (plan §8.3 Record screen, e.g. "23.4 m").
 * Tabular numerals so the width does not jitter while walking.
 */
@Composable
fun DistanceReadout(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge.copy(
            fontFeatureSettings = "tnum",
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * Landmark card (plan Review / Navigate screens): keyframe slot, title,
 * cue and stat chips. The image painter arrives with W2-01/W3-07; until
 * then the slot is a tinted placeholder, never a broken image.
 */
@Composable
fun LandmarkCard(
    title: String,
    cue: String,
    stats: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(EchoCardRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {}
            Spacer(modifier = Modifier.height(8.dp))
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
