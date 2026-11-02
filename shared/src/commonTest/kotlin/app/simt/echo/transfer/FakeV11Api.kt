package app.simt.echo.transfer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Scripted v1.1 API for the walk-time upload tests: records every call, fails on demand. */
internal class FakeV11Api : V11CloudApi {
    val uploadCreates = mutableListOf<List<UploadFileRef>>()
    val puts = mutableListOf<UploadFile>()
    val uploadDeletes = mutableListOf<String>()
    val stagedCreates = mutableListOf<Triple<String, List<String>, String>>()
    var deleteError: EchoApiError? = null
    var putError: EchoApiError? = null
    var staleOnCreate: EchoApiError? = null
    private var nextUpload = 0

    override suspend fun submit(manifestJson: String, files: List<UploadFile>): String = "job-x"

    override suspend fun get(jobId: String): JobPoll = JobPoll("complete", JobStage.COMPLETE)

    override suspend fun delete(jobId: String) = Unit

    override suspend fun samEnabled(): Boolean = false

    override suspend fun createUpload(files: List<UploadFileRef>): CreatedUpload {
        uploadCreates += files
        nextUpload++
        return CreatedUpload("upload-$nextUpload", files.map { it.name })
    }

    override suspend fun uploadMissing(uploadId: String): List<String> = emptyList()

    override suspend fun putUploadFile(uploadId: String, file: UploadFile) {
        putError?.let { throw it }
        puts += file
    }

    override suspend fun createJob(manifestJson: String, uploadId: String, idempotencyKey: String, resumeOf: String?): String =
        "job-x"

    override suspend fun deleteUpload(uploadId: String) {
        deleteError?.let { throw it }
        uploadDeletes += uploadId
    }

    override suspend fun createJobFromStaged(
        manifestJson: String,
        uploadIds: List<String>,
        idempotencyKey: String,
    ): String {
        staleOnCreate?.let { throw it }
        stagedCreates += Triple(manifestJson, uploadIds, idempotencyKey)
        return "job-1"
    }

    override fun watchEvents(jobId: String, lastEventId: String?): Flow<JobEvent> = emptyFlow()
}
