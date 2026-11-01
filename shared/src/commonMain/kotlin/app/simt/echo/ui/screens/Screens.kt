package app.simt.echo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simt.echo.data.RecoverableDraft
import app.simt.echo.data.destination
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.StatusChip
import kotlin.math.roundToInt

/**
 * Home: saved routes are listed as cards with an ownership badge
 * (plan W2-01), unfinished captures carry a Draft badge (W1-03), and one
 * button per §8.2 destination keeps every route reachable.
 */
@Composable
fun HomeScreen(
    onOpen: (EchoRoute) -> Unit,
    modifier: Modifier = Modifier,
    draftIds: List<String> = emptyList(),
    onOpenDraft: (String) -> Unit = { onOpen(EchoRoute.Review(it)) },
    savedRoutes: List<SavedRoute> = emptyList(),
    onOpenRoute: (String) -> Unit = { onOpen(EchoRoute.RouteDetail(it)) },
    recoverableDrafts: List<RecoverableDraft> = emptyList(),
    onResumeDraft: (RecoverableDraft) -> Unit = { onOpen(it.destination()) },
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "ECHO", style = MaterialTheme.typography.displayLarge)
        Text(text = "Walk it once. Share the way.")
        if (recoverableDrafts.isNotEmpty()) {
            // Interrupted work first (W4-03): a kill mid-record or
            // mid-upload must greet the user on relaunch, not hide.
            Text(text = "Interrupted drafts", style = MaterialTheme.typography.titleMedium)
            recoverableDrafts.forEach { draft ->
                EchoButton(
                    text = if (draft.hasRouteDraft) {
                        "${draft.draftId} · Open in Review"
                    } else {
                        "${draft.draftId} · Resume recording (${draft.sampleCount} samples)"
                    },
                    onClick = { onResumeDraft(draft) },
                )
            }
        }
        savedRoutes.forEach { route ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EchoButton(
                    text = "${route.name.ifEmpty { route.routeId }} · " +
                        "${route.totalLengthM.roundToInt()} m · ${route.landmarks.size} landmarks",
                    onClick = { onOpenRoute(route.routeId) },
                    modifier = Modifier.weight(1f),
                )
                // Own saves stamp the Segmenter default; anything else
                // arrived over the wire (W2-04).
                StatusChip(text = if (route.creatorLabel == "creator") "Own" else "Received")
            }
        }
        draftIds.forEach { draftId ->
            EchoButton(
                text = "$draftId · Draft",
                onClick = { onOpenDraft(draftId) },
            )
        }
        EchoButton(text = "Record", onClick = { onOpen(EchoRoute.Record()) })
        EchoButton(text = "Receive", onClick = { onOpen(EchoRoute.Receive) })
        EchoButton(text = "Review demo draft", onClick = { onOpen(EchoRoute.Review("demo-draft")) })
        EchoButton(text = "Route detail demo", onClick = { onOpen(EchoRoute.RouteDetail("demo-route")) })
        EchoButton(text = "Share demo route", onClick = { onOpen(EchoRoute.Share("demo-route")) })
        EchoButton(text = "Align demo route", onClick = { onOpen(EchoRoute.Align("demo-route")) })
        EchoButton(
            text = "Navigate demo route",
            onClick = { onOpen(EchoRoute.Navigate("demo-route", simulate = false)) },
        )
        EchoButton(text = "Settings", onClick = { onOpen(EchoRoute.Settings) })
    }
}

/** Placeholder body shared by every not-yet-owned screen. */
@Composable
fun PlaceholderScreen(
    viewModel: PlaceholderViewModel,
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = ui.title, style = MaterialTheme.typography.titleLarge)
        Text(text = ui.subtitle, style = MaterialTheme.typography.bodyLarge)
        EchoButton(
            text = primaryText,
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
