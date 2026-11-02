package app.simt.echo.ui.screens.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.simt.echo.ai.AiCandidate
import app.simt.echo.ai.CandidateMerger
import app.simt.echo.ai.MaskPath
import app.simt.echo.model.Landmark
import app.simt.echo.platform.decodeImage
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
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
) {
    val node = reviewNode.node
    val kind = nodeKindWord(node.type.name)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            SuggestionCard(
                candidate = suggestion,
                heroBytes = suggestionImage ?: reviewNode.heroBytes,
                position = suggestionPosition,
                count = suggestionCount,
                onUse = onUseSuggestion,
                onNext = onNextSuggestion,
                onSkip = onSkipSuggestions,
            )
        }
        reviewNode.landmarks.forEach { landmark ->
            LandmarkRow(
                landmark = landmark,
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
        val bitmap = remember(heroBytes) { heroBytes?.let(::decodeImage) }
        val accent = MaterialTheme.colorScheme.primary
        val accentFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
        if (bitmap != null) {
            val box = CandidateMerger.candidateCropBox(candidate)
            val mask = candidate.maskSvgPath
                ?.takeIf { candidate.maskW != null && candidate.maskH != null }
                ?.let { MaskPath.parseSvgPath(it) }
            Canvas(
                modifier = Modifier.fillMaxWidth().height(160.dp).semantics {
                    contentDescription = "AI suggestion: ${candidate.title}"
                },
            ) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
                // Box: normalised crop mapped onto the canvas.
                drawRect(
                    color = accent,
                    topLeft = Offset(box.left * size.width, box.top * size.height),
                    size = Size(
                        (box.right - box.left) * size.width,
                        (box.bottom - box.top) * size.height,
                    ),
                    style = Stroke(width = strokePx),
                )
                // Mask: SVG raster px → keyframe px (kfW/maskW, kfH/maskH) →
                // canvas px. An unreadable mask draws nothing (box-only).
                val maskW = candidate.maskW
                val maskH = candidate.maskH
                if (mask != null && maskW != null && maskH != null && maskW > 0 && maskH > 0) {
                    for (subpath in mask) {
                        val path = Path()
                        subpath.points.forEachIndexed { i, point ->
                            // Float scaling: mask raster → canvas directly
                            // (the raster covers the frame the canvas shows).
                            val x = point.x * (size.width / maskW.toFloat())
                            val y = point.y * (size.height / maskH.toFloat())
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        if (subpath.closed) path.close()
                        drawPath(path = path, color = accentFill, style = Fill)
                    }
                }
            }
        }
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
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
