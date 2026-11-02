package app.simt.echo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16

// Cosmos record/row grammar (Cosmos §6.5 collections): rows separated by
// 1 dp `line` dividers on the continuous void — never nested cards.
// Badges are shape + text (Own/Received/Draft, offline marks), never
// hue-only.

const val CosmosRecordDividerTag = "CosmosRecordDivider"

/**
 * One record row: title (heading), optional subtitle metadata, badge texts,
 * and an optional trailing slot. Rows are pressable only when [onClick] is
 * given; dividers between rows are drawn by [CosmosRecordRows].
 */
@Composable
fun CosmosRecordRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badges: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val extras = EchoExtras
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier,
            )
            .padding(horizontal = EchoSpace16, vertical = EchoSpace12)
            .testTag("CosmosRecord:$title"),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (badges.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    badges.forEach { badge ->
                        CosmosBadge(text = badge)
                    }
                }
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/** Rows joined by line dividers — the only way records stack. */
@Composable
fun CosmosRecordRows(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.testTag(CosmosRecordDividerTag),
                )
            }
            row()
        }
    }
}

/** Small shape + text badge (provenance, offline-ready). Never hue-only. */
@Composable
fun CosmosBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
