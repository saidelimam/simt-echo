package app.simt.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Camera navigate overlay (plan W5-02, P2).
 *
 * Generic presentation only: the caller supplies the live camera
 * [preview] slot (existing `CameraRecorder.Preview`), and this composable
 * layers the same navigation content NavigateScreen already renders —
 * translucent arrow, instruction + distance, landmark crop card, progress —
 * over it. No projection math, no Iris names, no new dependency: landmark
 * anchoring beyond the static crop is out of scope for the generic slice.
 */

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
    progress01: Float,
    legText: String,
    scrimAlpha: Float,
    preview: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
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
            DirectionArrow(
                angleRad = arrowRad,
                color = Color(0xFFC6F432).copy(alpha = 0.92f),
            )
            Text(text = instruction, style = MaterialTheme.typography.titleLarge)
            DistanceReadout(text = distanceText)
            if (landmarkTitle != null && landmarkCue != null) {
                if (shouldShowLandmarkCrop(cropBytes)) {
                    CropImage(bytes = cropBytes, contentDescription = cropDescription)
                }
                LandmarkCard(
                    title = landmarkTitle,
                    cue = landmarkCue,
                    stats = landmarkStats,
                )
            }
            LinearProgressIndicator(
                progress = { progress01 },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = legText, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
