package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Live QR scanner for the Receive screen (plan W2-04).
 *
 * The Android actual binds CameraX `ImageAnalysis` with an ML Kit barcode
 * scanner and reports the first value starting with the ECHO URI scheme,
 * then unbinds. iOS is out of v1 scope: a stub that never reports.
 * Camera permission flow belongs to W4-03; like Record (W1-03) this seam
 * assumes the camera is available and fails closed (no scan) otherwise.
 */
@Composable
expect fun QrScannerView(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
)
