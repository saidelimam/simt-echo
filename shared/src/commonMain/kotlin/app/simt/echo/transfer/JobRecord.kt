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
)
