package app.simt.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.simt.echo.transfer.PairingMethod
import app.simt.echo.transfer.PairingRole
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.copy.PairingCopy
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace8

/** Stable tags for the pairing selector (plan 0005 W21-03 acceptance). */
const val PairingMethodNfcTag = "PairingMethodNfc"
const val PairingMethodQrTag = "PairingMethodQr"

/**
 * The always-visible NFC / QR selector (plan 0005 §2).
 *
 * One selectable group with two labelled items, each a real radio-role
 * control with the 48 dp minimum target. At a large font scale — or on a
 * very narrow phone — the options stack instead of truncating, and nothing
 * here has a fixed height.
 *
 * Selection only reports intent: whether NFC can actually run is the tap
 * panel's business, so an unavailable choice stays reachable and
 * explainable rather than being silently disabled.
 */
@Composable
fun PairingMethodSelector(
    role: PairingRole,
    selected: PairingMethod,
    enabled: Boolean,
    onSelect: (PairingMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nfcLabel = PairingCopy.NFC_LABEL
    val qrLabel = if (role == PairingRole.SHARE) {
        PairingCopy.QR_SHOW_LABEL
    } else {
        PairingCopy.QR_SCAN_LABEL
    }
    val groupLabel = PairingCopy.METHOD_GROUP
    val lockedHint = PairingCopy.METHOD_LOCKED
    val stacked = LocalDensity.current.fontScale >= 1.5f

    CanopyPanel(variant = CanopyVariant.Flat, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace8)
                .selectableGroup()
                .semantics { contentDescription = groupLabel },
            verticalArrangement = Arrangement.spacedBy(EchoSpace8),
        ) {
            if (stacked) {
                PairingOption(
                    label = nfcLabel,
                    selected = selected == PairingMethod.NFC,
                    enabled = enabled,
                    tag = PairingMethodNfcTag,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(PairingMethod.NFC) },
                )
                PairingOption(
                    label = qrLabel,
                    selected = selected == PairingMethod.QR,
                    enabled = enabled,
                    tag = PairingMethodQrTag,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(PairingMethod.QR) },
                )
            } else {
                // Receive reads QR first, Share reads NFC first; both keep
                // the pair adjacent so the switch is one glance away.
                val qrFirst = role == PairingRole.RECEIVE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EchoSpace8),
                ) {
                    if (qrFirst) {
                        PairingOption(
                            label = qrLabel,
                            selected = selected == PairingMethod.QR,
                            enabled = enabled,
                            tag = PairingMethodQrTag,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(PairingMethod.QR) },
                        )
                        PairingOption(
                            label = nfcLabel,
                            selected = selected == PairingMethod.NFC,
                            enabled = enabled,
                            tag = PairingMethodNfcTag,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(PairingMethod.NFC) },
                        )
                    } else {
                        PairingOption(
                            label = nfcLabel,
                            selected = selected == PairingMethod.NFC,
                            enabled = enabled,
                            tag = PairingMethodNfcTag,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(PairingMethod.NFC) },
                        )
                        PairingOption(
                            label = qrLabel,
                            selected = selected == PairingMethod.QR,
                            enabled = enabled,
                            tag = PairingMethodQrTag,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(PairingMethod.QR) },
                        )
                    }
                }
            }
            if (!enabled) {
                Text(
                    text = lockedHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One labelled option: 48 dp minimum, radio role, selected semantics. */
@Composable
private fun PairingOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
            .background(
                color = if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceVariant.copy(alpha = 0.35f),
                shape = shape,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outline.copy(alpha = 0.4f),
                shape = shape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = EchoSpace12, vertical = EchoSpace12),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}
