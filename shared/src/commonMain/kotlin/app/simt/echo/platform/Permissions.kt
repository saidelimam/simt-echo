package app.simt.echo.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.simt.echo.ui.components.EchoButton
import app.simt.echo.ui.components.PillButton

/**
 * Camera permission state (plan W4-03).
 *
 * Generic seam: screens observe [status] and call [request] from a user
 * gesture. The Android actual drives `RequestPermission` + rationale; iOS
 * is a no-op stub (W5-03 out of scope) that reports [UNKNOWN] so the gate
 * below never blocks content there.
 */
enum class CameraPermissionStatus {
    UNKNOWN,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

data class CameraPermissionState(
    val status: CameraPermissionStatus,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
expect fun rememberCameraPermissionState(): CameraPermissionState

/** Plan copy: why ECHO needs the camera, in one line. */
fun cameraRationale(): String =
    "ECHO needs the camera to record the walk and scan pair codes. No photo leaves this phone without your consent."

fun cameraDeniedMessage(permanently: Boolean): String =
    if (permanently) {
        "Camera is off. Enable it in Settings to record or scan, or continue without camera."
    } else {
        "Camera was denied. ECHO cannot record or scan without it."
    }

/**
 * Fail-closed permission gate (W4-03): granted (or unknown on platforms
 * without runtime permissions) renders [content]; denied renders the
 * rationale + retry instead of crashing on a camera bind. No dialog
 * windows — inline panel only, matching the app's UX rule.
 */
@Composable
fun CameraPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val permission = rememberCameraPermissionState()
    when (permission.status) {
        CameraPermissionStatus.GRANTED,
        CameraPermissionStatus.UNKNOWN,
        -> content()
        CameraPermissionStatus.DENIED,
        CameraPermissionStatus.PERMANENTLY_DENIED,
        -> {
            val permanent = permission.status == CameraPermissionStatus.PERMANENTLY_DENIED
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = "Camera needed",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = cameraRationale(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = cameraDeniedMessage(permanent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (permanent) {
                    EchoButton(
                        text = "Open settings",
                        onClick = permission.openSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PillButton(
                        text = "Try again",
                        onClick = permission.request,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    EchoButton(
                        text = "Allow camera",
                        onClick = permission.request,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
