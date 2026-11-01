package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoPillRadius

/**
 * Small status pill: offline badge, ±σ chip, uncertainty banner
 * (plan §8.3 Navigate screen). [dot] colors the state; null hides it.
 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
) {
    Surface(
        shape = RoundedCornerShape(EchoPillRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (dot != null) {
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(EchoPillRadius)),
                ) {
                    drawCircle(dot)
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
