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
    companion object {
        /** Pending plaintext exports (W2-07 `.echo`, W6-01 `trials.csv`):
         *  app-visible, share-sheet-bound, bounded lifetime. */
        const val EXPORTS_DIR = "exports"
    }

    private fun resolve(path: String): Path {
        val relative = path.toPath()
        return if (relative.isAbsolute) relative else dataDir / relative
    }

    /** Absolute path for a store-relative [path] (camera/media writers). */
    fun resolvePath(path: String): Path = resolve(path)

    fun readText(path: String): String? {
        val target = resolve(path)
        // Corrupt/missing storage never throws into callers (W7-10):
        // a path deleted between the exists check and the read, a
        // directory where a file was expected, or any IO failure
        // degrades to "absent" and the repositories fall back to
        // their honest empty states.
        return runCatching {
            if (!fileSystem.exists(target)) return null
            fileSystem.read(target) { readUtf8() }
        }.getOrNull()
    }

    fun readBytes(path: String): ByteArray? {
        val target = resolve(path)
        return runCatching {
            if (!fileSystem.exists(target)) return null
            fileSystem.read(target) { readByteArray() }
        }.getOrNull()
    }

    /**
     * Size in bytes, or null when absent/unreadable. Metadata only — it
     * never opens the file, so answering "does it exist / how big" for a
     * video must use this (or [exists]), never [readBytes] (W10-02 G3).
     */
    fun size(path: String): Long? {
        val target = resolve(path)
        return runCatching { fileSystem.metadataOrNull(target)?.size }.getOrNull()
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

    /** Lists one directory (non-recursive); empty when it does not exist
     * or is not a directory (a file where a directory was expected is
     * corrupt storage, not a crash). */
    fun list(path: String): List<Path> {
        val target = resolve(path)
        return runCatching {
            if (!fileSystem.exists(target)) return emptyList()
            fileSystem.list(target)
        }.getOrDefault(emptyList())
    }

    /** Deletes a file or directory tree; missing paths are a no-op. */
    fun deleteRecursively(path: String) {
        runCatching { fileSystem.deleteRecursively(resolve(path)) }
    }

    fun exists(path: String): Boolean =
        runCatching { fileSystem.exists(resolve(path)) }.getOrDefault(false)
}
