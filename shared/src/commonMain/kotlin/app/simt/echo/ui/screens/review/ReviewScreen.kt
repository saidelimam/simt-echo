package app.simt.echo.ui.screens.review

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.simt.echo.data.DraftRepository
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.Minimap
import kotlin.math.roundToInt

/**
 * Read-only draft review (plan W1-04): the segmented path on the [Minimap]
 * and the node list. W2-01 extends this same screen with the landmark
 * editor; the geometry shown here is sensor-owned and never edited.
 */
@Composable
fun ReviewScreen(
    draftId: String,
    drafts: DraftRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = remember(draftId) { drafts.loadDraftRoute(draftId) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (route == null) {
            Text(text = "No draft route for $draftId", style = MaterialTheme.typography.titleLarge)
            EchoButton(text = "Back home", onClick = onBack, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        Text(text = route.name.ifEmpty { draftId }, style = MaterialTheme.typography.titleLarge)
        Text(
            text = "${route.totalLengthM.roundToInt()} m · ${route.nodes.size} nodes",
            style = MaterialTheme.typography.bodyLarge,
        )
        // Walk-forward is drawn up: path y grows forward, screen y grows down.
        Minimap(
            points = route.path.map { Offset(it.x.toFloat(), -it.y.toFloat()) },
            highlightIndex = 0,
            modifier = Modifier.fillMaxWidth(),
        )
        route.nodes.forEach { n ->
            Text(
                text = "${n.atDistanceM.roundToInt()} m · ${n.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        EchoButton(text = "Back home", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
