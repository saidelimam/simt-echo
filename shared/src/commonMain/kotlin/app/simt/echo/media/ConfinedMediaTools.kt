package app.simt.echo.media

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Confines a [MediaTools] to [io] (W10-02 G1): the blocking operations —
 * frame decode, duration probe, crop decode/encode and their file IO — are
 * never run on the caller's dispatcher, so a caller on Main (a
 * `viewModelScope`) needs no `withContext` of its own.
 *
 * The Transformer operations ([cutClip], [forgeStripClip],
 * [transcodeStripClip]) are deliberately passed through: Media3's
 * Transformer must start on a looper thread (plan W3-01), and it suspends
 * on its own callback rather than blocking, so the delegate owns any file IO
 * it does after the export.
 */
class ConfinedMediaTools(
    private val delegate: MediaTools,
    private val io: CoroutineDispatcher,
) : MediaTools {

    override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) =
        withContext(io) { delegate.extractFrame(video, atMs, maxSide, out) }

    override suspend fun extractFrames(video: Path, frames: List<FrameRequest>) =
        withContext(io) { delegate.extractFrames(video, frames) }

    override suspend fun videoDurationMs(video: Path): Long? =
        withContext(io) { delegate.videoDurationMs(video) }

    override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) =
        withContext(io) { delegate.cropJpeg(input, box, maxSide, out) }

    override suspend fun cutClip(video: Path, startMs: Long, endMs: Long, maxFps: Int, out: Path): ClipInfo =
        delegate.cutClip(video, startMs, endMs, maxFps, out)

    override val supportsStripForge: Boolean
        get() = delegate.supportsStripForge

    override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo =
        delegate.forgeStripClip(frames, out)

    override suspend fun transcodeStripClip(video: Path, startMs: Long, endMs: Long, out: Path): ClipInfo =
        delegate.transcodeStripClip(video, startMs, endMs, out)
}
