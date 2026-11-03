package app.simt.echo.ui.screens.align

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.CameraRecorder
import app.simt.echo.platform.CaptureScreen
import app.simt.echo.platform.cameraGraphFor
import app.simt.echo.platform.rememberCameraRecorder
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.copy.MISSING_ROUTE_ERROR
import app.simt.echo.ui.theme.EchoExtras
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace24

/** Reference frame: the view the walker matches. */
const val AlignReferenceFrameTag = "AlignReferenceFrame"

/** Corner pip: the walker's live camera, bottom right of the frame. */
const val AlignLivePreviewTag = "AlignLivePreview"

private val FrameShape = RoundedCornerShape(24.dp)
private val PipShape = RoundedCornerShape(16.dp)

/**
 * Align screen (plan §8.3 / W2-06, Cosmos W8-10): the start photo fills
 * the frame, the live camera is a bottom-right pip, the hint sits under
 * the frame, and "I'm aligned" hands off. Stateless by design: the tap
 * hands off to Navigate, which zeroes its odometer on entry — no shared
 * Navigator state crosses the destination boundary.
 */
@Composable
fun AlignScreen(
    routeId: String,
    routes: RouteRepository,
    onAligned: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    camera: CameraRecorder = rememberCameraRecorder(cameraGraphFor(CaptureScreen.ALIGN)),
) {
    val route = remember(routeId) { routes.get(routeId) }

    if (route == null) {
        CosmosEmptyState(
            title = MISSING_ROUTE_ERROR.whatHappened,
            actionLabel = "Back",
            onAction = onBack,
            modifier = modifier.fillMaxSize(),
            body = "${MISSING_ROUTE_ERROR.whatsSafe} ${MISSING_ROUTE_ERROR.nextAction}",
        )
        return
    }

    val startBytes = remember(route) {
        route.start.photoAssetId?.let { routes.readAsset(route.routeId, it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(EchoSpace24),
        verticalArrangement = Arrangement.spacedBy(EchoSpace12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Stand here. Match this view.",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(2.dp, EchoExtras.cyan, FrameShape)
                .clip(FrameShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .testTag(AlignReferenceFrameTag),
        ) {
            CropImage(
                bytes = startBytes,
                contentDescription = "View to match",
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No start photo for this route",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(EchoSpace24),
                    )
                }
            }
            LivePreview(
                camera = camera,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            )
        }
        Text(
            text = route.start.alignmentHint,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        CosmosPrimaryButton(label = "I'm aligned", onClick = onAligned, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Fixed portrait pip. The size lives on this box, not on the camera
 * surface: a surface sized to the sensor buffer ignores the corner
 * alignment and covers the photo.
 */
@Composable
private fun LivePreview(
    camera: CameraRecorder,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 120.dp, height = 168.dp)
            .clip(PipShape)
            .border(2.dp, MaterialTheme.colorScheme.primary, PipShape)
            .background(Color.Black)
            .testTag(AlignLivePreviewTag)
            .semantics { contentDescription = "Live preview" },
    ) {
        camera.PreviewFramed(modifier = Modifier.fillMaxSize())
        Text(
            text = "Live preview",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(vertical = 4.dp),
        )
    }
}
