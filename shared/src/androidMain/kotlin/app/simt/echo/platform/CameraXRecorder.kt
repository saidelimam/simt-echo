package app.simt.echo.platform

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Path
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX [CameraRecorder] (slice W1-02).
 *
 * Silent HD video (no `withAudioEnabled`, so no mic permission), one still
 * for the start photo, and the recording-start `tNs` on the motion clock.
 * If binding Preview+ImageCapture+Video together throws on a device, falls
 * back to Preview+Video and flags [photoFromVideo] so W1-05 extracts the
 * start frame at t = 500 ms after [stopVideo] instead.
 *
 * @param lifecycleOwner the Activity lifecycle camera binds to; supplied by
 * the owning screen (W1-03), never the Application.
 */
class CameraXRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraRecorder {

    private var state = RecorderState.IDLE
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var activeVideoOut: Path? = null
    private var finalizeSignal: CompletableDeferred<VideoRecordEvent.Finalize>? = null
    private var startEventNs: Long? = null

    /** True once the fallback path is in effect (plan §12 camera risk). */
    var photoFromVideo: Boolean = false
        private set

    private fun move(event: RecorderEvent) {
        state = transition(state, event)
            ?: error("CameraXRecorder: illegal $event in $state")
    }

    @Composable
    override fun Preview(modifier: Modifier, onError: (String) -> Unit) {
        var request by remember { mutableStateOf<SurfaceRequest?>(null) }
        LaunchedEffect(Unit) {
            // A bind failure is a graceful error, not a crash (W4-03):
            // the screen shows it and the user can go back.
            runCatching { ensureBound() }
                .onFailure { onError("Camera could not start: ${it.message ?: "unavailable"}") }
                .onSuccess { boundPreview?.setSurfaceProvider { request = it } }
        }
        DisposableEffect(Unit) {
            onDispose { provider?.unbindAll() }
        }
        request?.let {
            CameraXViewfinder(surfaceRequest = it, modifier = modifier)
        }
    }

    private var boundPreview: Preview? = null

    private suspend fun ensureBound() {
        if (provider != null) return
        val cameraProvider = awaitProvider()
        provider = cameraProvider
        val preview = Preview.Builder().build()
        boundPreview = preview
        val video = VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    ),
                )
                .setTargetVideoEncodingBitRate(3_000_000)
                .build(),
        )
        videoCapture = video
        try {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                video,
            )
        } catch (e: Exception) {
            Log.w(TAG, "3-use-case bind failed, falling back to Preview+Video", e)
            photoFromVideo = true
            imageCapture = null
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                DEFAULT_BACK_CAMERA,
                preview,
                video,
            )
        }
        if (state == RecorderState.IDLE) move(RecorderEvent.PREVIEW_READY)
    }

    override suspend fun takePhoto(out: Path): Path {
        val capture = imageCapture
            ?: error("takePhoto needs ImageCapture; photoFromVideo=$photoFromVideo")
        // FileStore guarantees parent dirs for common-code writes; the
        // CameraX seam owns the same contract (fresh draft dirs may not
        // exist yet — W7-12 device gate: ENOENT on first photo).
        File(out.toString()).parentFile?.mkdirs()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(File(out.toString())).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cont.resume(out)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
    }

    override suspend fun startVideo(out: Path): Long {
        move(RecorderEvent.START_VIDEO)
        ensureBound()
        val output = videoCapture ?: error("VideoCapture unavailable after bind")
        val callNs = SystemClock.elapsedRealtimeNanos()
        startEventNs = null
        val signal = CompletableDeferred<VideoRecordEvent.Finalize>()
        finalizeSignal = signal
        // Same parent-dir contract as takePhoto above: video output lands
        // in a possibly-fresh draft dir.
        File(out.toString()).parentFile?.mkdirs()
        recording = output.output
            .prepareRecording(context, FileOutputOptions.Builder(File(out.toString())).build())
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start ->
                        startEventNs = SystemClock.elapsedRealtimeNanos()
                    is VideoRecordEvent.Finalize ->
                        signal.complete(event)
                    else -> Unit
                }
            }
        activeVideoOut = out
        // The Start callback may not have fired yet when start() returns;
        // wait briefly so startNs is the camera's truth, not ours.
        repeat(20) {
            if (startEventNs != null) return@repeat
            delay(50)
        }
        return resolveVideoStartNs(startEventNs, callNs)
    }

    override suspend fun stopVideo(): Path {
        move(RecorderEvent.STOP_VIDEO)
        val active = recording ?: error("stopVideo with no active recording")
        val out = activeVideoOut ?: error("stopVideo with no output file")
        val signal = finalizeSignal ?: error("stopVideo with no finalize listener")
        active.stop()
        val final = signal.await()
        recording = null
        activeVideoOut = null
        finalizeSignal = null
        if (final.hasError()) {
            error("Video finalize failed: ${final.error} cause=${final.cause}")
        }
        return out
    }

    private companion object {
        const val TAG = "CameraXRecorder"
    }

    /**
     * Version-proof bridge for `ProcessCameraProvider.getInstance`
     * (ListenableFuture → coroutine). Retries once on failure; a camera
     * that never binds is a hard error, never a silent null.
     */
    private suspend fun awaitProvider(): ProcessCameraProvider {
        repeat(2) { attempt ->
            try {
                return suspendCancellableCoroutine { cont ->
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener(
                        {
                            try {
                                cont.resume(future.get())
                            } catch (e: Exception) {
                                cont.resumeWithException(e)
                            }
                        },
                        ContextCompat.getMainExecutor(context),
                    )
                }
            } catch (e: Exception) {
                if (attempt == 1) throw e
            }
        }
        error("unreachable")
    }
}
