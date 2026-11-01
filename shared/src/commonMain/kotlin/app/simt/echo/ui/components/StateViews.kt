package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Generic empty state: title plus one clear action (plan W4-04). Screens
 * pass their own copy; layout, heading semantics and the single primary
 * action live here so empty states never drift per screen.
 */
@Composable
fun EmptyState(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) {
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
        }
        EchoButton(text = actionLabel, onClick = onAction, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Generic error state: message plus one clear action and an optional
 * secondary (plan W4-04). Reuses [EchoButton]/[PillButton] — never a third
 * button variant.
 */
@Composable
fun ErrorState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Assertive
            },
        )
        EchoButton(text = actionLabel, onClick = onAction, modifier = Modifier.fillMaxWidth())
        if (secondaryLabel != null && onSecondary != null) {
            PillButton(text = secondaryLabel, onClick = onSecondary, modifier = Modifier.fillMaxWidth())
        }
    }
}
