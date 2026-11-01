package app.simt.echo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.AppGraph
import app.simt.echo.ui.theme.EchoTheme

/**
 * Root composable hosted by every platform entry point.
 *
 * Scaffold placeholder only: the navigation shell (Home → Record → Review →
 * Share / Receive → Navigate) is slice W0-03, and each screen lands with its
 * own slice. Keep this file free of feature logic.
 */
@Composable
fun EchoApp(graph: AppGraph) {
    EchoTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "ECHO",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(text = "Give unmapped places a memory.")
                Text(text = "Platform: ${graph.platform.name}")
                Text(text = "Scaffold ready — screens land per slice.")
            }
        }
    }
}
