package app.simt.echo.platform

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android QR scanner (plan W2-04).
 *
 * Enclosed copy model (plan §4): the analysis pattern follows `simt-app`'s
 * `core/ui-camera/.../QrCodeScanner` — `ImageAnalysis` with
 * `STRATEGY_KEEP_ONLY_LATEST`, one single-thread executor, `InputImage`
 * into an ML Kit QR-only client, first accepted value delivered once —
 * re-implemented here as ECHO code with no `simt-*` names. The plan's
 * `MlKitAnalyzer` sketch is not used: the copied component drives the
 * analyzer directly, which needs no extra `camera-mlkit-vision` artifact.
 *
 * Shows the live camera (a Preview use case drawn by `CameraXViewfinder`,
 * like Record) so the user can aim at the other phone's QR; without it the
 * frame is an empty box. Reports the first `simt-echo://` value once per
 * composition, then unbinds the use cases ("stop analysis"). Camera
 * permission flow belongs to W4-03; like Record (W1-03) this fails closed
 * (no scan) when the camera is unavailable.
 */
@Composable
actual fun QrScannerView(
    onScanned: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val delivered = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val preview = remember { Preview.Builder().build() }
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }

    DisposableEffect(lifecycleOwner) {
        analysis.setAnalyzer(executor) { proxy ->
            if (delivered.get()) {
                proxy.close()
                return@setAnalyzer
            }
            @SuppressLint("UnsafeOptInUsageError")
            val mediaImage = proxy.image
            if (mediaImage == null) {
                proxy.close()
                return@setAnalyzer
            }
            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val uri = barcodes.mapNotNull { it.rawValue }
                        .firstOrNull { it.startsWith(ECHO_QR_SCHEME) }
                    if (uri != null && delivered.compareAndSet(false, true)) {
                        onScanned(uri)
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
        preview.setSurfaceProvider { request = it }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener(
            {
                try {
                    providerFuture.get().bindToLifecycle(lifecycleOwner, DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    Log.d(TAG, "QR scanner bind failed", e)
                }
            },
            mainExecutor,
        )
        onDispose {
            preview.setSurfaceProvider(null)
            analysis.clearAnalyzer()
            scanner.close()
            executor.shutdown()
            try {
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.d(TAG, "QR scanner unbind failed", e)
            }
        }
    }

    // The Receive screen draws its rounded cutout and reticle over this (plan §8.3).
    Box(modifier = modifier) {
        request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private const val TAG = "EchoQrScanner"

/** ECHO pairing URIs; anything else in view is not ours to handle. */
private const val ECHO_QR_SCHEME = "simt-echo://"
