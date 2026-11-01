package app.simt.echo.ui.screens.arrived

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.NodeType
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.oneDecimal

/**
 * Arrived screen (plan §8.3 / W2-06): check mark, destination, summary,
 * Done. Summary only by decision: trial persistence belongs to W4-01's
 * TrialRepository, which extends this screen with the write.
 */
@Composable
fun ArrivedScreen(
    routeId: String,
    routes: RouteRepository,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = remember(routeId) { routes.get(routeId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "✓", style = MaterialTheme.typography.displayLarge)
        if (route == null) {
            Text(text = "Arrived", style = MaterialTheme.typography.titleLarge)
        } else {
            val destination = route.nodes.lastOrNull { it.type == NodeType.DESTINATION }
                ?.label?.takeIf { it.isNotEmpty() } ?: route.name.ifEmpty { routeId }
            Text(text = destination, style = MaterialTheme.typography.titleLarge)
            // Route facts only: this screen observes no confirmations, so it
            // must not claim any (review 0001 R2).
            Text(
                text = "${oneDecimal(route.totalLengthM)} m · " +
                    "${route.landmarks.size} landmarks on route",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        EchoButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}
