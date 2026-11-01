package app.simt.echo.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [MediaTools] (plan W1-05, W3-01): [MediaMetadataRetriever] for
 * frame extraction, [Bitmap.createBitmap] for crops, Media3 [Transformer]
 * for silent H.264 clips. Frames are rotated to displayed orientation,
 * scaled to [maxSide], and written as JPEG.
 */
class AndroidMediaTools(
    private val context: Context,
) : MediaTools {

    override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.toString())
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            val clamped = clampFrameTime(atMs, durationMs)

            var frame = retriever.getScaledFrameAtTime(
                clamped * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                maxSide,
                maxSide,
            ) ?: return

            // Rotate to displayed orientation (plan W1-05).
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
                if (rotated !== frame) frame.recycle()
                frame = rotated
            }

            // Scale down if still larger than maxSide.
            if (frame.width > maxSide || frame.height > maxSide) {
                val scale = maxSide.coerceAtMost(maxOf(frame.width, frame.height)).toFloat() /
                    maxOf(frame.width, frame.height).toFloat()
                if (scale < 1f) {
                    val scaled = Bitmap.createScaledBitmap(
                        frame,
                        (frame.width * scale).toInt().coerceAtLeast(1),
                        (frame.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (scaled !== frame) frame.recycle()
                    frame = scaled
                }
            }

            out.toFile().outputStream().use { os ->
                frame.compress(Bitmap.CompressFormat.JPEG, 82, os)
            }
            frame.recycle()
        } finally {
            retriever.release()
        }
    }

    override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) {
        val padded = padBox(box)
        val full = input.toFile().inputStream().use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return

        try {
            val w = full.width
            val h = full.height
            val left = (padded.left * w).toInt().coerceIn(0, w - 1)
            val top = (padded.top * h).toInt().coerceIn(0, h - 1)
            val right = (padded.right * w).toInt().coerceIn(left + 1, w)
            val bottom = (padded.bottom * h).toInt().coerceIn(top + 1, h)

            var cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)

            // Scale to maxSide.
            if (cropped.width > maxSide || cropped.height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(cropped.width, cropped.height).toFloat()
                val scaled = Bitmap.createScaledBitmap(
                    cropped,
                    (cropped.width * scale).toInt().coerceAtLeast(1),
                    (cropped.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== cropped) cropped.recycle()
                cropped = scaled
            }

            out.toFile().outputStream().use { os ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 85, os)
            }
            cropped.recycle()
        } finally {
            full.recycle()
        }
    }

    override suspend fun cutClip(video: Path, startMs: Long, endMs: Long, maxFps: Int, out: Path): ClipInfo {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(video.toFile()))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setFrameRate(maxFps)
            .build()

        // The Transformer must run on the main looper (plan W3-01); the
        // listener delivers completion or an [ExportException].
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .build()

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
            transformer.start(edited, out.toString())
            cont.invokeOnCancellation { transformer.cancel() }
        }
        transformer.removeAllListeners()

        // Read back the exported clip's metadata (plan W3-01 → ClipInfo).
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.toString())
            val frameCount = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
            )?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: (endMs - startMs)
            return ClipInfo(out, frameCount, durationMs)
        } finally {
            retriever.release()
        }
    }
}
