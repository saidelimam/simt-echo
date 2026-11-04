package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace16

// Cosmos banners & toast (Cosmos §8 feedback): inline banners for
// persistent states (offline, relay, AI-unavailable), toast for transient
// confirmations ("Saved", "Offer stopped"). Kind is mark + text, never
// hue-only.

const val CosmosBannerTag = "CosmosBanner"

/**
 * Banner kinds with a text mark so kind never rides on hue alone. The mark
 * is decorative on screen and spoken as [spokenLabel], never as the raw
 * character (review 0013 F2).
 */
enum class CosmosBannerKind(val mark: String, val spokenLabel: String) {
    Info("i", "Information"),
    Warning("!", "Warning"),
    Error("×", "Error"),
    Success("✓", "Success"),
}

/**
 * Inline banner: mark + message + optional action. Persistent states live
 * here; transient confirmations use [CosmosToastHost].
 */
@Composable
fun CosmosBanner(
    message: String,
    modifier: Modifier = Modifier,
    kind: CosmosBannerKind = CosmosBannerKind.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .testTag("$CosmosBannerTag:${kind.name}")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EchoSpace12),
            modifier = Modifier.padding(horizontal = EchoSpace16, vertical = EchoSpace12),
        ) {
            Text(
                text = kind.mark,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = kind.spokenLabel
                },
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                CosmosTertiaryButton(label = actionLabel, onClick = onAction)
            }
        }
    }
}

/** Transient confirmations ride a standard snackbar host; copy stays human. */
@Composable
fun CosmosToastHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier)
}

/** Single toast state per screen; screens show human confirmations only. */
@Composable
fun rememberCosmosToastState(): SnackbarHostState = remember { SnackbarHostState() }
