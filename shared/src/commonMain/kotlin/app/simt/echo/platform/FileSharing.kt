package app.simt.echo.platform

import androidx.compose.runtime.Composable
import okio.Path

/**
 * OS file exchange (plan W2-07): export hands a plain `.echo` container to
 * the system share sheet, import picks one back through the document
 * picker. Implementations need a live Activity (FileProvider and
 * ActivityResult), so the instance is a composable seam like the other
 * device services. iOS is out of v1 scope: the iOS actual is a stub.
 */
interface FileSharing {
    /**
     * Shares the file at [path] through the system share sheet; true when
     * the chooser opened. [mime] stays generic — the container is opaque.
     */
    suspend fun shareFile(path: Path, name: String, mime: String): Boolean

    /**
     * Opens the system document picker; returns the picked file's bytes,
     * or null when the user cancels. Nothing is written to app storage.
     */
    suspend fun pickDocument(): ByteArray?
}

/** Plain containers travel unencrypted (W2-07 privacy warning). */
const val ECHO_MIME = "application/octet-stream"

@Composable
expect fun rememberFileSharing(): FileSharing
