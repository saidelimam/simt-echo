package app.simt.echo.ui.screens.review

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.ai.AiCandidate
import app.simt.echo.ai.CandidateMerger
import app.simt.echo.model.Landmark
import app.simt.echo.ui.motion.revealOnEnter
import app.simt.echo.ui.components.CosmosBadge
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosRecordRow
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CosmosTextField
import app.simt.echo.ui.components.CropImage
import kotlin.math.roundToInt

/**
 * One stat line ("SAM 0.86 · tracked 78% · visible ~9 m") as separate
 * chip labels. Presentation split only; [CandidateMerger.statLine] owns
 * the wording.
 */
fun statChipParts(statLine: String): List<String> =
    statLine.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }

/** Human node-kind word for timeline records ("turn", "destination", …). */
fun nodeKindWord(typeName: String): String = typeName.lowercase().replace('_', ' ')

@Composable
fun StartCard(
    description: String,
    heroBytes: ByteArray?,
    onDescription: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Story selection (W13-05): the selected timeline card reads by ring, never hue alone. */
    selected: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.shapes.medium,
                    )
                } else {
                    Modifier
                },
            )
            .padding(if (selected) 6.dp else 0.dp)
            .semantics {
                if (selected) contentDescription = "Start selected"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CosmosRecordRow(title = "Start", subtitle = "Route start")
        HeroImage(bytes = heroBytes, contentDescription = "Start photo")
        CosmosTextField(
            label = "Start description",
            value = description,
            onValueChange = onDescription,
        )
    }
}

@Composable
fun NodeCard(
    reviewNode: ReviewNode,
    suggestion: AiCandidate?,
    suggestionImage: ByteArray? = null,
    suggestionPosition: Int,
    suggestionCount: Int,
    onLabel: (String) -> Unit,
    onCue: (String) -> Unit,
    onDraw: () -> Unit,
    onEditLandmark: (String, String, String, Boolean) -> Unit,
    onDeleteLandmark: (String) -> Unit,
    onUseSuggestion: () -> Unit,
    onNextSuggestion: () -> Unit,
    onSkipSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    /** Story selection (W13-05): the selected timeline card reads by ring, never hue alone. */
    selected: Boolean = false,
) {
    val node = reviewNode.node
    val kind = nodeKindWord(node.type.name)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.shapes.medium,
                    )
                } else {
                    Modifier
                },
            )
            .padding(if (selected) 6.dp else 0.dp)
            .semantics {
                if (selected) contentDescription = "${node.label.ifEmpty { "Unnamed $kind" }} selected"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CosmosRecordRow(
            title = node.label.ifEmpty { "Unnamed $kind" },
            subtitle = "${node.atDistanceM.roundToInt()} m · $kind",
            badges = if (reviewNode.landmarks.isEmpty()) {
                emptyList()
            } else {
                listOf("${reviewNode.landmarks.size} verified")
            },
        )
        HeroImage(bytes = reviewNode.heroBytes, contentDescription = node.label.ifEmpty { "Node keyframe" })
        CosmosTextField(
            label = "Label",
            value = node.label,
            onValueChange = onLabel,
        )
        CosmosTextField(
            label = "Spoken cue",
            value = node.cue,
            onValueChange = onCue,
        )
        if (suggestion != null) {
            // Keyed so a checkpoint arrival slides its card in (W13-05);
            // later arrivals for other nodes never replay this one.
            key(suggestion.candidateId) {
                SuggestionCard(
                    candidate = suggestion,
                    heroBytes = suggestionImage ?: reviewNode.heroBytes,
                    position = suggestionPosition,
                    count = suggestionCount,
                    onUse = onUseSuggestion,
                    onNext = onNextSuggestion,
                    onSkip = onSkipSuggestions,
                    modifier = Modifier.revealOnEnter(0),
                )
            }
        }
        reviewNode.landmarks.forEach { landmark ->
            LandmarkRow(
                landmark = landmark,
                cropBytes = reviewNode.landmarkCrops[landmark.id],
                onEdit = onEditLandmark,
                onDelete = onDeleteLandmark,
            )
        }
        CosmosSecondaryButton(label = "Draw landmark", onClick = onDraw, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * One preselected AI suggestion (plan W3-07): the keyframe with the
 * candidate box (2 dp accent stroke) and the SAM mask at 35 % accent fill,
 * stat chips when SAM ran, and Use / Next / Skip. Unreviewed suggestions
 * never enter the route, so Skip needs no export filter downstream.
 * Provenance lives in the screen footnote, not per card.
 */
@Composable
fun SuggestionCard(
    candidate: AiCandidate,
    heroBytes: ByteArray?,
    position: Int,
    count: Int,
    onUse: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SuggestionArtwork(candidate = candidate, heroBytes = heroBytes)
        Text(
            text = "AI suggestion${
                if (count > 1) " $position of $count" else ""
            }: ${candidate.title}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = candidate.cue, style = MaterialTheme.typography.bodyMedium)
        CandidateMerger.statLine(candidate)?.let { stats ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                statChipParts(stats).forEach { part ->
                    CosmosBadge(text = part)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CosmosPrimaryButton(label = "Use", onClick = onUse)
            if (count > 1) CosmosSecondaryButton(label = "Next option", onClick = onNext)
            CosmosSecondaryButton(label = "Skip", onClick = onSkip)
        }
    }
}

@Composable
fun LandmarkRow(
    landmark: Landmark,
    onEdit: (String, String, String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    cropBytes: ByteArray? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cropBytes != null) {
            CropImage(
                bytes = cropBytes,
                contentDescription = landmark.title.ifEmpty { "Landmark crop" },
                modifier = Modifier.height(120.dp),
            )
        }
        CosmosTextField(
            label = "Landmark",
            value = landmark.title,
            onValueChange = { onEdit(landmark.id, it, landmark.cue, landmark.privacyRisk) },
        )
        CosmosTextField(
            label = "Landmark cue",
            value = landmark.cue,
            onValueChange = { onEdit(landmark.id, landmark.title, it, landmark.privacyRisk) },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sensitive (people, plates, screens)",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = landmark.privacyRisk,
                onCheckedChange = { onEdit(landmark.id, landmark.title, landmark.cue, it) },
            )
        }
        CosmosSecondaryButton(label = "Delete", onClick = { onDelete(landmark.id) })
    }
}

@Composable
fun HeroImage(
    bytes: ByteArray?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    CropImage(
        bytes = bytes,
        contentDescription = contentDescription,
        modifier = modifier.height(160.dp),
    ) {
        Text(
            text = "No keyframe yet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
