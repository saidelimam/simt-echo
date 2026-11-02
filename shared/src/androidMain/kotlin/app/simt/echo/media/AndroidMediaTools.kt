package app.simt.echo.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.EditedMediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Android [MediaTools] (plan W1-05, W3-01): [MediaMetadataRetriever] for
 * frame extraction, [Bitmap.createBitmap] for crops, Media3 [Transformer]
 * for silent H.264 clips. Frames are rotated to displayed orientation,
 * scaled to [maxSide], and written as JPEG.
 *
 * Threading (W10-02 G1): decode and file IO run on [ioDispatcher], bitmap
 * scaling on [computeDispatcher] — callers on Main stay off the main thread
 * without their own `withContext`. Dispatchers are injectable for host tests.
 * A StrictMode run over Record → Processing must show zero main-thread disk
 * reads here (device evidence owed by the closing slice; T reviewer).
 */
class AndroidMediaTools(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaTools {

    override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
        val batch = listOf(FrameRequest(atMs, maxSide, out))
        extractFrames(video, batch)
    }

    /**
     * One retriever for the whole batch (W10-02 G1): keyframe extraction for
     * a route opens `video.mp4` once, not once per frame.
     */
    override suspend fun extractFrames(video: Path, frames: List<FrameRequest>) =
        withContext(ioDispatcher) {
            if (frames.isEmpty()) return@withContext
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.toString())
                val durationMs = retriever.durationMs()
                val rotation = retriever.rotationDegrees()
                for (frame in frames) {
                    extractOne(retriever, durationMs, rotation, frame.atMs, frame.maxSide, frame.out)
                }
            } finally {
                retriever.release()
            }
        }

    /** Metadata probe only — never decodes a frame (W10-02 G5). */
    override suspend fun videoDurationMs(video: Path): Long? = withContext(ioDispatcher) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.toString())
            retriever.durationMs().takeIf { it > 0L }
        } finally {
            retriever.release()
        }
    }

    override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) =
        withContext(ioDispatcher) {
            val padded = padBox(box)
            val full = input.toFile().inputStream().use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@withContext

            try {
                val w = full.width
                val h = full.height
                val left = (padded.left * w).toInt().coerceIn(0, w - 1)
                val top = (padded.top * h).toInt().coerceIn(0, h - 1)
                val right = (padded.right * w).toInt().coerceIn(left + 1, w)
                val bottom = (padded.bottom * h).toInt().coerceIn(top + 1, h)

                var cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)

                // Scale to maxSide on Default (W10-02 G1).
                if (cropped.width > maxSide || cropped.height > maxSide) {
                    val source = cropped
                    cropped = withContext(computeDispatcher) { source.scaledTo(maxSide) }
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
        // Clamp the end to the video duration (W10-02 G5): capture-relative
        // windows can overshoot short videos. Unknown duration keeps the
        // caller's window, as before.
        val durationMs = videoDurationMs(video)
        val safeEnd = if (durationMs != null && durationMs > 0L) endMs.coerceAtMost(durationMs) else endMs
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(video.toFile()))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(safeEnd)
                    .build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setFrameRate(maxFps)
            .build()

        // The Transformer must run on the main looper (plan W3-01); the
        // listener delivers completion or an [ExportException]. The default
        // bitrate is kept here deliberately: this is the legacy W3-01 cut,
        // not the W11-03 forge (see the G5 verdict in AndroidClipForge.kt).
        export(context, out, useForgeBitrate = false) { transformer ->
            transformer.start(edited, out.toString())
        }
        return describeExport(out, safeEnd - startMs, ioDispatcher)
    }

    /**
     * Strip forge available: Media3 Transformer image-sequence input
     * ([forgeStripFromJpegs]). See the G5 verdict in AndroidClipForge.kt.
     */
    override val supportsStripForge: Boolean = true

    override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo =
        forgeStripFromJpegs(context, frames, out)

    override suspend fun transcodeStripClip(video: Path, startMs: Long, endMs: Long, out: Path): ClipInfo {
        val durationMs = videoDurationMs(video)
        return transcodeStripFromVideo(context, video, startMs, endMs, durationMs, out)
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun MediaMetadataRetriever.durationMs(): Long =
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    private fun MediaMetadataRetriever.rotationDegrees(): Int =
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

    /**
     * Decodes, rotates to displayed orientation (plan W1-05), scales and
     * writes one frame using an open [retriever]. Runs on IO; the bitmap
     * scale hops to Default (W10-02 G1).
     */
    private suspend fun extractOne(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        rotation: Int,
        atMs: Long,
        maxSide: Int,
        out: Path,
    ) {
        val clamped = clampFrameTime(atMs, durationMs)

        var frame = retriever.getScaledFrameAtTime(
            clamped * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
            maxSide,
            maxSide,
        ) ?: return

        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
            if (rotated !== frame) frame.recycle()
            frame = rotated
        }

        if (frame.width > maxSide || frame.height > maxSide) {
            val source = frame
            frame = withContext(computeDispatcher) { source.scaledTo(maxSide) }
        }

        out.toFile().outputStream().use { os ->
            frame.compress(Bitmap.CompressFormat.JPEG, 82, os)
        }
        frame.recycle()
    }

    /** Scales [this] to ≤ [maxSide] px; recycles the source when replaced. */
    private fun Bitmap.scaledTo(maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(width, height).toFloat()
        if (scale >= 1f) return this
        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== this) recycle()
        return scaled
    }
}
