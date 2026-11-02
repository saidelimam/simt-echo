package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace24

// Shared canopy modal policy (repo rule: no raw AlertDialog /
// ModalBottomSheet / Dialog bypass outside this file). Dialogs and sheets
// render on the canopy overlay shape (28 dp) with the horizon top-light via
// CanopyPanel in their screen call sites; here the M3 surface carries the
// overlay shape from the frozen theme. Screens W8-06…W8-11 migrate to these.

const val CosmosDialogTag = "CosmosDialog"
const val CosmosSheetTag = "CosmosSheet"

/**
 * Canopy confirm dialog: title heading, human body, one primary path and
 * an optional secondary. Destructive confirms pass [destructive] so the
 * primary renders on the danger channel.
 */
@Composable
fun CosmosDialog(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
        },
        confirmButton = {
            if (destructive) {
                CosmosDestructiveButton(label = primaryLabel, onClick = onPrimary)
            } else {
                CosmosPrimaryButton(label = primaryLabel, onClick = onPrimary)
            }
        },
        dismissButton = {
            if (secondaryLabel != null && onSecondary != null) {
                CosmosSecondaryButton(label = secondaryLabel, onClick = onSecondary)
            }
        },
        modifier = modifier.testTag(CosmosDialogTag),
    )
}

/**
 * Canopy sheet: title heading plus caller content (form, consent copy,
 * import preview). The only bottom-sheet surface screens may use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CosmosSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag(CosmosSheetTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EchoSpace24),
            verticalArrangement = Arrangement.spacedBy(EchoSpace12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            content()
            if (primaryLabel != null && onPrimary != null) {
                CosmosPrimaryButton(
                    label = primaryLabel,
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(EchoSpace16))
        }
    }
}

/** Horizontal primary/secondary pair for inline (non-modal) confirms. */
@Composable
fun CosmosConfirmRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    destructive: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(EchoSpace12),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (secondaryLabel != null && onSecondary != null) {
            CosmosSecondaryButton(
                label = secondaryLabel,
                onClick = onSecondary,
                modifier = Modifier.weight(1f),
            )
        }
        if (destructive) {
            CosmosDestructiveButton(
                label = primaryLabel,
                onClick = onPrimary,
                modifier = Modifier.weight(1f),
            )
        } else {
            CosmosPrimaryButton(
                label = primaryLabel,
                onClick = onPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
