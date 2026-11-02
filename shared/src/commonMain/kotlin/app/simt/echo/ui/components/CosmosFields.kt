package app.simt.echo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoSpace8
import app.simt.echo.ui.theme.EchoTouchOther

// Cosmos form fields: labels are always visible (never placeholder-only),
// every field offers a clear button once it holds text, error text is
// human copy from the caller. ≥48 dp clear targets, gold focus ring.

const val CosmosFieldTag = "CosmosField"
const val CosmosFieldClearTag = "CosmosFieldClear"

/**
 * Labelled text field with a clear button.
 *
 * @param label always-visible label (also the semantics label).
 * @param value current text; [onValueChange] receives edits and clears.
 * @param onClear invoked with empty text when the Clear button is pressed.
 */
@Composable
fun CosmosTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
) {
    val shape = MaterialTheme.shapes.small
    val extras = EchoExtras
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(text = label) },
            enabled = enabled,
            isError = isError,
            singleLine = singleLine,
            shape = shape,
            supportingText = {
                when {
                    isError && errorText != null -> Text(text = errorText)
                    supportingText != null -> Text(text = supportingText)
                }
            },
            trailingIcon = {
                if (value.isNotEmpty() && enabled) {
                    CosmosTertiaryButton(
                        label = "Clear",
                        onClick = {
                            onValueChange("")
                            onClear?.invoke()
                        },
                        modifier = Modifier
                            .sizeIn(minWidth = EchoTouchOther, minHeight = EchoTouchOther)
                            .testTag(CosmosFieldClearTag),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier)
                .testTag("$CosmosFieldTag:$label"),
        )
    }
}

/** Spacing token re-export for field stacks; fields separate by 8 dp. */
val CosmosFieldSpacing = EchoSpace8
