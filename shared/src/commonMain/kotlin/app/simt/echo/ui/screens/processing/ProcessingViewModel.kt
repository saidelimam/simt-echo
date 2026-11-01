package app.simt.echo.ui.screens.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.capture.CaptureSession
import app.simt.echo.data.DraftRepository
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.KeyframeSpec
import app.simt.echo.media.ManifestBuilder
import app.simt.echo.media.ManifestConsent
import app.simt.echo.media.MediaTools
import app.simt.echo.media.clipFileName
import app.simt.echo.media.clipId
import app.simt.echo.media.clipWindow
import app.simt.echo.media.ensureKeyframes
import app.simt.echo.media.keyframeFiles
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.EchoApiError
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.UploadFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** What the consent sheet offers to send (plan W3-02 copy). */
data class ConsentInfo(
    val photos: Int,
    val clips: Int,
    val samEnabled: Boolean,
)

/** Processing screen phase (plan §8.3): consent → stages → Ready. */
sealed interface ProcessingPhase {
    data object Loading : ProcessingPhase
    data class Consent(val info: ConsentInfo, val hasJob: Boolean) : ProcessingPhase
    data object Uploading : ProcessingPhase
    data class Working(val stage: JobStage) : ProcessingPhase
    data object Ready : ProcessingPhase
    data class Failed(val message: String) : ProcessingPhase
}

data class ProcessingUiState(
    val phase: ProcessingPhase = ProcessingPhase.Loading,
    val jobId: String? = null,
)

/** Plan consent copy (W3-02 verbatim): files, retention, training tier, shape. */
fun consentCopy(info: ConsentInfo): String {
    val clips = if (info.samEnabled) " (and ${info.clips} five-second silent clips if SAM is enabled)" else ""
    return "Send ${info.photos} photos$clips to cloud AI on Cloudflare " +
        "(Meta's Llama 4 Scout on Workers AI; Meta Model API if enabled) via SIMT ECHO's server " +
        "to suggest landmarks. Files are deleted within 24 h (or now, from this screen). " +
        "If the Meta Model API is enabled, its standard tier is used, not the contributor tier " +
        "whose inputs may be used for training. Your route shape stays on this phone."
}

/**
 * Consent + cloud upload + job polling (plan W3-02). Pure orchestration:
 * the draft route, capture log and keyframes stay on disk; only the
 * manifest plus allow-listed keyframe/clip bytes leave the phone, and only
 * after an explicit [send]. `job.json` persists the job id + status so a
 * restart resumes polling instead of resubmitting.
 *
 * Fail-closed: nothing uploads without consent, without an app token, or
 * when the draft has no media to send.
 */
class ProcessingViewModel(
    private val draftId: String,
    private val drafts: DraftRepository,
    private val media: MediaTools,
    private val api: CloudApi,
    private val nowMs: () -> Long,
    private val pollIntervalMs: Long = 2_000L,
    private val maxPolls: Int = 150,
    private val maxTransientPollFailures: Int = 3,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProcessingUiState())
    val ui: StateFlow<ProcessingUiState> = _ui.asStateFlow()

    private var counts: ConsentInfo = ConsentInfo(0, 0, false)

    init {
        refresh()
    }

    /** (Re)loads the draft; resumes polling when a live job is persisted. */
    fun refresh() {
        viewModelScope.launch {
            val route = drafts.loadDraftRoute(draftId)
            if (route == null) {
                _ui.update { it.copy(phase = ProcessingPhase.Failed("No draft route for this upload.")) }
                return@launch
            }
            runCatching { ensureKeyframes(drafts, media, draftId, route) }
            val sam = runCatching { api.samEnabled() }.getOrDefault(false)
            val specs = keyframeSpecs(route.nodes)
            val photos = specs.sumOf { spec ->
                spec.timesMs.indices.count { i ->
                    drafts.readDraftFile(draftId, spec.fileName(i)) != null
                }
            }
            val checkpoints = route.nodes.count { it.type != NodeType.START && it.captureTMs != null }
            counts = ConsentInfo(photos, if (sam) checkpoints else 0, sam)
            val job = drafts.loadJobRecord(draftId)
            if (job != null && job.status != "complete" && job.status != "errored") {
                _ui.update { it.copy(phase = ProcessingPhase.Working(JobStage.QUEUED), jobId = job.jobId) }
                poll(job.jobId)
            } else if (job != null && job.status == "complete") {
                _ui.update { it.copy(phase = ProcessingPhase.Ready, jobId = job.jobId) }
            } else if (job != null) {
                // Errored job: its files may still be on the server, so the
                // consent screen's delete-now action must stay reachable and
                // the job must not be orphaned by clearing its id here.
                _ui.update { it.copy(phase = ProcessingPhase.Consent(counts, hasJob = true), jobId = job.jobId) }
            } else {
                _ui.update { it.copy(phase = ProcessingPhase.Consent(counts, hasJob = false), jobId = null) }
            }
        }
    }

    /**
     * Consent Send: builds the manifest, uploads files, starts polling.
     * This is the only upload path, and it is gated on the Consent phase —
     * the consent flag in the manifest is true because the user is looking
     * at the consent copy, not because a caller asserted it. The phase is
     * claimed synchronously before launching, so a double-tap cannot pass
     * the gate twice and submit two jobs (review 0001 R3).
     */
    fun send() {
        if (_ui.value.phase !is ProcessingPhase.Consent) return
        _ui.update { it.copy(phase = ProcessingPhase.Uploading) }
        viewModelScope.launch {
            try {
                val route = checkNotNull(drafts.loadDraftRoute(draftId)) { "Draft is gone." }
                val log = checkNotNull(drafts.loadCapture(draftId)) { "Capture log is gone." }
                val specs = keyframeSpecs(route.nodes)
                val clips = cutClips(route)
                val manifest = ManifestBuilder.build(
                    route = route,
                    log = log,
                    specs = specs,
                    clips = clips,
                    consent = ManifestConsent(sendFramesToCloudAi = true, atEpochMs = nowMs()),
                )
                val files = collectFiles(specs, clips)
                if (files.isEmpty()) throw EchoApiError.Server(0, "No keyframes to send.")
                val manifestJson = EchoJson.encodeToString(manifest)
                val jobId = api.submit(manifestJson, files)
                persist(jobId, "queued")
                _ui.update { it.copy(phase = ProcessingPhase.Working(JobStage.QUEUED), jobId = jobId) }
                poll(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = ProcessingPhase.Failed(friendly(e))) }
            }
        }
    }

    /** Consent Not now / error Continue without AI: back to Review. */
    fun decline(onDone: () -> Unit) = onDone()

    /**
     * Failure Retry: drops the dead job server-side, then returns to the
     * Consent phase. It never re-uploads on its own — a fresh upload needs
     * a fresh explicit Send in front of the consent copy (review 0001 R3).
     */
    fun retry() {
        val jobId = _ui.value.jobId
        viewModelScope.launch {
            if (jobId != null) {
                try {
                    api.delete(jobId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _ui.update {
                        it.copy(phase = ProcessingPhase.Failed("Could not delete the failed job from the server. Check the connection and retry."))
                    }
                    return@launch
                }
                drafts.clearJobRecord(draftId)
            }
            _ui.update { it.copy(phase = ProcessingPhase.Consent(counts, hasJob = false), jobId = null) }
        }
    }

    /**
     * Consent "delete now": removes the server's files + the job record.
     * A failed delete keeps the record and says so — silently clearing it
     * would strand the files with no way to retry (review 0001 R3).
     */
    fun deleteNow() {
        val jobId = _ui.value.jobId ?: return
        viewModelScope.launch {
            try {
                api.delete(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(phase = ProcessingPhase.Failed("Could not delete the uploaded files. They may still be on the server — check the connection and try again."))
                }
                return@launch
            }
            drafts.clearJobRecord(draftId)
            _ui.update { it.copy(phase = ProcessingPhase.Consent(counts, hasJob = false), jobId = null) }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun persist(jobId: String, status: String) {
        drafts.saveJobRecord(draftId, JobRecord(jobId, status, nowMs()))
    }

    private suspend fun poll(jobId: String) {
        var consecutiveFailures = 0
        repeat(maxPolls) {
            delay(pollIntervalMs)
            val poll = try {
                api.get(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EchoApiError.JobNotFound) {
                drafts.clearJobRecord(draftId)
                _ui.update { it.copy(phase = ProcessingPhase.Failed("The job is gone from the server.")) }
                return
            } catch (e: Exception) {
                // A transient blip must not kill a minutes-long job or push
                // the user into a destructive re-upload: tolerate a few in
                // a row before declaring failure (review 0001 R3).
                consecutiveFailures++
                if (consecutiveFailures > maxTransientPollFailures) {
                    _ui.update { it.copy(phase = ProcessingPhase.Failed(friendly(e))) }
                    return
                }
                return@repeat
            }
            consecutiveFailures = 0
            persist(jobId, poll.status)
            when (poll.status) {
                "complete" -> {
                    _ui.update { it.copy(phase = ProcessingPhase.Ready) }
                    return
                }
                "errored" -> {
                    _ui.update { it.copy(phase = ProcessingPhase.Failed(poll.error ?: "The cloud job failed.")) }
                    return
                }
                else -> _ui.update { it.copy(phase = ProcessingPhase.Working(poll.stage)) }
            }
        }
        _ui.update { it.copy(phase = ProcessingPhase.Failed("Timed out waiting for the cloud job.")) }
    }

    /** Cuts one ≤10 fps silent clip per checkpoint when SAM is enabled. */
    private suspend fun cutClips(route: EchoRoute): Map<String, ClipInfo> {
        if (!counts.samEnabled) return emptyMap()
        val videoFile = CaptureSession.VIDEO_FILE
        val video = drafts.draftFilePath(draftId, videoFile)
        if (drafts.readDraftFile(draftId, videoFile) == null) return emptyMap()
        val out = mutableMapOf<String, ClipInfo>()
        for (node in route.nodes) {
            val t = node.captureTMs ?: continue
            if (node.type == NodeType.START) continue
            val window = clipWindow(t)
            val file = clipFileName(node.id)
            try {
                val info = media.cutClip(video, window.startMs, window.endMs, 10, drafts.draftFilePath(draftId, file))
                out[node.id] = info
            } catch (e: Exception) {
                // One bad clip omits that node; the manifest stays valid.
            }
        }
        return out
    }

    private fun collectFiles(
        specs: List<KeyframeSpec>,
        clips: Map<String, ClipInfo>,
    ): List<UploadFile> {
        val files = mutableListOf<UploadFile>()
        for ((id, name) in keyframeFiles(specs)) {
            val bytes = drafts.readDraftFile(draftId, name) ?: continue
            files += UploadFile(id, "image/jpeg", bytes)
        }
        for (node in clips.keys) {
            val bytes = drafts.readDraftFile(draftId, clipFileName(node)) ?: continue
            files += UploadFile(clipId(node), "video/mp4", bytes)
        }
        return files
    }

    private fun friendly(e: Exception): String = when (e) {
        is EchoApiError -> e.message ?: "Cloud upload failed."
        else -> e.message ?: "Cloud upload failed."
    }
}
