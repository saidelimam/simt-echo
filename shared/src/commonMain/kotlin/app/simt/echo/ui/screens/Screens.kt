package app.simt.echo.ui.screens

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.components.EchoButton

/**
 * Home: route cards land with W2-01; until then one button per §8.2
 * destination proves every route is reachable (W0-03 done-when).
 */
@Composable
fun HomeScreen(
    onOpen: (EchoRoute) -> Unit,
    modifier: Modifier = Modifier,
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
        EchoButton(text = "Record", onClick = { onOpen(EchoRoute.Record) })
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
