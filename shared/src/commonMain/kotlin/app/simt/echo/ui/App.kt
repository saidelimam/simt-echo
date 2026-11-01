package app.simt.echo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.simt.echo.AppGraph
import app.simt.echo.ui.theme.EchoTheme

/**
 * Root composable hosted by every platform entry point. Feature screens
 * live in [EchoNavHost]; keep this file free of feature logic.
 */
@Composable
fun EchoApp(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    EchoTheme {
        EchoNavHost(graph = graph, modifier = modifier)
    }
}
