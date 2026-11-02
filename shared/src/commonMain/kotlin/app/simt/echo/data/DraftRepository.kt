package app.simt.echo.data

import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.model.CaptureLog
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.ReadyStagedRecord
import app.simt.echo.transfer.UploadRecord
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

    /** Writes raw [bytes] to [fileName] inside draft [draftId]'s directory. */
    fun writeDraftFile(draftId: String, fileName: String, bytes: ByteArray) {
        store.writeBytes("$DRAFTS_DIR/$draftId/$fileName", bytes)
    }

    /**
     * Persists the recording's kept ledger entries (`glimpse/entries.json`,
     * plan W11-02) so Review builds keyframes from the ledger without
     * seeking the video. JPEG bytes live beside it under `glimpse/`,
     * referenced draft-relative by [GlimpseEntry.jpegPath].
     */
    fun saveGlimpseEntries(draftId: String, entries: List<GlimpseEntry>) {
        store.writeText("$DRAFTS_DIR/$draftId/$GLIMPSE_ENTRIES_FILE", EchoJson.encodeToString(entries))
    }

    /**
     * The persisted ledger entries, or null when absent/corrupt — a draft
     * recorded without the ledger (or a torn write) degrades to "no
     * ledger", never a throw; keyframes fall back to video (W7-10).
     */
    fun loadGlimpseEntries(draftId: String): List<GlimpseEntry>? {
        val text = store.readText("$DRAFTS_DIR/$draftId/$GLIMPSE_ENTRIES_FILE") ?: return null
        return runCatching { EchoJson.decodeFromString<List<GlimpseEntry>>(text) }.getOrNull()
    }

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

    /**
     * Persists the hash-first upload session (`upload.json`, plan
     * W11-08) so a restart resumes with `PUT missing[]` instead of
     * re-uploading. Cleared when the job is created.
     */
    fun saveUploadRecord(draftId: String, record: UploadRecord) {
        store.writeText("$DRAFTS_DIR/$draftId/$UPLOAD_FILE", EchoJson.encodeToString(record))
    }

    /** The persisted upload session, or null when never started, consumed, or corrupt. */
    fun loadUploadRecord(draftId: String): UploadRecord? {
        val text = store.readText("$DRAFTS_DIR/$draftId/$UPLOAD_FILE") ?: return null
        return runCatching { EchoJson.decodeFromString<UploadRecord>(text) }.getOrNull()
    }

    /** Clears the persisted upload session (job created, stale restart). */
    fun clearUploadRecord(draftId: String) {
        store.deleteRecursively("$DRAFTS_DIR/$draftId/$UPLOAD_FILE")
    }

    /**
     * Persists the ready-on-arrival staged sessions (`ready-upload.json`,
     * plan W11-09): one upload id per closed checkpoint window, so a
     * restart still discards (or finishes against) every staged blob.
     * Cleared on job create and on discard.
     */
    fun saveReadyStagedRecord(draftId: String, record: ReadyStagedRecord) {
        store.writeText("$DRAFTS_DIR/$draftId/$READY_UPLOAD_FILE", EchoJson.encodeToString(record))
    }

    /** Staged walk-upload session ids, or null when none were staged, consumed, or corrupt. */
    fun loadReadyStagedRecord(draftId: String): List<String>? {
        val text = store.readText("$DRAFTS_DIR/$draftId/$READY_UPLOAD_FILE") ?: return null
        return runCatching { EchoJson.decodeFromString<ReadyStagedRecord>(text).uploadIds }.getOrNull()
    }

    /** Clears the staged-session record (job created, discard done). */
    fun clearReadyStagedRecord(draftId: String) {
        store.deleteRecursively("$DRAFTS_DIR/$draftId/$READY_UPLOAD_FILE")
    }

    /**
     * Persists accumulated partial checkpoint candidates
     * (`partials.json`, plan W11-08) as they land on the event stream,
     * so Review can render them before the job completes. Stored as the
     * raw `AiJobResult`-shaped JSON; this repository stays AI-agnostic.
     */
    fun savePartialResult(draftId: String, resultJson: String) {
        store.writeText("$DRAFTS_DIR/$draftId/$PARTIALS_FILE", resultJson)
    }

    /** The persisted partial result JSON, or null when none has landed. */
    fun loadPartialResult(draftId: String): String? =
        store.readText("$DRAFTS_DIR/$draftId/$PARTIALS_FILE")

    /** Clears persisted partials (job finished, draft consumed, retry). */
    fun clearPartialResult(draftId: String) {
        store.deleteRecursively("$DRAFTS_DIR/$draftId/$PARTIALS_FILE")
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
        const val UPLOAD_FILE = "upload.json"
        const val READY_UPLOAD_FILE = "ready-upload.json"
        const val PARTIALS_FILE = "partials.json"
        const val GLIMPSE_ENTRIES_FILE = "glimpse/entries.json"
    }
}
