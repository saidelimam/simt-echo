package app.simt.echo.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Media3 Transformer clip forge (plan W11-03): strip JPEGs → H.264 MP4 at
 * [CLIP_FORGE_FPS] fps, 480 px short side, ~600 kbps, plus the
 * video-transcode fallback. Called from [AndroidMediaTools], which owns the
 * threading contract (W10-02 G1); like `cutClip`, the Transformer must run
 * on the main looper (plan W3-01), so these stay on the caller's thread.
 *
 * G5 verdict (W11-03, verified against the pinned media3-transformer 1.10.1
 * sources, not on device): `EditedMediaItem.setFrameRate` is **not** inert
 * on video. For inputs with an intrinsic frame rate it acts as a **maximum**:
 * frames above the max are dropped, below it nothing happens (never
 * upsampled). So the old `cutClip(…, 10, …)` did cap 30 fps video to ≤ 10
 * fps — the "believed inert" suspicion is refuted in the cap direction (and
 * confirmed only for upsampling, which never happens). For image inputs the
 * rate is mandatory (`ImageAssetLoader` checkStates it) and sets the output
 * fps. The old clips were still expensive because 5 s at ≤ 10 fps is ≈ 50
 * full-resolution frames at the default bitrate — which is what this forge
 * replaces, not the frame-rate cap.
 */

/** Shared video effects for both forge paths: 480p + drop to the forge fps. */
@OptIn(UnstableApi::class)
internal fun forgeVideoEffects(): Effects = Effects(
    listOf(),
    listOf(
        Presentation.createForHeight(CLIP_FORGE_SHORT_SIDE_PX),
        FrameDropEffect.createDefaultFrameDropEffect(CLIP_FORGE_FPS.toFloat()),
    ),
)

/** Encoder factory pinning the forge target bitrate (~600 kbps H.264). */
@OptIn(UnstableApi::class)
internal fun forgeEncoderFactory(context: Context): DefaultEncoderFactory =
    DefaultEncoderFactory.Builder(context)
        .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder()
                .setBitrate(CLIP_FORGE_VIDEO_BITRATE_BPS)
                .build(),
        )
        .build()

/**
 * Runs one export: builds the Transformer (H.264, optional forge bitrate),
 * delivers completion or [ExportException] through the listener, and removes
 * the listeners on every exit path; cancellation propagates as the
 * [kotlinx.coroutines.CancellationException] it is. Must be called on the
 * main looper (plan W3-01).
 */
@OptIn(UnstableApi::class)
internal suspend fun export(
    context: Context,
    out: Path,
    useForgeBitrate: Boolean,
    start: (Transformer) -> Unit,
) {
    val builder = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
    if (useForgeBitrate) builder.setEncoderFactory(forgeEncoderFactory(context))
    val transformer = builder.build()
    try {
        suspendCancellableCoroutine<Unit> { cont ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    error: ExportException,
                ) {
                    cont.resumeWithException(error)
                }
            }
            transformer.addListener(listener)
            // Before start: a cancel that lands during start still stops the export.
            cont.invokeOnCancellation { transformer.cancel() }
            start(transformer)
        }
    } finally {
        // Completion, export error and cancellation alike: a Transformer
        // must not keep a listener (and through it the continuation).
        transformer.removeAllListeners()
    }
}

/** Reads back one exported clip's metadata on IO (plan W3-01 → ClipInfo). */
internal suspend fun describeExport(
    out: Path,
    fallbackDurationMs: Long,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ClipInfo = withContext(ioDispatcher) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(out.toString())
        val frameCount = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
        )?.toIntOrNull() ?: 0
        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION,
        )?.toLongOrNull() ?: fallbackDurationMs
        ClipInfo(out, frameCount, durationMs)
    } finally {
        retriever.release()
    }
}

/**
 * Primary forge: ordered strip JPEGs → one MP4. Each image shows for
 * exactly one frame interval (`imageDurationMs` + `durationUs` per item, the
 * pair `ImageAssetLoader` requires), so output frame `i` is `frames[i]`
 * and [buildFrameMap] over the same entries is the exact sidecar.
 */
@OptIn(UnstableApi::class)
internal suspend fun forgeStripFromJpegs(
    context: Context,
    frames: List<Path>,
    out: Path,
): ClipInfo {
    require(frames.isNotEmpty()) { "Strip forge needs at least one frame" }
    val frameUs = 1_000_000L / CLIP_FORGE_FPS
    val items = frames.map { jpeg ->
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(jpeg.toFile()))
            .setImageDurationMs(frameUs / 1_000L)
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setDurationUs(frameUs)
            .setFrameRate(CLIP_FORGE_FPS)
            .setRemoveAudio(true)
            .setEffects(forgeVideoEffects())
            .build()
    }
    val composition = Composition.Builder(
        // Video-only sequence: the items carry `setRemoveAudio(true)`, and
        // TRACK_TYPE_VIDEO makes the sequence itself emit video only.
        EditedMediaItemSequence.withVideoFrom(items),
    ).build()
    export(context, out, useForgeBitrate = true) { transformer ->
        transformer.start(composition, out.toString())
    }
    return describeExport(out, frames.size * frameUs / 1_000L)
}

/**
 * Fallback forge: transcodes [window] of [video] at the forge fps / 480p /
 * ~600 kbps. The window arrives clamped to the duration by the caller
 * (`stripFallbackWindow`, W10-02 G5) and validated non-empty.
 */
@OptIn(UnstableApi::class)
internal suspend fun transcodeStripFromVideo(
    context: Context,
    video: Path,
    window: ClipWindow,
    out: Path,
): ClipInfo {
    val item = MediaItem.Builder()
        .setUri(Uri.fromFile(video.toFile()))
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(window.startMs)
                .setEndPositionMs(window.endMs)
                .build(),
        )
        .build()
    val edited = EditedMediaItem.Builder(item)
        .setRemoveAudio(true)
        .setFrameRate(CLIP_FORGE_FPS)
        .setEffects(forgeVideoEffects())
        .build()
    export(context, out, useForgeBitrate = true) { transformer ->
        transformer.start(edited, out.toString())
    }
    return describeExport(out, window.endMs - window.startMs)
}
