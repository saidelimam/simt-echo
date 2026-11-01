package app.simt.echo.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Android camera permission (W4-03).
 *
 * No new dependency: `activity-compose` is already wired. Fail-closed:
 * starts at DENIED until the OS confirms GRANTED, requests once from the
 * gate's user gesture, and reports PERMANENTLY_DENIED when the OS will no
 * longer show its own dialog (no more auto-prompts from here).
 */
@Composable
actual fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val activity = context as? Activity
    var status by remember {
        mutableStateOf(initialStatus(context))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) {
            CameraPermissionStatus.GRANTED
        } else if (activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        ) {
            CameraPermissionStatus.PERMANENTLY_DENIED
        } else {
            CameraPermissionStatus.DENIED
        }
    }
    // Re-check when returning from Settings (gate's Open settings).
    LaunchedEffect(Unit) {
        if (initialStatus(context) == CameraPermissionStatus.GRANTED) {
            status = CameraPermissionStatus.GRANTED
        }
    }
    return remember(status) {
        CameraPermissionState(
            status = status,
            request = {
                if (initialStatus(context) == CameraPermissionStatus.GRANTED) {
                    status = CameraPermissionStatus.GRANTED
                } else {
                    launcher.launch(Manifest.permission.CAMERA)
                }
            },
            openSettings = {
                activity?.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        )
    }
}

private fun initialStatus(context: android.content.Context): CameraPermissionStatus =
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        CameraPermissionStatus.GRANTED
    } else {
        CameraPermissionStatus.DENIED
    }
