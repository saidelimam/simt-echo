package app.simt.echo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RecoverableDraft
import app.simt.echo.data.destination
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.model.NodeType
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.components.CosmosEmptyState
import app.simt.echo.ui.components.CosmosRecordRow
import app.simt.echo.ui.components.CosmosRecordRows
import app.simt.echo.ui.components.oneDecimal
import app.simt.echo.ui.copy.UNTITLED_WALK

/**
 * Home in Cosmos (plan W8-06, collections grammar §6.5).
 *
 * Saved walks are *records* — name, length, turns, landmark count, an
 * Own/Received/Draft badge (shape + text, never hue-only) and the
 * offline-ready mark — stacked in stable tracks (line dividers, never
 * nested cards). Interrupted drafts recover first when present (W4-03):
 * a kill mid-record or mid-upload greets the user on relaunch. First
 * run is Ignition: one centred task, one primary command.
 *
 * Presentation only — the shell owns the top chrome and the persistent
 * Record/Receive deck (W8-05); ViewModel/state shapes untouched.
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
    if (savedRoutes.isEmpty() && draftIds.isEmpty() && recoverableDrafts.isEmpty()) {
        // First-run Ignition: one centred task, one primary command.
        CosmosEmptyState(
            title = "Walk it once. Share the way.",
            body = "Record a short walk to share it as a guided route.",
            actionLabel = "Record a walk",
            onAction = { onOpen(EchoRoute.Record()) },
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Walk it once. Share the way.")
        if (recoverableDrafts.isNotEmpty()) {
            // Interrupted work first: a kill mid-record or mid-upload
            // must greet the user on relaunch, not hide.
            Text(
                text = "Interrupted drafts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            CosmosRecordRows(
                rows = recoverableDrafts.map { draft ->
                    {
                        CosmosRecordRow(
                            title = draftDisplayName(draft.draftId, draftNames),
                            subtitle = if (draft.hasRouteDraft) {
                                "Ready to review"
                            } else {
                                "${draft.sampleCount} samples · Resume recording"
                            },
                            badges = listOf("Draft"),
                            onClick = { onResumeDraft(draft) },
                        )
                    }
                },
            )
        }
        if (savedRoutes.isNotEmpty()) {
            Text(
                text = "Saved walks",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            CosmosRecordRows(
                rows = savedRoutes.map { route ->
                    {
                        val turns = route.nodes.count { it.type == NodeType.TURN }
                        // Own saves stamp the Segmenter default; anything
                        // else arrived over the wire (W2-04). Every saved
                        // walk lives on this phone, so all are offline-ready.
                        val provenance = if (route.creatorLabel == "creator") "Own" else "Received"
                        CosmosRecordRow(
                            title = route.name.ifEmpty { UNTITLED_WALK },
                            subtitle = "${oneDecimal(route.totalLengthM)} m · " +
                                "$turns turns · ${route.landmarks.size} landmarks",
                            badges = listOf(provenance, "Offline-ready"),
                            onClick = { onOpenRoute(route.routeId) },
                        )
                    }
                },
            )
        }
        if (draftIds.isNotEmpty()) {
            Text(
                text = "Drafts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            CosmosRecordRows(
                rows = draftIds.map { draftId ->
                    {
                        CosmosRecordRow(
                            title = draftDisplayName(draftId, draftNames),
                            subtitle = "Unfinished walk",
                            badges = listOf("Draft"),
                            onClick = { onOpenDraft(draftId) },
                        )
                    }
                },
            )
        }
    }
}

/** Draft display name: the route/draft name when one exists, never a raw id. */
private fun draftDisplayName(draftId: String, names: Map<String, String>): String =
    names[draftId]?.takeIf { it.isNotBlank() } ?: "Untitled draft"
