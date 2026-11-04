package app.simt.echo.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.simt.echo.ui.components.CosmosDeniedState

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
    /** Never asked (or askable again): not a denial, and must not be
     * labelled as one (review 0003 R2). */
    NOT_DETERMINED,
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
        "Camera is off. Enable it in Settings to record or scan."
    } else {
        "Camera was denied. ECHO cannot record or scan without it."
    }

fun cameraNotDeterminedMessage(): String =
    "ECHO has not asked for the camera yet."

/** Copy for one gate state; null means the gate should render its content. */
internal data class CameraGateCopy(
    val title: String,
    val body: String,
    val actionLabel: String,
    val secondaryLabel: String? = null,
    val opensSettings: Boolean = false,
)

/**
 * Pure state → copy. NOT_DETERMINED is explicitly neutral: asking is not
 * a refusal, so its copy never says "denied" (review 0013 F5).
 */
internal fun cameraGateCopy(status: CameraPermissionStatus): CameraGateCopy? = when (status) {
    CameraPermissionStatus.GRANTED,
    CameraPermissionStatus.UNKNOWN,
    -> null
    CameraPermissionStatus.NOT_DETERMINED -> CameraGateCopy(
        title = "Camera needed",
        body = "${cameraRationale()} ${cameraNotDeterminedMessage()}",
        actionLabel = "Allow camera",
    )
    CameraPermissionStatus.DENIED -> CameraGateCopy(
        title = "Camera needed",
        body = "${cameraRationale()} ${cameraDeniedMessage(permanently = false)}",
        actionLabel = "Allow camera",
    )
    CameraPermissionStatus.PERMANENTLY_DENIED -> CameraGateCopy(
        title = "Camera is off",
        body = "${cameraRationale()} ${cameraDeniedMessage(permanently = true)}",
        actionLabel = "Open settings",
        secondaryLabel = "Try again",
        opensSettings = true,
    )
}

/**
 * Fail-closed permission gate (W4-03): granted (or unknown on platforms
 * without runtime permissions) renders [content]; every other state is
 * the shared [CosmosDeniedState] — one state component, live region and
 * heading included — instead of a bespoke column (review 0013 F5).
 */
@Composable
fun CameraPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val permission = rememberCameraPermissionState()
    val copy = cameraGateCopy(permission.status)
    if (copy == null) {
        content()
        return
    }
    CosmosDeniedState(
        title = copy.title,
        body = copy.body,
        actionLabel = copy.actionLabel,
        onAction = if (copy.opensSettings) permission.openSettings else permission.request,
        secondaryLabel = copy.secondaryLabel,
        onSecondary = permission.request,
        modifier = modifier.fillMaxSize(),
    )
}
