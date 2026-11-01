package app.simt.echo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    /** Display name per draft id (route name, else destination); blanks fall back, never raw ids. */
    draftNames: Map<String, String> = emptyMap(),
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
                val name = draftDisplayName(draft.draftId, draftNames)
                EchoButton(
                    text = if (draft.hasRouteDraft) {
                        "$name · Open in Review"
                    } else {
                        "$name · Resume recording (${draft.sampleCount} samples)"
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
                text = "${draftDisplayName(draftId, draftNames)} · Draft",
                onClick = { onOpenDraft(draftId) },
            )
        }
        EchoButton(text = "Record", onClick = { onOpen(EchoRoute.Record()) })
        EchoButton(text = "Receive", onClick = { onOpen(EchoRoute.Receive) })
        EchoButton(text = "Settings", onClick = { onOpen(EchoRoute.Settings) })
    }
}

/** Draft display name: the route/draft name when one exists, never a raw id. */
private fun draftDisplayName(draftId: String, names: Map<String, String>): String =
    names[draftId]?.takeIf { it.isNotBlank() } ?: "Untitled draft"
