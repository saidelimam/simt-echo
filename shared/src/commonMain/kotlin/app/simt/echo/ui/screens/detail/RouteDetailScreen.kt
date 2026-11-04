package app.simt.echo.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.components.CosmosDialog
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.CosmosPrimaryButton
import app.simt.echo.ui.components.CosmosRecordRow
import app.simt.echo.ui.components.CosmosRecordRows
import app.simt.echo.ui.components.CosmosSecondaryButton
import app.simt.echo.ui.components.CropImage
import app.simt.echo.ui.copy.MISSING_ROUTE_ERROR
import app.simt.echo.ui.copy.UNTITLED_WALK
import app.simt.echo.ui.motion.echoSharedElement
import app.simt.echo.ui.motion.sigilSharedKey
import app.simt.echo.ui.sigil.RouteSigil
import app.simt.echo.ui.sigil.routeSigil
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
 *
 * W8-05: the shell owns the Route heading and the Back chrome — the
 * missing-route recovery action below is a body action, not a top bar.
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
            CosmosEmptyState(
                title = MISSING_ROUTE_ERROR.whatHappened,
                body = "${MISSING_ROUTE_ERROR.whatsSafe} ${MISSING_ROUTE_ERROR.nextAction}",
                actionLabel = "Back home",
                onAction = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        // Hero (W13-03): the route's own sigil traces itself in above its
        // name — the glyph is decorative, the name stays the identifier.
        val sigil = remember(route) { routeSigil(route) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RouteSigil(
                geometry = sigil,
                sigilSize = 144.dp,
                trace = true,
                modifier = Modifier.echoSharedElement(sigilSharedKey(routeId)),
            )
            Text(
                text = route.name.ifEmpty { UNTITLED_WALK },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "${route.totalLengthM.roundToInt()} m · ${route.nodes.size} nodes · " +
                    "${route.landmarks.size} landmarks",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // Landmarks use the record grammar: crop, name, cue and the node
        // distance, instead of two bare Texts (review 0013 F8).
        val landmarkRows: List<@Composable () -> Unit> =
            route.landmarks.map { landmark ->
                {
                    val crop = landmark.cropAssetId?.let { routes.readAsset(routeId, it) }
                    val atM = route.nodes.firstOrNull { it.id == landmark.nodeId }?.atDistanceM
                    CosmosRecordRow(
                        title = landmark.title,
                        subtitle = listOfNotNull(
                            landmark.cue.takeIf { it.isNotBlank() },
                            atM?.let { "at ${it.roundToInt()} m" },
                        ).joinToString(" · ").ifBlank { null },
                        leading = crop?.let { bytes ->
                            {
                                CropImage(
                                    bytes = bytes,
                                    contentDescription = landmark.title,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(MaterialTheme.shapes.small),
                                )
                            }
                        },
                    )
                }
            }
        CosmosRecordRows(rows = landmarkRows)
        CosmosPrimaryButton(
            label = "Navigate",
            onClick = { onNavigate(routeId) },
            modifier = Modifier.fillMaxWidth(),
        )
        // One Ember primary per region (Cosmos C12): Navigate leads, Share follows.
        CosmosSecondaryButton(
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
        CosmosSecondaryButton(
            label = "Delete route",
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
        )
        if (confirmDelete) {
            // Destructive confirms go through the shared dialog, whose
            // primary renders on the danger channel (review 0013 F8).
            CosmosDialog(
                title = "Delete this route?",
                body = "The saved walk, its landmarks and its crops are removed from this phone. This cannot be undone.",
                primaryLabel = "Delete route",
                onPrimary = {
                    transferService?.deletePlainExportsFor(routeId)
                    routes.delete(routeId)
                    onDeleted()
                },
                onDismiss = { confirmDelete = false },
                secondaryLabel = "Keep route",
                onSecondary = { confirmDelete = false },
                destructive = true,
            )
        }
    }
}
