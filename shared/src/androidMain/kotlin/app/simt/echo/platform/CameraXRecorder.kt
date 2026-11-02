package app.simt.echo.platform

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
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
import app.simt.echo.capture.FrameRateGovernor
import app.simt.echo.capture.FrameTap
import app.simt.echo.capture.TimestampAlignment
import app.simt.echo.model.PerceptionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okio.Path
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX [CameraRecorder] (slice W1-02, graph rework W10-03).
 *
 * Silent HD video (no `withAudioEnabled`, so no mic permission) on the
 * motion clock, plus a throttled Frame Tap on its own thread. The bound
 * use cases follow [CameraGraph]: Record binds Preview+Video+Analysis with
 * ImageCapture dropped (the start photo becomes the ledger's best frame in
 * W11-01); Align and Navigate bind Preview+Analysis; overlay-off Navigate
 * binds Analysis only. The fallback ladder is by real bind attempt:
 * `[P,V,A] → [P,V]` with the ledger off, recorded as [perceptionMode].
 *
 * @param lifecycleOwner the Activity lifecycle camera binds to; supplied by
 * the owning screen (W1-03), never the Application.
 * @param graph the screen's use-case graph (W10-03).
 */
class CameraXRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val graph: CameraGraph = CameraGraph.RECORD_FULL,
) : CameraRecorder {

    private var state = RecorderState.IDLE
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysisExecutor: ExecutorService? = null
    private var recording: Recording? = null
    private var activeVideoOut: Path? = null
    private var finalizeSignal: CompletableDeferred<VideoRecordEvent.Finalize>? = null

    /** Throttled frames; the W11-01 ledger is the first subscriber. */
    private val tapSource = FrameTapSource(
        governor = FrameRateGovernor(
            if (graph == CameraGraph.NAVIGATE || graph == CameraGraph.ANALYSIS_ONLY) {
                FrameRateGovernor.NAVIGATE_INTERVAL_NS
            } else {
                // Record 8 Hz; Align is interactive and keeps the same
                // budget until W12-05 tunes it.
                FrameRateGovernor.RECORD_INTERVAL_NS
            },
        ),
        thermalNow = { context.currentThermalState() },
        alignmentNow = { timestampAlignment },
    )

    override val frameTap: FrameTap
        get() = tapSource

    @Volatile
    private var timestampAlignment: TimestampAlignment = TimestampAlignment.REALTIME

    /**
     * Ledger mode for `capture.json`: [PerceptionMode.LEDGER_ON] only when
     * the bind ladder actually bound analysis, OFF on the `[P,V]` fallback.
     */
    override val perceptionMode: String
        get() = boundMode

    @Volatile
    private var boundMode: String = PerceptionMode.LEDGER_OFF

    /**
     * True once the fallback path is in effect (plan §12 camera risk): no
     * still source, so the start photo comes from the ledger (W11-01) or,
     * before it, the W1-05 video-frame extraction.
     */
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
            onDispose {
                provider?.unbindAll()
                analysisExecutor?.shutdown()
                analysisExecutor = null
            }
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
        timestampAlignment = queryTimestampAlignment(context)
        val preview = if (graph.hasPreview) Preview.Builder().build() else null
        boundPreview = preview
        val video = if (graph.hasVideo) {
            VideoCapture.withOutput(
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
        } else {
            null
        }
        videoCapture = video
        val analysis = if (graph.hasAnalysis) {
            val executor = newAnalysisExecutor()
            analysisExecutor = executor
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(executor, tapSource.analyzer) }
        } else {
            null
        }
        // Fallback ladder by real bind attempt, never capability guess:
        // the full graph first, then Preview (+Video on Record) with the
        // ledger off. ImageCapture is dropped from every rung: the start
        // photo is the ledger's best frame (W11-01).
        imageCapture = null
        photoFromVideo = true
        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                DEFAULT_BACK_CAMERA,
                *listOfNotNull(preview, video, analysis).toTypedArray<UseCase>(),
            )
            boundMode = if (analysis != null) PerceptionMode.LEDGER_ON else PerceptionMode.LEDGER_OFF
        } catch (e: Exception) {
            Log.w(TAG, "Full ${graph.name} bind failed, falling back", e)
            cameraProvider.unbindAll()
            analysis?.clearAnalyzer()
            analysisExecutor?.shutdown()
            analysisExecutor = null
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                DEFAULT_BACK_CAMERA,
                *listOfNotNull(preview, video).toTypedArray<UseCase>(),
            )
            boundMode = PerceptionMode.LEDGER_OFF
        }
        if (state == RecorderState.IDLE) move(RecorderEvent.PREVIEW_READY)
    }

    /**
     * Single still into [out]. ImageCapture left the bind graphs in W10-03
     * (the start photo is the ledger's best frame, W11-01), so this stays
     * only for API compatibility and fails honestly until the ledger wires
     * it; the video recording path is untouched.
     */
    override suspend fun takePhoto(out: Path): Path {
        val capture = imageCapture
            ?: error("takePhoto has no still source on graph ${graph.name}; the ledger (W11-01) supplies the start photo")
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
        val startSignal = CompletableDeferred<Long>()
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
                        startSignal.complete(SystemClock.elapsedRealtimeNanos())
                    is VideoRecordEvent.Finalize ->
                        signal.complete(event)
                    else -> Unit
                }
            }
        activeVideoOut = out
        // Suspend for the camera's own Start event (≤ 1 s) instead of
        // polling it: startNs is the camera's truth when it arrives, the
        // call-site clock when it never does (W10-02).
        val eventNs = try {
            withTimeout(1_000L) { startSignal.await() }
        } catch (e: TimeoutCancellationException) {
            null
        }
        return resolveVideoStartNs(eventNs, callNs)
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
