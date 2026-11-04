package app.simt.echo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace24

// Cosmos §8.9 state family: the ONLY state components screens may use.
// loading / empty / denied / offline / error / success. Each is a centred
// mark + heading + human body + one primary path (plus an optional
// secondary on error). Kind is mark + copy, never hue-only; status changes
// announce via live regions. Replaces the pre-Cosmos EmptyState/ErrorState
// (migrated call sites in W8-04; no third variant survives).

const val CosmosStateTag = "CosmosState"

private enum class CosmosStateKind(val mark: String) {
    Loading(""),
    Empty("○"),
    Denied("!"),
    Offline("i"),
    Error("×"),
    Success("✓"),
}

@Composable
private fun CosmosStateScaffold(
    kind: CosmosStateKind,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    assertive: Boolean = false,
    /** Replaces the 48 dp kind mark with an illustration (W13-11: brand mark, bloom). Decorative. */
    hero: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(EchoSpace24)
            .testTag("$CosmosStateTag:${kind.name}")
            .semantics {
                liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(EchoSpace12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hero != null) {
            hero()
        } else if (kind == CosmosStateKind.Loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .testTag(CosmosLoadingTag)
                    .semantics { contentDescription = title },
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Kind is already carried by the heading + copy below; the
                    // glyph is decorative and must not be read as "White
                    // circle" or "Multiplication sign" (review 0013 F2).
                    Text(
                        text = kind.mark,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) {
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
        }
        actions?.invoke()
    }
}

/** Ongoing work with honest progress copy; no fake determinate bar. */
@Composable
fun CosmosLoadingState(
    title: String,
    modifier: Modifier = Modifier,
) {
    CosmosStateScaffold(kind = CosmosStateKind.Loading, title = title, modifier = modifier)
}

/**
 * Nothing here yet: title, why it is empty, one clear next step, plus
 * optional quiet secondary/tertiary paths for offers that are real
 * actions, never a second primary.
 */
@Composable
fun CosmosEmptyState(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    hero: @Composable (() -> Unit)? = null,
    /** Optional secondary path (W13-13 first-run Practice offer). Never a second primary. */
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    /** Optional tertiary path (first-run Receive offer): a third quiet action after the secondary. */
    tertiaryLabel: String? = null,
    onTertiary: () -> Unit = {},
) {
    CosmosStateScaffold(
        kind = CosmosStateKind.Empty,
        title = title,
        body = body,
        modifier = modifier,
        hero = hero,
        actions = {
            CosmosPrimaryButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
            if (secondaryLabel != null) {
                CosmosSecondaryButton(
                    label = secondaryLabel,
                    onClick = onSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (tertiaryLabel != null) {
                CosmosSecondaryButton(
                    label = tertiaryLabel,
                    onClick = onTertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** Permission or capability denied: what is needed, the one path to fix it. */
@Composable
fun CosmosDeniedState(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    CosmosStateScaffold(
        kind = CosmosStateKind.Denied,
        title = title,
        body = body,
        modifier = modifier,
        actions = {
            CosmosPrimaryButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** Offline: what still works, the one path forward. Never a dead end. */
@Composable
fun CosmosOfflineState(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    CosmosStateScaffold(
        kind = CosmosStateKind.Offline,
        title = title,
        body = body,
        modifier = modifier,
        actions = {
            CosmosPrimaryButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** Failure with recovery: retry primary, optional safe secondary. */
@Composable
fun CosmosErrorState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    CosmosStateScaffold(
        kind = CosmosStateKind.Error,
        title = message,
        modifier = modifier,
        assertive = true,
        actions = {
            CosmosPrimaryButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
            if (secondaryLabel != null && onSecondary != null) {
                CosmosSecondaryButton(
                    label = secondaryLabel,
                    onClick = onSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** Confirmed completion: what happened, optional one next step. */
@Composable
fun CosmosSuccessState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    hero: @Composable (() -> Unit)? = null,
) {
    CosmosStateScaffold(
        kind = CosmosStateKind.Success,
        title = title,
        body = body,
        modifier = modifier,
        hero = hero,
        actions = {
            if (actionLabel != null && onAction != null) {
                CosmosPrimaryButton(
                    label = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
