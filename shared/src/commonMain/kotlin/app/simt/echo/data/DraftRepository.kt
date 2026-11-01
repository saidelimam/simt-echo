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

    fun loadCapture(draftId: String): CaptureLog? {
        val text = store.readText(captureFile(draftId)) ?: return null
        return EchoJson.decodeFromString<CaptureLog>(text)
    }

    fun saveDraftRoute(route: EchoRoute) {
        // Draft routes are keyed by routeId until Save assigns permanence.
        store.writeText(draftRouteFile(route.routeId), EchoJson.encodeToString(route))
    }

    fun loadDraftRoute(routeId: String): EchoRoute? {
        val text = store.readText(draftRouteFile(routeId)) ?: return null
        return EchoJson.decodeFromString<EchoRoute>(text)
    }

    /**
     * Raw bytes of a file inside draft [draftId], or null (keyframe heroes,
     * start photo, manual crops). Reads, never writes.
     */
    fun readDraftFile(draftId: String, fileName: String): ByteArray? =
        store.readBytes("$DRAFTS_DIR/$draftId/$fileName")

    /** Persists the cloud job record (`job.json`) next to the draft. */
    fun saveJobRecord(draftId: String, record: JobRecord) {
        store.writeText("$DRAFTS_DIR/$draftId/$JOB_FILE", EchoJson.encodeToString(record))
    }

    /** The persisted cloud job record, or null when never submitted. */
    fun loadJobRecord(draftId: String): JobRecord? {
        val text = store.readText("$DRAFTS_DIR/$draftId/$JOB_FILE") ?: return null
        return EchoJson.decodeFromString<JobRecord>(text)
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

    private fun captureFile(draftId: String) = "$DRAFTS_DIR/$draftId/capture.json"

    private fun draftRouteFile(routeId: String) = "$DRAFTS_DIR/$routeId/route.draft.json"

    private companion object {
        const val DRAFTS_DIR = "drafts"
        const val JOB_FILE = "job.json"
    }
}
