package app.simt.echo.ui.screens.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.simt.echo.transfer.ExpiryOption
import app.simt.echo.transfer.ExportItem
import app.simt.echo.transfer.ExportSelection
import app.simt.echo.transfer.ExportSummary
import app.simt.echo.transfer.LANDMARK_TEXT_DISCLOSURE
import app.simt.echo.transfer.PERCEPTION_DISCLOSURE
import app.simt.echo.transfer.START_PHOTO_ITEM_ID
import app.simt.echo.ui.atmosphere.CanopyPanel
import app.simt.echo.ui.atmosphere.CanopyVariant
import app.simt.echo.ui.components.CosmosRadioRow
import app.simt.echo.ui.components.CosmosTertiaryButton
import app.simt.echo.ui.components.CosmosToggleRow
import app.simt.echo.ui.copy.UNTITLED_WALK
import app.simt.echo.ui.copy.createdCopy
import app.simt.echo.ui.copy.expiryCopy
import app.simt.echo.ui.theme.EchoSpace16
import app.simt.echo.ui.theme.EchoSpace8

const val ShareExportSectionTag = "ShareExportSection"
const val ShareExportToggleTag = "ShareExportToggle"
const val ShareExportItemTag = "ShareExportItem"
const val SharePerceptionDisclosureTag = "SharePerceptionDisclosure"

/**
 * Export preview as a collapsed canopy section (plan W3-08): one honest
 * summary line while collapsed, the full asset toggles + link-expiry
 * selector when opened. Inline, not a dialog.
 *
 * Extracted from the Share screen in W21-03 so the pairing panel above it
 * owns the file's whole surface budget; the content is unchanged.
 */
@Composable
internal fun ShareExportSection(
    items: List<ExportItem>,
    selection: ExportSelection,
    summary: ExportSummary?,
    onToggle: (String, Boolean) -> Unit,
    onExpiry: (ExpiryOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    CanopyPanel(
        variant = CanopyVariant.Flat,
        modifier = modifier.testTag(ShareExportSectionTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EchoSpace16),
            verticalArrangement = Arrangement.spacedBy(EchoSpace8),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "What others receive",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                CosmosTertiaryButton(
                    label = if (open) "Hide details" else "Show details",
                    onClick = { open = !open },
                    modifier = Modifier.testTag(ShareExportToggleTag),
                )
            }
            if (!open) {
                Text(
                    text = collapsedSummary(summary, items.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExportPreview(
                    items = items,
                    selection = selection,
                    summary = summary,
                    onToggle = onToggle,
                    onExpiry = onExpiry,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun collapsedSummary(summary: ExportSummary?, itemCount: Int): String {
    if (summary == null) return if (itemCount == 0) "Loading export…" else "$itemCount items"
    val name = summary.routeName.ifEmpty { UNTITLED_WALK }
    return "$name · ${summary.nodeCount} nodes · ${summary.landmarkCountText()}"
}

private fun ExportSummary.landmarkCountText(): String {
    val n = landmarks.size
    return if (n == 1) "1 landmark" else "$n landmarks"
}

/**
 * Export preview rows (plan W3-08): every asset the bundle will hold,
 * with a toggle each, plus the link-expiry selector.
 */
@Composable
private fun ExportPreview(
    items: List<ExportItem>,
    selection: ExportSelection,
    summary: ExportSummary?,
    onToggle: (String, Boolean) -> Unit,
    onExpiry: (ExpiryOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(EchoSpace8),
    ) {
        if (summary != null) {
            Text(
                text = "${summary.routeName.ifEmpty { UNTITLED_WALK }} · by ${summary.creatorLabel} · " +
                    createdCopy(summary.createdAtEpochMs),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${summary.nodeCount} nodes · ${summary.pathPointCount} path points · ${summary.totalLengthM} m",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = "Start: ${summary.startDescription}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (summary.hasAlignmentHint) "Alignment hint included" else "No alignment hint",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (summary.hasStartPhoto) "Start photo included" else "Start photo not included",
                style = MaterialTheme.typography.bodyMedium,
            )
            summary.landmarks.forEach { landmark ->
                Text(
                    text = "Landmark: ${landmark.title} — ${landmark.cue}" +
                        (landmark.signText?.let { " · sign: $it" } ?: "") +
                        when {
                            !landmark.hasCrop -> " · text only"
                            landmark.cropIncluded -> " · photo included"
                            else -> " · photo off, text still included"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(text = LANDMARK_TEXT_DISCLOSURE, style = MaterialTheme.typography.bodyMedium)
            if (summary.hasPerception) {
                Text(
                    text = PERCEPTION_DISCLOSURE,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(SharePerceptionDisclosureTag),
                )
            }
            summary.ai?.let { ai ->
                Text(
                    text = "AI suggestions included (${ai.scoutModel} / ${ai.groundModel})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = expiryCopy(summary.expiresAtEpochMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items.forEach { item ->
            val included = if (item.id == START_PHOTO_ITEM_ID) {
                selection.includeStartPhoto
            } else {
                item.id !in selection.excludedCropIds
            }
            CosmosToggleRow(
                title = item.label,
                subtitle = if (item.privacyRisk) "May show people or screens — off by default" else null,
                checked = included,
                onCheckedChange = { onToggle(item.id, it) },
                modifier = Modifier.testTag("$ShareExportItemTag:${item.id}"),
            )
        }
        Text(
            text = "Link expires",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val expiryOptions = buildList {
            if (selection.expiry == ExpiryOption.KEEP_STORED) add(ExpiryOption.KEEP_STORED)
            add(ExpiryOption.NONE)
            add(ExpiryOption.HOURS_24)
            add(ExpiryOption.DAYS_7)
        }
        Column(modifier = Modifier.selectableGroup()) {
            expiryOptions.forEach { option ->
                CosmosRadioRow(
                    label = expiryLabel(option),
                    selected = selection.expiry == option,
                    onSelect = { onExpiry(option) },
                )
            }
        }
        Text(text = RAW_VIDEO_NOTE, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun expiryLabel(option: ExpiryOption): String = when (option) {
    ExpiryOption.NONE -> "Never"
    ExpiryOption.HOURS_24 -> "After 24 hours"
    ExpiryOption.DAYS_7 -> "After 7 days"
    ExpiryOption.KEEP_STORED -> "Keep existing expiry"
}
