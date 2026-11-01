package app.simt.echo.data

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * All file access under `Platform.dataDir` goes through here (plan §5.3),
 * so tests run on `FakeFileSystem` and no slice touches `java.io.File`
 * from common code. Paths are relative to [dataDir] unless absolute.
 */
class FileStore(
    val fileSystem: FileSystem,
    val dataDir: Path,
) {
    private fun resolve(path: String): Path {
        val relative = path.toPath()
        return if (relative.isAbsolute) relative else dataDir / relative
    }

    /** Absolute path for a store-relative [path] (camera/media writers). */
    fun resolvePath(path: String): Path = resolve(path)

    fun readText(path: String): String? {
        val target = resolve(path)
        if (!fileSystem.exists(target)) return null
        return fileSystem.read(target) { readUtf8() }
    }

    fun readBytes(path: String): ByteArray? {
        val target = resolve(path)
        if (!fileSystem.exists(target)) return null
        return fileSystem.read(target) { readByteArray() }
    }

    fun writeText(path: String, text: String) {
        val target = resolve(path)
        target.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(target) { writeUtf8(text) }
    }

    /**
     * Write-then-rename: a kill mid-write leaves the previous complete
     * file untouched (plus a stray `.tmp`), never a truncated original.
     * Used where a torn write would destroy the only copy — the capture
     * autosave that process-death recovery reads (W4-03).
     */
    fun writeTextAtomic(path: String, text: String) {
        val target = resolve(path)
        val tmp = resolve("$path.tmp")
        target.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(tmp) { writeUtf8(text) }
        fileSystem.atomicMove(tmp, target)
    }

    fun writeBytes(path: String, bytes: ByteArray) {
        val target = resolve(path)
        target.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(target) { write(bytes) }
    }

    /** Lists one directory (non-recursive); empty when it does not exist. */
    fun list(path: String): List<Path> {
        val target = resolve(path)
        if (!fileSystem.exists(target)) return emptyList()
        return fileSystem.list(target)
    }

    /** Deletes a file or directory tree; missing paths are a no-op. */
    fun deleteRecursively(path: String) {
        fileSystem.deleteRecursively(resolve(path))
    }

    fun exists(path: String): Boolean = fileSystem.exists(resolve(path))
}
