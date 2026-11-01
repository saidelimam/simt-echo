package app.simt.echo.platform

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * Reports the first `simt-echo://` value once per composition, then
 * unbinds the analysis use case ("stop analysis"). Camera permission flow
 * belongs to W4-03; like Record (W1-03) this fails closed (no scan) when
 * the camera is unavailable.
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
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener(
            {
                try {
                    providerFuture.get().bindToLifecycle(lifecycleOwner, DEFAULT_BACK_CAMERA, analysis)
                } catch (e: Exception) {
                    Log.d(TAG, "QR scanner bind failed", e)
                }
            },
            mainExecutor,
        )
        onDispose {
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

    // No preview stream: the Receive screen draws its own rounded cutout
    // over this analysis-only surface (plan §8.3).
    Box(modifier = modifier)
}

private const val TAG = "EchoQrScanner"

/** ECHO pairing URIs; anything else in view is not ours to handle. */
private const val ECHO_QR_SCHEME = "simt-echo://"
