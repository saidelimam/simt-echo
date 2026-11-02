package app.simt.echo.transfer

import kotlinx.serialization.Serializable

/**
 * Persisted upload state (`drafts/<draftId>/job.json`, plan W3-02): the
 * server job id plus the last seen status, so a restart resumes polling
 * instead of resubmitting. Written at submit and on every status change,
 * cleared when the draft is consumed or the user deletes the job.
 */
@Serializable
data class JobRecord(
    val jobId: String,
    val status: String,
    val updatedAtMs: Long,
    /**
     * Hash-first upload session the job was created from (plan W11-08),
     * kept for diagnostics after the create. Null for legacy multipart
     * submits and for records written before v1.1.
     */
    val uploadId: String? = null,
    /**
     * Last SSE event id seen while watching the job (plan W11-08), so a
     * restart resumes the stream without replaying delivered partials.
     */
    val lastEventId: String? = null,
)
