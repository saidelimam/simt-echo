package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// iOS is out of v1 scope (plan W5-03 Accepted): stub keeps the target
// compiling. UNKNOWN lets CameraPermissionGate render content (fail-open
// here — the iOS build never claims a permission it cannot verify).

@Composable
actual fun rememberCameraPermissionState(): CameraPermissionState = remember {
    CameraPermissionState(
        status = CameraPermissionStatus.UNKNOWN,
        request = {},
        openSettings = {},
    )
}
