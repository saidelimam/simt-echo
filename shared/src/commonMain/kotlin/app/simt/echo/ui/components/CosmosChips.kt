package app.simt.echo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoTouchOther

// Cosmos chips & segmented controls (freeze §4 control 8 dp, §7 accents).
// Selection is shape + mark + text, never hue-only. Every chip target is
// ≥48×48 dp [P-R C19] with the same gold focus ring as the commands.

const val CosmosChipTag = "CosmosChip"
const val CosmosSegmentTag = "CosmosSegment"

/**
 * Selectable chip: leading mark when selected ("✓" + shape change, never
 * hue-only), label text, optional supporting icon slot.
 */
@Composable
fun CosmosChip(
    label: String,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val extras = EchoExtras
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { onSelect(!selected) },
        enabled = enabled,
        selected = selected,
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .sizeIn(minWidth = EchoTouchOther, minHeight = EchoTouchOther)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
            }
            .testTag("$CosmosChipTag:$label"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .then(if (focused) Modifier.testTag(CosmosFocusRingTag) else Modifier)
                        // Selection is already exposed as state on the surface;
                        // the mark is decorative (review 0013 F2).
                        .clearAndSetSemantics {},
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Single-select segmented control: one row, exactly one option selected.
 * Options are icon + text at the call site where meaning needs it.
 */
@Composable
fun CosmosSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val extras = EchoExtras
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .selectableGroup()
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            var focused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onSelect(index) },
                enabled = enabled,
                selected = selected,
                shape = shape,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = EchoTouchOther)
                    .onFocusChanged { focused = it.isFocused }
                    .then(if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = selected
                    }
                    .testTag("$CosmosSegmentTag:$option"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    if (selected) {
                        Text(
                            text = "✓ ",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.then(
                            if (focused) Modifier.testTag(CosmosFocusRingTag) else Modifier,
                        ),
                    )
                }
            }
        }
    }
}
