package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.simt.echo.media.CropBox
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.MediaTools
import okio.Path

// iOS is out of v1 scope (plan W5-03 Accepted): stub keeps the target
// compiling without pretending frame extraction exists.

@Composable
actual fun rememberMediaTools(): MediaTools = remember {
    object : MediaTools {
        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path): Nothing =
            error("Frame extraction is Android-only in v1")

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path): Nothing =
            error("Frame extraction is Android-only in v1")

        override suspend fun cutClip(
            video: Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: Path,
        ): ClipInfo = error("Clip cutting is Android-only in v1")
    }
}
