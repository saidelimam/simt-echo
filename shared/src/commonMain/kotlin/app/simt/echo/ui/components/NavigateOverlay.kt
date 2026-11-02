package app.simt.echo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.simt.echo.ai.MaskPath
import app.simt.echo.platform.decodeImage
import kotlin.math.roundToInt

/**
 * Camera navigate overlay (plan W5-02, completed in review 0004 R4).
 *
 * A camera HUD, honestly named: the caller supplies the live camera
 * [preview] slot (existing `CameraRecorder.Preview`, permission-gated
 * by the caller), and this composable layers the navigation HUD —
 * translucent arrow, instruction + distance, landmark crop card with
 * the persisted SAM mask drawn over the crop, progress — over it.
 * There is no live-frame projection: the mask lives in the creation
 * keyframe's frame space and is mapped into the crop's space, which is
 * the only mapping the persisted data supports.
 */

/**
 * One mask-raster point → normalised crop coordinates. The mask raster
 * covers the whole [frameW]×[frameH] frame; the crop is [box] within
 * that frame. Results may fall outside 0..1 (mask extending past the
 * crop edge); the canvas clips. Null on degenerate geometry.
 */
fun maskPointInCrop(
    x: Float,
    y: Float,
    maskW: Int,
    maskH: Int,
    box: List<Int>,
    frameW: Int,
    frameH: Int,
): Pair<Float, Float>? {
    if (maskW <= 0 || maskH <= 0 || frameW <= 0 || frameH <= 0 || box.size != 4) return null
    val cropW = (box[2] - box[0]).toFloat()
    val cropH = (box[3] - box[1]).toFloat()
    if (cropW <= 0f || cropH <= 0f) return null
    val frameX = x / maskW * frameW
    val frameY = y / maskH * frameH
    return ((frameX - box[0]) / cropW) to ((frameY - box[1]) / cropH)
}

/** Scrim alpha over the preview: nearer the next turn, clearer the view. */
fun overlayScrimAlpha(distanceToNextM: Double): Float =
    when {
        distanceToNextM <= 0.0 -> 0.35f
        distanceToNextM >= 30.0 -> 0.65f
        else -> (0.35f + (distanceToNextM / 30.0 * 0.30f).toFloat())
    }

/** The crop slot draws only when decodable bytes are present. */
fun shouldShowLandmarkCrop(cropBytes: ByteArray?): Boolean =
    cropBytes != null && cropBytes.isNotEmpty()

@Composable
fun CameraNavigateOverlay(
    arrowRad: Float,
    instruction: String,
    distanceText: String,
    uncertaintyText: String,
    offline: Boolean,
    landmarkTitle: String?,
    landmarkCue: String?,
    landmarkStats: String?,
    cropBytes: ByteArray?,
    cropDescription: String?,
    /** Persisted SAM geometry for mask-over-crop; all null → crop only. */
    maskSvgPath: String? = null,
    maskW: Int? = null,
    maskH: Int? = null,
    maskBox: List<Int>? = null,
    maskFrameW: Int? = null,
    maskFrameH: Int? = null,
    progress01: Float,
    legText: String,
    scrimAlpha: Float,
    preview: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** W13-08 instrument slots: defaults preserve the W5-02 HUD. */
    arrow: @Composable () -> Unit = {
        DirectionArrow(
            angleRad = arrowRad,
            color = Color(0xFFC6F432).copy(alpha = 0.92f),
        )
    },
    distance: @Composable () -> Unit = {
        DistanceReadout(text = distanceText, color = Color.White)
    },
    landmarkCard: @Composable () -> Unit = {
        DefaultOverlayLandmarkCard(
            landmarkTitle = landmarkTitle,
            landmarkCue = landmarkCue,
            landmarkStats = landmarkStats,
            cropBytes = cropBytes,
            cropDescription = cropDescription,
            maskSvgPath = maskSvgPath,
            maskW = maskW,
            maskH = maskH,
            box = maskBox,
            frameW = maskFrameW,
            frameH = maskFrameH,
        )
    },
    /** Below the distance: fingerprint chip + relock ring (W13-08). */
    belowDistance: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        preview()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offline) StatusChip(text = "Offline ✓")
                StatusChip(text = uncertaintyText)
            }
            arrow()
            // Light text over a dark scrim in BOTH schemes: the scrim is
            // black, so scheme text colours (near-black in light theme)
            // would vanish (review 0004 R4).
            Text(text = instruction, style = MaterialTheme.typography.titleLarge, color = Color.White)
            distance()
            belowDistance()
            landmarkCard()
            LinearProgressIndicator(
                progress = { progress01 },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = legText, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            actions()
        }
    }
}

/**
 * Standard masked landmark card shared by the overlay default slot and
 * both W13-08 Navigate bodies: persisted SAM mask over the crop, else
 * the plain crop, plus the landmark copy. Renders nothing without a
 * title and cue.
 */
@Composable
fun DefaultOverlayLandmarkCard(
    landmarkTitle: String?,
    landmarkCue: String?,
    landmarkStats: String?,
    cropBytes: ByteArray?,
    cropDescription: String?,
    maskSvgPath: String?,
    maskW: Int?,
    maskH: Int?,
    box: List<Int>?,
    frameW: Int?,
    frameH: Int?,
    modifier: Modifier = Modifier,
) {
    if (landmarkTitle != null && landmarkCue != null) {
        Column(modifier = modifier) {
            if (shouldShowLandmarkCrop(cropBytes)) {
                MaskedCropImage(
                    bytes = cropBytes,
                    contentDescription = cropDescription,
                    maskSvgPath = maskSvgPath,
                    maskW = maskW,
                    maskH = maskH,
                    box = box,
                    frameW = frameW,
                    frameH = frameH,
                )
            }
            LandmarkCard(
                title = landmarkTitle,
                cue = landmarkCue,
                stats = landmarkStats,
            )
        }
    }
}

/**
 * The landmark crop with its persisted SAM mask filled over it (35%
 * accent, Review's convention). Falls back to the plain crop when any
 * geometry is missing or the mask is unreadable.
 */
@Composable
fun MaskedCropImage(
    bytes: ByteArray?,
    contentDescription: String?,
    maskSvgPath: String?,
    maskW: Int?,
    maskH: Int?,
    box: List<Int>?,
    frameW: Int?,
    frameH: Int?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(bytes) { bytes?.let(::decodeImage) }
    val subpaths = remember(maskSvgPath) { maskSvgPath?.let(MaskPath::parseSvgPath) }
    if (bitmap == null || subpaths == null || maskW == null || maskH == null ||
        box == null || frameW == null || frameH == null
    ) {
        CropImage(bytes = bytes, contentDescription = contentDescription, modifier = modifier)
        return
    }
    val accentFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        drawImage(
            image = bitmap,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
        for (subpath in subpaths) {
            val path = Path()
            var started = false
            for (point in subpath.points) {
                val crop = maskPointInCrop(point.x, point.y, maskW, maskH, box, frameW, frameH) ?: continue
                val px = crop.first * size.width
                val py = crop.second * size.height
                if (!started) {
                    path.moveTo(px, py)
                    started = true
                } else {
                    path.lineTo(px, py)
                }
            }
            if (subpath.closed) path.close()
            if (started) drawPath(path = path, color = accentFill, style = Fill)
        }
    }
}
