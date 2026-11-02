package app.simt.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.atmosphere.LocalEchoRipples
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoTouchOther

// Cosmos command library (freeze §7, Cosmos §8.1): exactly five commands.
// Ember→rose gradient primary (one per region), secondary outline, tertiary
// text, destructive danger fill, icon-only command. Loading semantics: a
// loading command shows progress, blocks clicks, and names the state for
// screen readers. Every command is ≥48×48 dp [P-R C19] with a gold focus
// ring that is never glow-only (border + tag, asserted in tests).

const val CosmosFocusRingTag = "CosmosFocusRing"
const val CosmosLoadingTag = "CosmosLoading"
const val CosmosButtonTag = "CosmosButton"
/** State description set alongside the gold border when a command holds focus. */
const val CosmosFocusedState = "CosmosFocused"

/** Gold focus ring: 2 dp border + a tagged node tests can assert. */
internal const val CosmosFocusBorderWidth = 2

/**
 * Primary command: the ONE action per region (freeze §7 Ember grammar).
 * Ember→rose gradient fill, action-ink content.
 */
@Composable
fun CosmosPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = MaterialTheme.shapes.small
    val extras = EchoExtras
    val gradient = remember(extras.ember, extras.rose) {
        Brush.horizontalGradient(listOf(extras.ember, extras.rose))
    }
    var focused by remember { mutableStateOf(false) }
    // Ripple motif (luminous-freeze §4): a press sends a ripple across the
    // echo field from this command's centre. Position is kept outside
    // state — reading it never recomposes.
    val ripples = LocalEchoRipples.current
    val centerInRoot = remember { FloatArray(2) }
    Surface(
        onClick = {
            if (!loading) {
                ripples?.emit(Offset(centerInRoot[0], centerInRoot[1]))
                onClick()
            }
        },
        enabled = enabled && !loading,
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val center = coordinates.boundsInRoot().center
                centerInRoot[0] = center.x
                centerInRoot[1] = center.y
            }
            .sizeIn(minWidth = EchoTouchOther, minHeight = EchoTouchOther)
            .background(gradient, shape)
            .testTag("$CosmosButtonTag:$label")
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier)
            .then(if (focused) Modifier.semantics { stateDescription = CosmosFocusedState } else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag(CosmosLoadingTag)
                        .semantics { contentDescription = "$label loading" },
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier,
                )
            }
        }
    }
}

/** Secondary command: boundary outline, pairs with the primary. Never two primaries. */
@Composable
fun CosmosSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = MaterialTheme.shapes.small
    val extras = EchoExtras
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled && !loading,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier
            .sizeIn(minWidth = EchoTouchOther, minHeight = EchoTouchOther)
            .testTag("$CosmosButtonTag:$label")
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier)
            .then(if (focused) Modifier.semantics { stateDescription = CosmosFocusedState } else Modifier),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(24.dp)
                    .testTag(CosmosLoadingTag)
                    .semantics { contentDescription = "$label loading" },
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier,
            )
        }
    }
}

/** Tertiary command: text only, for low-emphasis actions (Back, Not now). */
@Composable
fun CosmosTertiaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = MaterialTheme.shapes.small
    val extras = EchoExtras
    var focused by remember { mutableStateOf(false) }
    TextButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled && !loading,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier
            .sizeIn(minWidth = EchoTouchOther, minHeight = EchoTouchOther)
            .testTag("$CosmosButtonTag:$label")
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier)
            .then(if (focused) Modifier.semantics { stateDescription = CosmosFocusedState } else Modifier),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(24.dp)
                    .testTag(CosmosLoadingTag)
                    .semantics { contentDescription = "$label loading" },
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier,
            )
        }
    }
}

/** Destructive command: danger fill, always icon + text at the call site, never hue-only. */
@Composable
fun CosmosDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = MaterialTheme.shapes.small
    val extras = EchoExtras
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled && !loading,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier
            .sizeIn(minWidth = EchoTouchOther, minHeight = EchoTouchOther)
            .testTag("$CosmosButtonTag:$label")
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, extras.gold, shape) else Modifier)
            .then(if (focused) Modifier.semantics { stateDescription = CosmosFocusedState } else Modifier),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .size(24.dp)
                    .testTag(CosmosLoadingTag)
                    .semantics { contentDescription = "$label loading" },
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier,
            )
        }
    }
}

/**
 * Icon-only command: 48 dp target, caller supplies the mark and its
 * content description (null only when decorative, per the a11y rules).
 */
@Composable
fun CosmosIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentDescription: String? = null,
    icon: @Composable () -> Unit,
) {
    val extras = EchoExtras
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled && !loading,
        modifier = modifier
            .size(EchoTouchOther)
            .testTag("$CosmosButtonTag:icon")
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.border(2.dp, extras.gold, MaterialTheme.shapes.small)
                } else {
                    Modifier
                },
            )
            .then(if (focused) Modifier.semantics { stateDescription = CosmosFocusedState } else Modifier)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(24.dp)
                    .testTag(CosmosLoadingTag),
            )
        } else {
            icon()
        }
    }
}

/**
 * 88 dp round capture action (freeze §4 [ECHO-local] carried from EchoRecordButton).
 * Content is a short label so screen readers name the action.
 */
@Composable
fun CosmosRecordButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val extras = EchoExtras
    val gradient = remember(extras.ember, extras.rose) {
        Brush.horizontalGradient(listOf(extras.ember, extras.rose))
    }
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { if (!loading) onClick() },
        enabled = enabled && !loading,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .size(88.dp)
            .background(gradient, androidx.compose.foundation.shape.CircleShape)
            .testTag("$CosmosButtonTag:$label")
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.border(
                        2.dp,
                        extras.gold,
                        androidx.compose.foundation.shape.CircleShape,
                    )
                } else {
                    Modifier
                },
            )
            .then(if (focused) Modifier.semantics { stateDescription = CosmosFocusedState } else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag(CosmosLoadingTag)
                        .semantics { contentDescription = "$label loading" },
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier,
                )
            }
        }
    }
}
