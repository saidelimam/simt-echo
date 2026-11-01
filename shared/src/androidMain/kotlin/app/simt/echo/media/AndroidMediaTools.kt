package app.simt.echo.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import okio.Path

/**
 * Android [MediaTools] (plan W1-05): [MediaMetadataRetriever] for frame
 * extraction, [Bitmap.createBitmap] for crops. Frames are rotated to
 * displayed orientation, scaled to [maxSide], and written as JPEG.
 */
class AndroidMediaTools : MediaTools {

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
}
