package app.simt.echo.data

import app.simt.echo.model.CaptureLog
import app.simt.echo.transfer.JobRecord
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import kotlinx.serialization.encodeToString

/** In-progress captures under `drafts/<draftId>/` (plan §5.3). */
class DraftRepository(private val store: FileStore) {

    /** Absolute path of [fileName] inside draft [draftId]'s directory. */
    fun draftFilePath(draftId: String, fileName: String): okio.Path =
        store.resolvePath("$DRAFTS_DIR/$draftId/$fileName")

    fun saveCapture(log: CaptureLog) {
        // Atomic: the 10 s autosave is exactly what a kill mid-record
        // leaves behind; a torn write must not destroy the last good log.
        store.writeTextAtomic(captureFile(log.draftId), EchoJson.encodeToString(log))
    }

    /** The capture log, or null when absent/corrupt — a torn autosave
     * or hand-edited `capture.json` degrades to "no recoverable log",
     * never a throw into recovery/screens (W7-10). */
    fun loadCapture(draftId: String): CaptureLog? {
        val text = store.readText(captureFile(draftId)) ?: return null
        return runCatching { EchoJson.decodeFromString<CaptureLog>(text) }.getOrNull()
    }

    fun saveDraftRoute(route: EchoRoute) {
        // Draft routes are keyed by routeId until Save assigns permanence.
        store.writeText(draftRouteFile(route.routeId), EchoJson.encodeToString(route))
    }

    /** The draft route, or null when absent/corrupt (W7-10). */
    fun loadDraftRoute(routeId: String): EchoRoute? {
        val text = store.readText(draftRouteFile(routeId)) ?: return null
        return runCatching { EchoJson.decodeFromString<EchoRoute>(text) }.getOrNull()
    }

    /**
     * Raw bytes of a file inside draft [draftId], or null (keyframe heroes,
     * start photo, manual crops). Reads, never writes.
     */
    fun readDraftFile(draftId: String, fileName: String): ByteArray? =
        store.readBytes("$DRAFTS_DIR/$draftId/$fileName")

    /**
     * True when the draft file exists. Metadata only — existence and
     * counting must use this (or [draftFileSize]), never [readDraftFile],
     * which loads the whole file into RAM (W10-02 G3).
     */
    fun draftFileExists(draftId: String, fileName: String): Boolean =
        store.exists("$DRAFTS_DIR/$draftId/$fileName")

    /** Size in bytes, or null when absent. Metadata only (W10-02 G3). */
    fun draftFileSize(draftId: String, fileName: String): Long? =
        store.size("$DRAFTS_DIR/$draftId/$fileName")

    /** Persists the cloud job record (`job.json`) next to the draft. */
    fun saveJobRecord(draftId: String, record: JobRecord) {
        store.writeText("$DRAFTS_DIR/$draftId/$JOB_FILE", EchoJson.encodeToString(record))
    }

    /** The persisted cloud job record, or null when never submitted,
     * absent, or corrupt (W7-10). */
    fun loadJobRecord(draftId: String): JobRecord? {
        val text = store.readText("$DRAFTS_DIR/$draftId/$JOB_FILE") ?: return null
        return runCatching { EchoJson.decodeFromString<JobRecord>(text) }.getOrNull()
    }

    /** Clears the persisted cloud job record (delete-now, resubmit). */
    fun clearJobRecord(draftId: String) {
        store.deleteRecursively("$DRAFTS_DIR/$draftId/$JOB_FILE")
    }

    /** Draft ids with a capture present, newest directory name last. */
    fun listDraftIds(): List<String> =
        store.list(DRAFTS_DIR).map { it.name }.sorted()

    fun delete(draftId: String) {
        store.deleteRecursively("$DRAFTS_DIR/$draftId")
    }

    /**
     * Deletes every draft (W8-11 Delete all). Returns how many
     * draft directories were removed.
     */
    fun deleteAll(): Int {
        val count = store.list(DRAFTS_DIR).size
        store.deleteRecursively(DRAFTS_DIR)
        return count
    }

    private fun captureFile(draftId: String) = "$DRAFTS_DIR/$draftId/capture.json"

    private fun draftRouteFile(routeId: String) = "$DRAFTS_DIR/$routeId/route.draft.json"

    private companion object {
        const val DRAFTS_DIR = "drafts"
        const val JOB_FILE = "job.json"
    }
}
