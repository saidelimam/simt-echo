package app.simt.echo.ui.screens.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.ai.AiCandidate
import app.simt.echo.ai.AiCheckpoint
import app.simt.echo.ai.AiJobResult
import app.simt.echo.ai.AiModels
import app.simt.echo.ai.CandidateMerger
import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.approachStrip
import app.simt.echo.data.DraftRepository
import app.simt.echo.media.ClipFrame
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.ClipWindow
import app.simt.echo.media.ForgeSource
import app.simt.echo.media.FrameFlags
import app.simt.echo.media.KeyframeSpec
import app.simt.echo.media.ManifestBuilder
import app.simt.echo.media.ManifestConsent
import app.simt.echo.media.MediaTools
import app.simt.echo.media.buildFrameMap
import app.simt.echo.media.clipFileName
import app.simt.echo.media.clipId
import app.simt.echo.media.clipSidecarFileName
import app.simt.echo.media.clipSidecarId
import app.simt.echo.media.encodeFrameMap
import app.simt.echo.media.ensureKeyframes
import app.simt.echo.media.frameFlagsFor
import app.simt.echo.media.keyframeFiles
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.media.ledgerJpegName
import app.simt.echo.media.selectForgeSource
import app.simt.echo.media.sha256Hex
import app.simt.echo.media.stripFallbackWindow
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.EchoApiError
import app.simt.echo.transfer.FACE_FILTER_DISCLOSURE
import app.simt.echo.transfer.FALLBACK_POLL_DELAYS_MS
import app.simt.echo.transfer.JobEvent
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.UploadFile
import app.simt.echo.transfer.UploadFileRef
import app.simt.echo.transfer.UploadRecord
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.ui.copy.processingErrorCopy
import app.simt.echo.ui.copy.processingJobErrorCopy
import app.simt.echo.ui.copy.processingJobGoneCopy
import app.simt.echo.ui.copy.processingJobTimeoutCopy
import app.simt.echo.ui.copy.processingNoDraftCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
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
    /**
     * Cloud AI not configured on this build (plan W7-07): the honest
     * state — no consent copy, no stages, no progress. "Continue
     * without AI" is the primary (only) path.
     */
    data object AiUnavailable : ProcessingPhase
}

data class ProcessingUiState(
    val phase: ProcessingPhase = ProcessingPhase.Loading,
    val jobId: String? = null,
)

/** Plan consent copy (W3-02 verbatim): files, retention, training tier, shape. */
fun consentCopy(info: ConsentInfo): String {
    val clips = if (info.samEnabled) " (and ${info.clips} short silent clips if SAM is enabled)" else ""
    return "Send ${info.photos} photos$clips to cloud AI on Cloudflare " +
        "(Meta's Llama 4 Scout on Workers AI; Meta Model API if enabled) via SIMT ECHO's server " +
        "to suggest landmarks. Files are deleted within 24 h (or now, from this screen). " +
        "If the Meta Model API is enabled, its standard tier is used, not the contributor tier " +
        "whose inputs may be used for training. Your route shape stays on this phone. " +
        FACE_FILTER_DISCLOSURE
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
    /**
     * False when the build has no cloud credentials (plan W7-07:
     * `AppGraph.cloudAiConfigured`). The ViewModel then presents the
     * honest [ProcessingPhase.AiUnavailable] state and never touches
     * the network — not even the SAM probe.
     */
    private val aiConfigured: Boolean = true,
    /**
     * On-device privacy pre-filter (plan W11-04). Null keeps today's
     * behavior: no face scan, no flagging, every keyframe uploads with
     * the keep-default flags. A non-null detector flags strip frames
     * into the manifest (so the worker drops scout boxes before SAM
     * spend) and withholds face-flagged keyframe bytes from the upload.
     * Cut video clips cannot be face-scanned yet (no frame seam in the
     * Media3 cut path and no ML Kit face dep in the catalog) — owed.
     */
    private val faceDetector: FaceDetector? = null,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProcessingUiState())
    val ui: StateFlow<ProcessingUiState> = _ui.asStateFlow()

    /**
     * Partial checkpoint candidates landed from the v1.1 event stream
     * (plan W11-08), keyed by node id. Review renders these before the
     * job completes (wired in W13-05); the merge is the same pure
     * [CandidateMerger.merge] the completed result takes, so geometry
     * stays sensor-owned either way.
     */
    private val _partials = MutableStateFlow<Map<String, List<AiCandidate>>>(emptyMap())
    val partials: StateFlow<Map<String, List<AiCandidate>>> = _partials.asStateFlow()

    private var counts: ConsentInfo = ConsentInfo(0, 0, false)

    /** Route the in-flight upload/watch was built from; feeds partial merges. */
    private var lastRoute: EchoRoute? = null

    /** Accumulated v1.1 partial checkpoints for this job. */
    private var partialResult: AiJobResult? = null

    /** Cut clips keyed by forge path: `clip:<node>` + window or `strip:<node>` + strip span (W11-03). */
    private val clipCache = mutableMapOf<String, ClipInfo>()

    init {
        refresh()
    }

    /** (Re)loads the draft; resumes polling when a live job is persisted. */
    fun refresh() {
        viewModelScope.launch {
            val route = drafts.loadDraftRoute(draftId)
            if (route == null) {
                _ui.update { it.copy(phase = ProcessingPhase.Failed(processingNoDraftCopy())) }
                return@launch
            }
            if (!aiConfigured) {
                // Unconfigured build (plan W7-07): honest state first.
                // No keyframe extraction, no SAM probe, no poll resume —
                // a persisted job from another build must not render
                // fake "cloud" progress here.
                _ui.update { it.copy(phase = ProcessingPhase.AiUnavailable, jobId = null) }
                return@launch
            }
            runCatching { ensureKeyframes(drafts, media, draftId, route) }
            val sam = runCatching { api.samEnabled() }.getOrDefault(false)
            val specs = keyframeSpecs(route.nodes)
            val photos = specs.sumOf { spec ->
                spec.timesMs.indices.count { i ->
                    drafts.draftFileExists(draftId, spec.fileName(i))
                }
            }
            val checkpoints = route.nodes.count { it.type != NodeType.START && it.captureTMs != null }
            counts = ConsentInfo(photos, if (sam) checkpoints else 0, sam)
            val job = drafts.loadJobRecord(draftId)
            if (job != null && job.status != "complete" && job.status != "errored") {
                _ui.update { it.copy(phase = ProcessingPhase.Working(JobStage.QUEUED), jobId = job.jobId) }
                lastRoute = route
                restorePartials()
                watchOrPoll(job.jobId, job.lastEventId)
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
                val v11 = api as? V11CloudApi
                if (v11 != null) sendV11(v11) else sendLegacy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = ProcessingPhase.Failed(processingErrorCopy())) }
            }
        }
    }

    /** Manifest + allow-listed files, shared by the v1 and v1.1 send paths. */
    private data class PreparedUpload(
        val route: EchoRoute,
        val manifestJson: String,
        val files: List<UploadFile>,
    )

    private suspend fun prepareUpload(): PreparedUpload {
        val route = checkNotNull(drafts.loadDraftRoute(draftId)) { "Draft is gone." }
        val log = checkNotNull(drafts.loadCapture(draftId)) { "Capture log is gone." }
        val specs = keyframeSpecs(route.nodes)
        val clips = cutClips(route)
        val frameFlags = faceDetector?.let { detector ->
            val ledger = runCatching { drafts.loadGlimpseEntries(draftId) }.getOrNull() ?: emptyList()
            frameFlagsFor(route, specs, ledger, detector)
        } ?: emptyMap()
        val manifest = ManifestBuilder.build(
            route = route,
            log = log,
            specs = specs,
            clips = clips,
            consent = ManifestConsent(sendFramesToCloudAi = true, atEpochMs = nowMs()),
            frameFlags = frameFlags,
        )
        val files = collectFiles(specs, clips, frameFlags)
        if (files.isEmpty()) throw EchoApiError.Server(0, "No keyframes to send.")
        return PreparedUpload(route, EchoJson.encodeToString(manifest), files)
    }

    /**
     * Legacy v1 send (multipart `submit` + fixed-interval poll): kept for
     * servers and scripted clients without the v1.1 capability.
     */
    private suspend fun sendLegacy() {
        val prepared = prepareUpload()
        lastRoute = prepared.route
        clearPartials()
        val jobId = api.submit(prepared.manifestJson, prepared.files)
        persist(jobId, "queued")
        _ui.update { it.copy(phase = ProcessingPhase.Working(JobStage.QUEUED), jobId = jobId) }
        poll(jobId)
    }

    /**
     * Hash-first v1.1 send (plan W11-08): declare hashes, PUT only the
     * files the server still needs, then create the job with
     * `idempotencyKey = sha256(manifest)` so a retry never grounds twice.
     * A persisted session for the identical manifest resumes instead of
     * re-uploading; a stale session (reforged bytes, expiry) restarts
     * once from a fresh declaration.
     */
    private suspend fun sendV11(v11: V11CloudApi, restarted: Boolean = false) {
        val prepared = prepareUpload()
        lastRoute = prepared.route
        if (!restarted) clearPartials()
        val manifestJson = prepared.manifestJson
        val manifestSha = sha256Hex(manifestJson.encodeToByteArray())
        val stored = drafts.loadUploadRecord(draftId)
        val resume = stored != null && stored.manifestSha256 == manifestSha
        val uploadId: String
        var missing: List<String>
        if (resume) {
            checkNotNull(stored)
            uploadId = stored.uploadId
            missing = try {
                v11.uploadMissing(uploadId)
            } catch (e: EchoApiError.UploadStale) {
                return restartUpload(v11, restarted)
            }
        } else {
            val refs = prepared.files.map { file ->
                UploadFileRef(file.id, sha256Hex(file.bytes), file.bytes.size.toLong())
            }
            val created = v11.createUpload(refs)
            drafts.saveUploadRecord(draftId, UploadRecord(created.uploadId, manifestJson, manifestSha, nowMs()))
            uploadId = created.uploadId
            missing = created.missing
        }
        try {
            val byId = prepared.files.associateBy { it.id }
            missing.forEach { id -> byId[id]?.let { v11.putUploadFile(uploadId, it) } }
        } catch (e: EchoApiError.UploadStale) {
            return restartUpload(v11, restarted)
        }
        val jobId = v11.createJob(manifestJson, uploadId, manifestSha)
        drafts.clearUploadRecord(draftId)
        persist(jobId, "queued", uploadId = uploadId)
        _ui.update { it.copy(phase = ProcessingPhase.Working(JobStage.QUEUED), jobId = jobId) }
        watchOrPoll(jobId, lastEventId = null)
    }

    /** Clears a stale session and runs the v1.1 send once more, fresh. */
    private suspend fun restartUpload(v11: V11CloudApi, alreadyRestarted: Boolean) {
        drafts.clearUploadRecord(draftId)
        if (alreadyRestarted) throw EchoApiError.Server(0, "Upload session keeps failing.")
        sendV11(v11, restarted = true)
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
                drafts.clearUploadRecord(draftId)
                clearPartials()
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
            drafts.clearUploadRecord(draftId)
            clearPartials()
            _ui.update { it.copy(phase = ProcessingPhase.Consent(counts, hasJob = false), jobId = null) }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private var lastPersistedStatus: String? = null
    private var lastPersistedStage: JobStage? = null

    /**
     * Writes `job.json` on stage change only (plan W11-08 G11): repeats
     * of the same status + stage skip the write, so a minutes-long job
     * no longer persists every poll tick.
     */
    private fun persist(
        jobId: String,
        status: String,
        stage: JobStage? = null,
        uploadId: String? = null,
        lastEventId: String? = null,
    ) {
        if (status == lastPersistedStatus && stage == lastPersistedStage) return
        lastPersistedStatus = status
        lastPersistedStage = stage
        drafts.saveJobRecord(draftId, JobRecord(jobId, status, nowMs(), uploadId, lastEventId))
    }

    /**
     * v1.1 progress (plan W11-08): the SSE stream first, exponential-
     * backoff polling fallback (2 → 4 → 8 → 16 s) when the stream is
     * down. Legacy clients keep the fixed-interval [poll].
     */
    private suspend fun watchOrPoll(jobId: String, lastEventId: String?) {
        val v11 = api as? V11CloudApi ?: return poll(jobId)
        if (streamToTerminal(v11, jobId, lastEventId)) return
        fallbackPoll(jobId)
    }

    /**
     * Collects the event stream to a terminal event. A drop mid-stream
     * reconnects once with `Last-Event-ID`; a stream that never opens
     * (or ends eventless) drops straight to polling. Returns true when
     * the UI reached a terminal phase.
     */
    private suspend fun streamToTerminal(v11: V11CloudApi, jobId: String, startEventId: String?): Boolean {
        var lastId = startEventId
        repeat(2) {
            var sawEvents = false
            var terminal = false
            try {
                v11.watchEvents(jobId, lastId).takeWhile { !terminal }.collect { event ->
                    sawEvents = true
                    if (event.id.isNotEmpty()) lastId = event.id
                    if (handleStreamEvent(jobId, event, lastId)) terminal = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EchoApiError.JobNotFound) {
                drafts.clearJobRecord(draftId)
                drafts.clearUploadRecord(draftId)
                clearPartials()
                _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobGoneCopy())) }
                return true
            } catch (e: Exception) {
                // Reconnect below when events already flowed (same
                // Last-Event-ID), else fall through to polling.
            }
            if (!sawEvents) return false
            if (terminal) return true
        }
        return false
    }

    /** Applies one stream event; true when the phase is terminal. */
    private suspend fun handleStreamEvent(jobId: String, event: JobEvent, lastId: String?): Boolean {
        when (event) {
            is JobEvent.Stage -> {
                persist(jobId, event.stage.toJobStatus(), event.stage, lastEventId = lastId)
                when (event.stage) {
                    JobStage.COMPLETE -> {
                        _ui.update { it.copy(phase = ProcessingPhase.Ready) }
                        return true
                    }
                    JobStage.ERRORED -> {
                        _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobErrorCopy())) }
                        return true
                    }
                    else -> _ui.update { it.copy(phase = ProcessingPhase.Working(event.stage)) }
                }
            }
            is JobEvent.Checkpoint -> onPartialCheckpoint(jobId, event, lastId)
            is JobEvent.Complete -> {
                persist(jobId, "complete", JobStage.COMPLETE, lastEventId = lastId)
                _ui.update { it.copy(phase = ProcessingPhase.Ready) }
                return true
            }
            is JobEvent.Error -> {
                persist(jobId, "errored", JobStage.ERRORED, lastEventId = lastId)
                _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobErrorCopy())) }
                return true
            }
            is JobEvent.Usage, is JobEvent.Unknown -> Unit
        }
        return false
    }

    /**
     * Merges one partial `checkpoint` event into Review's view of the
     * job (plan W11-08): the candidates accumulate per node, persist to
     * `partials.json`, and publish through the same pure
     * [CandidateMerger.merge] as the completed result — geometry stays
     * sensor-owned. A garbled event is skipped, never fatal.
     */
    private fun onPartialCheckpoint(jobId: String, event: JobEvent.Checkpoint, lastId: String?) {
        val route = lastRoute ?: drafts.loadDraftRoute(draftId) ?: return
        lastRoute = route
        val candidates = runCatching {
            EchoJson.decodeFromString<List<AiCandidate>>(event.candidatesRaw)
        }.getOrNull() ?: return
        val current = partialResult
        val kept = (current?.checkpoints ?: emptyList()).filter { it.nodeId != event.nodeId }
        val snapshot = AiJobResult(
            jobId = current?.jobId ?: jobId,
            models = current?.models ?: AiModels(),
            checkpoints = kept + AiCheckpoint(event.nodeId, candidates),
        )
        partialResult = snapshot
        drafts.savePartialResult(draftId, EchoJson.encodeToString(snapshot))
        _partials.update { CandidateMerger.merge(route, snapshot) }
        persist(jobId, "running", JobStage.GROUNDING, lastEventId = lastId)
    }

    /** Re-publishes persisted partials when resuming a live job. */
    private fun restorePartials() {
        val route = lastRoute ?: return
        val raw = drafts.loadPartialResult(draftId) ?: return
        val parsed = runCatching { EchoJson.decodeFromString<AiJobResult>(raw) }.getOrNull() ?: return
        partialResult = parsed
        _partials.update { CandidateMerger.merge(route, parsed) }
    }

    /** Drops partial state on fresh starts, retries and deletes. */
    private fun clearPartials() {
        partialResult = null
        _partials.update { emptyMap() }
        drafts.clearPartialResult(draftId)
    }

    /**
     * Polling fallback after the stream fails (plan W11-08): at most
     * five polls (`FALLBACK_POLL_DELAYS_MS` gaps), the same transient
     * tolerance as [poll], `job.json` on stage change only.
     */
    private suspend fun fallbackPoll(jobId: String) {
        var consecutiveFailures = 0
        val attempts = minOf(maxPolls, FALLBACK_POLL_DELAYS_MS.size + 1)
        repeat(attempts) { index ->
            if (index > 0) delay(FALLBACK_POLL_DELAYS_MS[index - 1])
            val poll = try {
                api.get(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EchoApiError.JobNotFound) {
                drafts.clearJobRecord(draftId)
                _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobGoneCopy())) }
                return
            } catch (e: Exception) {
                consecutiveFailures++
                if (consecutiveFailures > maxTransientPollFailures) {
                    _ui.update { it.copy(phase = ProcessingPhase.Failed(processingErrorCopy())) }
                    return
                }
                return@repeat
            }
            consecutiveFailures = 0
            persist(jobId, poll.status, poll.stage)
            when (poll.status) {
                "complete" -> {
                    _ui.update { it.copy(phase = ProcessingPhase.Ready) }
                    return
                }
                "errored" -> {
                    _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobErrorCopy())) }
                    return
                }
                else -> _ui.update { it.copy(phase = ProcessingPhase.Working(poll.stage)) }
            }
        }
        _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobTimeoutCopy())) }
    }

    private fun JobStage.toJobStatus(): String = when (this) {
        JobStage.COMPLETE -> "complete"
        JobStage.ERRORED -> "errored"
        JobStage.QUEUED -> "queued"
        else -> "running"
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
                _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobGoneCopy())) }
                return
            } catch (e: Exception) {
                // A transient blip must not kill a minutes-long job or push
                // the user into a destructive re-upload: tolerate a few in
                // a row before declaring failure (review 0001 R3).
                consecutiveFailures++
                if (consecutiveFailures > maxTransientPollFailures) {
                    _ui.update { it.copy(phase = ProcessingPhase.Failed(processingErrorCopy())) }
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
                    _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobErrorCopy())) }
                    return
                }
                else -> _ui.update { it.copy(phase = ProcessingPhase.Working(poll.stage)) }
            }
        }
        _ui.update { it.copy(phase = ProcessingPhase.Failed(processingJobTimeoutCopy())) }
    }

    /** Forges one ≤12-frame 480p clip per checkpoint when SAM is enabled (plan W11-03).
     *
     * Primary path: the node's approach strip (W11-02 ledger frames) through
     * the image-sequence forge, with a `frames.json` sidecar mapping output
     * frame index → (tNs, odometer m) so `visibleFromM` is exact. Fallback:
     * a 3 s video transcode with an empty-frames sidecar — honest about the
     * unknown map. Every sidecar carries the clip's SHA-256.
     *
     * Clip ends clamp to the video duration (W10-02 G5), and forges are
     * cached per forge path: a second Send reuses the file on disk instead
     * of re-encoding it. A cache entry is only trusted while its output file
     * still exists.
     */
    private suspend fun cutClips(route: EchoRoute): Map<String, ClipInfo> {
        if (!counts.samEnabled) return emptyMap()
        val videoFile = CaptureSession.VIDEO_FILE
        val video = drafts.draftFilePath(draftId, videoFile)
        val hasVideo = drafts.draftFileExists(draftId, videoFile)
        if (!hasVideo && !media.supportsStripForge) return emptyMap()
        val ledger = drafts.loadGlimpseEntries(draftId) ?: emptyList()
        val durationMs = if (hasVideo) runCatching { media.videoDurationMs(video) }.getOrNull() else null
        val out = mutableMapOf<String, ClipInfo>()
        for (node in route.nodes) {
            val t = node.captureTMs ?: continue
            if (node.type == NodeType.START) continue
            val file = clipFileName(node.id)
            try {
                val strip = approachStrip(ledger, node.atDistanceM)
                val kept = strip.filter { entry ->
                    val name = ledgerJpegName(draftId, entry)
                    name != null && drafts.draftFileExists(draftId, name)
                }
                if (selectForgeSource(kept.size, media.supportsStripForge) == ForgeSource.IMAGE_STRIP) {
                    val key = "strip:$draftId:${node.id}:${kept.first().tNs}-${kept.last().tNs}-${kept.size}"
                    val info = cachedOrForge(key, file) {
                        media.forgeStripClip(
                            kept.map { entry ->
                                drafts.draftFilePath(draftId, checkNotNull(ledgerJpegName(draftId, entry)))
                            },
                            drafts.draftFilePath(draftId, file),
                        )
                    }
                    writeSidecar(node.id, file, source = "image-strip", map = buildFrameMap(kept))
                    out[node.id] = info
                } else {
                    if (!hasVideo) continue
                    val window = stripFallbackWindow(t, durationMs)
                    val key = "clip:$draftId:${node.id}:${window.startMs}-${window.endMs}"
                    val info = cachedOrForge(key, file) {
                        media.transcodeStripClip(
                            video,
                            window.startMs,
                            window.endMs,
                            drafts.draftFilePath(draftId, file),
                        )
                    }
                    writeSidecar(node.id, file, source = "video-transcode", map = emptyList())
                    out[node.id] = info
                }
            } catch (e: Exception) {
                // One bad clip omits that node; the manifest stays valid.
            }
        }
        return out
    }

    /** Reuses a cached forge while its output file still exists (W10-02 G5). */
    private suspend fun cachedOrForge(key: String, file: String, forge: suspend () -> ClipInfo): ClipInfo {
        val cached = clipCache[key]
        if (cached != null && drafts.draftFileExists(draftId, file)) return cached
        return forge().also { clipCache[key] = it }
    }

    /** Writes one node's `frames.json` sidecar with the clip's SHA-256. */
    private fun writeSidecar(nodeId: String, clipFile: String, source: String, map: List<ClipFrame>) {
        val sha = drafts.readDraftFile(draftId, clipFile)?.let(::sha256Hex)
        val json = encodeFrameMap(map, source = source, clipSha256 = sha)
        drafts.writeDraftFile(draftId, clipSidecarFileName(nodeId), json.encodeToByteArray())
    }

    /**
     * Upload allow-list: keyframe bytes plus cut clips. Keyframes the
     * pre-filter flagged `faceFree == false` are withheld — their bytes
     * never leave the phone (the manifest still carries the flag so the
     * worker drops the box too). Bad-quality (non-face) frames still
     * upload; the worker drops those boxes itself.
     */
    private fun collectFiles(
        specs: List<KeyframeSpec>,
        clips: Map<String, ClipInfo>,
        frameFlags: Map<String, FrameFlags> = emptyMap(),
    ): List<UploadFile> {
        val files = mutableListOf<UploadFile>()
        for ((id, name) in keyframeFiles(specs)) {
            if (frameFlags[id]?.faceFree == false) continue
            val bytes = drafts.readDraftFile(draftId, name) ?: continue
            files += UploadFile(id, "image/jpeg", bytes)
        }
        for (node in clips.keys) {
            val bytes = drafts.readDraftFile(draftId, clipFileName(node)) ?: continue
            files += UploadFile(clipId(node), "video/mp4", bytes)
            // The exact frame map rides beside its clip (W11-03); the worker
            // derives the sidecar id from the clip id by convention.
            drafts.readDraftFile(draftId, clipSidecarFileName(node))?.let { sidecar ->
                files += UploadFile(clipSidecarId(node), "application/json", sidecar)
            }
        }
        return files
    }
}
