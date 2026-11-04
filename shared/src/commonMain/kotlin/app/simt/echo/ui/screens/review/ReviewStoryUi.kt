package app.simt.echo.ui.screens.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.simt.echo.ai.CandidateMerger
import app.simt.echo.ai.MaskPath
import app.simt.echo.nav.HealthResult
import app.simt.echo.platform.decodeImage
import app.simt.echo.ui.atmosphere.CosmosMotionDurations
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.cosmosMotionTier
import app.simt.echo.ui.components.CosmosBadge
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.components.roundedOneDecimal
import app.simt.echo.ui.motion.echoSharedElement
import app.simt.echo.ui.theme.EchoExtras
import kotlin.math.roundToInt

/** Semantics tag for the film strip (host UI gate target). */
const val FilmStripTag = "filmStrip"

/** Semantics tag for the Route Health header. */
const val HealthHeaderTag = "healthHeader"

/** Semantics tag for the full-screen keyframe inspector. */
const val InspectorTag = "keyframeInspector"

/**
 * Shared-element key for a node's keyframe: the film-strip cell and the
 * full-screen inspector both name it, so the frame flies between them on
 * [app.simt.echo.ui.motion.LuminousSprings.spatialSlow] (FULL tier only;
 * elsewhere the inspector simply appears — same hierarchy either way).
 * V evidence is on-device; there is no host assertion on the flight.
 */
fun reviewKeyframeKey(nodeId: String): String = "review-keyframe:$nodeId"

/**
 * Route Health header (plan W12-07 read surface): score + band with
 * nudge buttons that jump the story selection to the place. Panel only —
 * the score is recomputed from the draft on every publish, never stored.
 */
@Composable
fun RouteHealthHeader(
    health: HealthResult,
    onNudge: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        health.score >= 75 -> EchoExtras.success
        health.score >= 50 -> EchoExtras.gold
        else -> EchoExtras.ember
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .testTag(HealthHeaderTag)
            .semantics { contentDescription = "Route health ${health.score}: ${health.band}" },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Route health ${health.score} · ${health.band}",
                    style = MaterialTheme.typography.titleMedium,
                )
                CosmosBadge(text = "${health.score}/100")
            }
            LinearProgressIndicator(
                progress = { (health.score / 100f).coerceIn(0f, 1f) },
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
            )
            health.visualCoverageNotes.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            health.nudges.forEach { nudge ->
                CosmosSecondaryButton(
                    label = nudge,
                    onClick = { onNudge(nudge) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Horizontal film strip of ledger frames in walked order. Tap scrubs the
 * shared selection (minimap cursor + timeline follow); tapping the
 * selected cell again opens the full-screen inspector. Selection reads
 * by ring + label, never hue alone.
 */
@Composable
fun FilmStrip(
    frames: List<FilmFrame>,
    heroes: Map<String, ByteArray?>,
    selectedNodeId: String?,
    onScrub: (String) -> Unit,
    onInspect: (String) -> Unit,
    modifier: Modifier = Modifier,
    landmarkCounts: Map<String, Int> = emptyMap(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(FilmStripTag)
            .semantics { contentDescription = "Film strip, ${frames.size} frames by distance" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        frames.forEachIndexed { index, frame ->
            val selected = frame.nodeId == selectedNodeId
            val landmarkCount = landmarkCounts[frame.nodeId] ?: 0
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                modifier = Modifier
                    .width(104.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { if (selected) onInspect(frame.nodeId) else onScrub(frame.nodeId) }
                    // One TalkBack node per cell: the index, star count and
                    // distance inside are decoration on top of this sentence
                    // (review 0013 F2).
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "Frame at ${frame.atDistanceM.roundToInt()} m" +
                                (if (frame.label.isNotEmpty()) ", ${frame.label}" else "") +
                                (if (selected) ". Selected — activate again to inspect" else "")
                    },
            ) {
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val bytes = heroes[frame.nodeId]
                    var cellModifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(MaterialTheme.shapes.small)
                    if (selected) cellModifier = cellModifier.echoSharedElement(reviewKeyframeKey(frame.nodeId))
                    Box(modifier = Modifier.fillMaxWidth().height(68.dp)) {
                        CropImage(
                            bytes = bytes,
                            contentDescription = null,
                            modifier = cellModifier,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = cellModifier.background(MaterialTheme.colorScheme.surfaceContainer),
                            ) {
                                Text(
                                    text = "${frame.atDistanceM.roundToInt()} m",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                                    },
                                    shape = CircleShape,
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        if (landmarkCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(
                                        color = EchoExtras.gold,
                                        shape = CircleShape,
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "★$landmarkCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.surface,
                                )
                            }
                        }
                    }
                    Text(
                        text = frame.label.ifEmpty {
                            when (index) {
                                0 -> "Start"
                                frames.lastIndex -> "Destination"
                                else -> "Spot ${index + 1}"
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = "${frame.atDistanceM.roundToInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * AI suggestion artwork: the keyframe with the candidate box and the SAM
 * mask tracing its own outline in [MASK_TRACE_MS] (luminous-freeze §4
 * "trace"). FULL traces the stroke and fades the fill in with it;
 * REDUCED settles the final mark in feedback time; STATIC shows it.
 * An unreadable mask draws nothing (box-only). Decorative motion — the
 * title and stat chips carry the meaning.
 */
@Composable
fun SuggestionArtwork(
    candidate: app.simt.echo.ai.AiCandidate,
    heroBytes: ByteArray?,
    modifier: Modifier = Modifier,
) {
    val tier = cosmosMotionTier()
    val trace = remember(candidate.candidateId) {
        Animatable(if (tier == CosmosMotionTier.STATIC) 1f else 0f)
    }
    LaunchedEffect(candidate.candidateId) {
        when (tier) {
            CosmosMotionTier.STATIC -> trace.snapTo(1f)
            CosmosMotionTier.REDUCED -> trace.animateTo(1f, tween(CosmosMotionDurations.FEEDBACK_MS))
            CosmosMotionTier.FULL -> {
                trace.snapTo(0f)
                trace.animateTo(1f, tween(MASK_TRACE_MS.toInt()))
            }
        }
    }
    val bitmap = remember(heroBytes) { heroBytes?.let(::decodeImage) }
    val accent = MaterialTheme.colorScheme.primary
    val accentFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val outline = remember(candidate.maskSvgPath, candidate.maskW, candidate.maskH) {
        val maskW = candidate.maskW
        val maskH = candidate.maskH
        if (maskW != null && maskH != null && maskW > 0 && maskH > 0) {
            candidate.maskSvgPath?.let(MaskPath::parseSvgPath)?.let { MaskOutline(it, maskW, maskH) }
        } else {
            null
        }
    }
    val box = CandidateMerger.candidateCropBox(candidate)
    // Draw-phase scratch: measuring and trimming never recompose or allocate per frame.
    val measure = remember { PathMeasure() }
    val traced = remember { Path() }
    Canvas(
        modifier = modifier.fillMaxWidth().height(160.dp).semantics {
            contentDescription = "AI suggestion: ${candidate.title}"
        },
    ) {
        // Read here, not in composition: the 600 ms trace redraws, never recomposes.
        val progress = trace.value.coerceIn(0f, 1f)
        if (bitmap != null) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
        drawRect(
            color = accent,
            topLeft = Offset(box.left * size.width, box.top * size.height),
            size = Size(
                (box.right - box.left) * size.width,
                (box.bottom - box.top) * size.height,
            ),
            style = Stroke(width = strokePx),
        )
        outline?.pathsFor(size)?.forEach { path ->
            if (progress >= 1f) {
                drawPath(path = path, color = accentFill, style = Fill)
                drawPath(path = path, color = accent, style = Stroke(width = strokePx))
            } else if (progress > 0f) {
                measure.setPath(path, false)
                traced.rewind()
                measure.getSegment(0f, measure.length * progress, traced, true)
                drawPath(path = traced, color = accent, style = Stroke(width = strokePx))
                drawPath(path = path, color = accentFill.copy(alpha = 0.35f * progress), style = Fill)
            }
        }
    }
}

/**
 * Full-screen keyframe inspector: the shared-element frame with pinch
 * zoom (1×–5×) for verifying a mask before Use. Geometry stays read-only
 * — this surface edits nothing and closes back to the strip.
 */
@Composable
fun KeyframeInspector(
    nodeId: String,
    title: String,
    heroBytes: ByteArray?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val bitmap = remember(heroBytes) { heroBytes?.let(::decodeImage) }
    // The fitted image, not the canvas, bounds the pan: letterbox bars never scroll into view.
    val content = bitmap
        ?.let { fitContain(Size(it.width.toFloat(), it.height.toFloat()), viewport).size }
        ?: Size.Zero
    val zoom = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = clampPan(offset + panChange, scale, content, viewport)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(InspectorTag)
            .semantics { contentDescription = "Full-frame inspector: $title. Pinch to zoom" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                .transformable(zoom),
        ) {
            if (bitmap != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .echoSharedElement(reviewKeyframeKey(nodeId)),
                ) {
                    drawFitted(bitmap)
                }
            } else {
                Text(text = "No keyframe yet.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            text = "Zoom ${roundedOneDecimal(scale.toDouble())}×",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CosmosSecondaryButton(label = "Close", onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}
