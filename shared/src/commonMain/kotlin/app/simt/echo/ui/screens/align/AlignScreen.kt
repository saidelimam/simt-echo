package app.simt.echo.ui.screens.align

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.rememberCameraRecorder
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.copy.MISSING_ROUTE_ERROR

/**
 * Align screen (plan §8.3 / W2-06): start photo, live preview PiP, hint
 * text, "I'm aligned". Stateless by design: the tap hands off to Navigate,
 * which zeroes its odometer on entry — no shared Navigator state crosses
 * the destination boundary.
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
    val camera = rememberCameraRecorder()

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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Stand here. Match this view.", style = MaterialTheme.typography.titleLarge)
        Box(modifier = Modifier.weight(1f)) {
            CropImage(
                bytes = startBytes,
                contentDescription = "Start photo",
                modifier = Modifier.fillMaxSize(),
            )
            camera.Preview(
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }
        Text(text = route.start.alignmentHint, style = MaterialTheme.typography.bodyLarge)
        CosmosPrimaryButton(label = "I'm aligned", onClick = onAligned, modifier = Modifier.fillMaxWidth())
    }
}
