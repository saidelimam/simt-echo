package app.simt.echo.transfer

import app.simt.echo.media.ClipInfo
import app.simt.echo.media.CropBox
import app.simt.echo.media.MediaTools
import okio.FileSystem
import okio.Path

/**
 * Media tools for a walk in progress: strips forge, but the video is still
 * being written, so any attempt to read it is a failure of the test subject.
 */
internal class FakeStripMedia(
    private val fileSystem: FileSystem,
    override val supportsStripForge: Boolean = true,
) : MediaTools {
    val forged = mutableListOf<String>()
    var videoCuts = 0

    override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) = error("no extraction while walking")

    override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path) = error("no crops")

    override suspend fun cutClip(video: Path, startMs: Long, endMs: Long, maxFps: Int, out: Path): ClipInfo {
        videoCuts++
        error("the video is still being written")
    }

    override suspend fun forgeStripClip(frames: List<Path>, out: Path): ClipInfo {
        forged += "${out.name}:${frames.size}"
        out.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(out) { write("forged".encodeToByteArray()) }
        return ClipInfo(out, frameCount = frames.size, durationMs = frames.size * 250L)
    }
}
