package app.simt.echo.ui.screens.align

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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.CaptureScreen
import app.simt.echo.platform.cameraGraphFor
import app.simt.echo.platform.rememberCameraRecorder
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.copy.MISSING_ROUTE_ERROR
import app.simt.echo.ui.theme.EchoSpace12
import app.simt.echo.ui.theme.EchoSpace24

/**
 * Align screen (plan §8.3 / W2-06, Cosmos W8-10): reticle-framed start
 * photo, live preview PiP, hint text, "I'm aligned". Stateless by design:
 * the tap hands off to Navigate, which zeroes its odometer on entry — no
 * shared Navigator state crosses the destination boundary.
 */
@Composable
fun AlignScreen(
    routeId: String,
    routes: RouteRepository,
    onAligned: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = remember(routeId) { routes.get(routeId) }
    // Align graph: Preview + Analysis, never video (plan W10-03).
    val camera = rememberCameraRecorder(cameraGraphFor(CaptureScreen.ALIGN))

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
            modifier = Modifier.semantics { heading() },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .padding(8.dp),
        ) {
            CropImage(
                bytes = startBytes,
                contentDescription = "Start photo",
                modifier = Modifier.fillMaxSize(),
            )
            camera.Preview(
                modifier = Modifier
                    .size(160.dp)
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
            )
        }
        Text(
            text = "Live preview, bottom right",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = route.start.alignmentHint, style = MaterialTheme.typography.bodyLarge)
        CosmosPrimaryButton(label = "I'm aligned", onClick = onAligned, modifier = Modifier.fillMaxWidth())
    }
}
