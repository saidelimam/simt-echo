package app.simt.echo.platform

import android.annotation.SuppressLint
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
            // The camera default is 640x480: an ECHO v1 URI at High error
            // correction with the centre sigil is a dense 73x73..89x89
            // module code, so modules land at ~2 px and indoor scans fail
            // (R14-06). 720p makes them readable at arm's length.
            .setResolutionSelector(QR_ANALYSIS_RESOLUTION)
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
                    // Every code is delivered, not just `simt-echo://`: a
                    // foreign or corrupt one has to reach the bounded parser
                    // so the screen can say "This isn't an ECHO share" and
                    // offer "Scan again" instead of silently ignoring the
                    // scan (R14-06).
                    val uri = barcodes.firstNotNullOfOrNull { it.rawValue }
                    if (uri != null && delivered.compareAndSet(false, true)) {
                        onScanned(uri)
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
        preview.setSurfaceProvider { request = it }
        // The provider future completes on the main executor, possibly after
        // this composition is gone (switched to NFC). Binding then would open
        // the camera behind the tap panel (R14-06).
        val disposed = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener(
            {
                if (disposed.get()) return@addListener
                try {
                    providerFuture.get().bindToLifecycle(lifecycleOwner, DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    Log.d(TAG, "QR scanner bind failed", e)
                }
            },
            mainExecutor,
        )
        onDispose {
            disposed.set(true)
            preview.setSurfaceProvider(null)
            analysis.clearAnalyzer()
            scanner.close()
            executor.shutdown()
            try {
                // Only this scanner's own use cases. The provider is a process
                // singleton, so unbindAll() detached whatever the next screen
                // had just bound, leaving its viewfinder stuck on one frame.
                if (providerFuture.isDone) providerFuture.get().unbind(preview, analysis)
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

/**
 * Analysis resolution for the pairing scanner: 720p, or the closest higher
 * camera mode then the closest lower one. Never the 640x480 default.
 */
private val QR_ANALYSIS_RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(1280, 720),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
        ),
    )
    .build()
