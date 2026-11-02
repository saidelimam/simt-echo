package app.simt.echo.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.copy.MISSING_ROUTE_ERROR
import app.simt.echo.ui.copy.UNTITLED_WALK
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Saved-route detail (plan W2-01): read-only header, landmark list,
 * entries to Share and Navigate. Delete (W3-08) removes the route and
 * leaves; the post-save upload delete reuses [CloudApi.delete].
 *
 * W7-04 adds the core-loop entries: [onNavigate] walks the live motion
 * source via Align, [onSimulate] replays the creator capture via
 * Align in simulate mode (V2 W2-05's promised RouteDetail entry).
 */
@Composable
fun RouteDetailScreen(
    routeId: String,
    routes: RouteRepository,
    onShare: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleted: () -> Unit = onBack,
    /** Cloud API for the post-save upload delete (W3-08); null hides it. */
    api: CloudApi? = null,
    /** Server job from the just-completed Review save, if any. */
    savedJobId: String? = null,
    /** Plaintext-export cleanup on delete; null in previews/tests. */
    transferService: TransferService? = null,
    /** Live navigation entry (W7-04): RouteDetail → Align → Navigate. */
    onNavigate: (String) -> Unit = {},
    /** Debug replay entry (W7-04): RouteDetail → Align → Navigate in
     * simulate mode. Defaults to no-op so previews/tests without a
     * motion seam keep compiling. */
    onSimulate: (String) -> Unit = {},
) {
    val route = remember(routeId) { routes.get(routeId) }
    val persistedJobId = remember(routeId) { routes.routeJobId(routeId) }
    var cloudJobId by remember(routeId, savedJobId, persistedJobId) {
        mutableStateOf(savedJobId ?: persistedJobId)
    }
    var confirmDelete by remember(routeId) { mutableStateOf(false) }
    var mediaDeleted by remember(routeId, savedJobId) { mutableStateOf(false) }
    var deleteError by remember(routeId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (route == null) {
            Text(text = MISSING_ROUTE_ERROR.whatHappened, style = MaterialTheme.typography.titleLarge)
            Text(
                text = "${MISSING_ROUTE_ERROR.whatsSafe} ${MISSING_ROUTE_ERROR.nextAction}",
                style = MaterialTheme.typography.bodyLarge,
            )
            CosmosPrimaryButton(label = "Back home", onClick = onBack, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        Text(text = route.name.ifEmpty { UNTITLED_WALK }, style = MaterialTheme.typography.titleLarge)
        Text(
            text = "${route.totalLengthM.roundToInt()} m · ${route.nodes.size} nodes · " +
                "${route.landmarks.size} landmarks",
            style = MaterialTheme.typography.bodyLarge,
        )
        route.landmarks.forEach { landmark ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = landmark.title, style = MaterialTheme.typography.titleMedium)
                if (landmark.cue.isNotBlank()) {
                    Text(text = landmark.cue, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        CosmosPrimaryButton(
            label = "Navigate",
            onClick = { onNavigate(routeId) },
            modifier = Modifier.fillMaxWidth(),
        )
        CosmosPrimaryButton(
            label = "Share",
            onClick = { onShare(routeId) },
            modifier = Modifier.fillMaxWidth(),
        )
        CosmosSecondaryButton(
            label = "Simulate walk",
            onClick = { onSimulate(routeId) },
            modifier = Modifier.fillMaxWidth(),
        )
        val jobId = cloudJobId
        if (jobId != null && api != null && !mediaDeleted) {
            CosmosSecondaryButton(
                label = "Delete uploaded media now",
                onClick = {
                    scope.launch {
                        try {
                            api.delete(jobId)
                        } catch (e: Exception) {
                            deleteError = "Could not delete the uploaded files. Check the connection and try again."
                            return@launch
                        }
                        routes.clearRouteJobId(routeId)
                        cloudJobId = null
                        mediaDeleted = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (mediaDeleted) {
            Text(text = "Uploaded media deleted.", style = MaterialTheme.typography.bodyLarge)
        }
        deleteError?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        if (!confirmDelete) {
            CosmosSecondaryButton(
                label = "Delete route",
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CosmosPrimaryButton(
                label = "Confirm delete",
                onClick = {
                    transferService?.deletePlainExportsFor(routeId)
                    routes.delete(routeId)
                    onDeleted()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            CosmosSecondaryButton(
                label = "Keep route",
                onClick = { confirmDelete = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CosmosSecondaryButton(label = "Back home", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
