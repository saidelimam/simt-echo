package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import okio.Path

// iOS is out of v1 scope (plan W5-03 Accepted): the stub keeps the target
// compiling without pretending file exchange exists.

@Composable
actual fun rememberFileSharing(): FileSharing = remember {
    object : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean =
            error("File sharing is Android-only in v1")

        override suspend fun pickDocument(): ByteArray? =
            error("File sharing is Android-only in v1")
    }
}
