package app.simt.echo.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// iOS is out of v1 scope (plan W5-03 Accepted): a stub that never scans,
// so the Receive screen compiles without pretending capture exists.

@Composable
actual fun QrScannerView(
    onScanned: (String) -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier)
}
