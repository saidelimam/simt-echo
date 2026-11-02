package app.simt.echo.ui.screens.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.ai.AiCandidate
import app.simt.echo.ai.AiCheckpoint
import app.simt.echo.ai.AiJobResult
import app.simt.echo.ai.AiModels
import app.simt.echo.ai.CandidateMerger
import app.simt.echo.capture.FaceDetector
import app.simt.echo.data.DraftRepository
import app.simt.echo.media.Manifest
import app.simt.echo.media.MediaTools
import app.simt.echo.media.ensureKeyframes
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.media.sha256Hex
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.platform.LiveUpdateDirector
import app.simt.echo.platform.PROCESSING_LIVE_UPDATE_ID
import app.simt.echo.platform.platformIoDispatcher
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.EchoApiError
import app.simt.echo.transfer.FACE_FILTER_DISCLOSURE
import app.simt.echo.transfer.FALLBACK_POLL_DELAYS_MS
import app.simt.echo.transfer.JobEvent
import app.simt.echo.transfer.JobRecord
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.PreparedUpload
import app.simt.echo.transfer.UploadFileRef
import app.simt.echo.transfer.UploadPreparer
import app.simt.echo.transfer.UploadRecord
import app.simt.echo.transfer.V11CloudApi
import app.simt.echo.transfer.createJobFromWalkStaging
import app.simt.echo.transfer.discardWalkStaging
import app.simt.echo.ui.copy.processingErrorCopy
import app.simt.echo.ui.copy.processingJobErrorCopy
import app.simt.echo.ui.copy.processingJobGoneCopy
import app.simt.echo.ui.copy.processingJobTimeoutCopy
import app.simt.echo.ui.copy.processingNoDraftCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

/** What the consent sheet offers to send (plan W3-02 copy). */
data class ConsentInfo(
    val photos: Int,
    val clips: Int,
    val samEnabled: Boolean,
    /**
     * True only when an on-device face detector is wired into the upload
     * path (plan W11-04). The face-filter sentence in [consentCopy] renders
     * only then — production passes no detector today, and the copy must
     * not promise a pre-filter that is not active (audit W14-04).
     */
    val faceFilterActive: Boolean = false,
)

/** How long declining waits for staged uploads to be deleted before it leaves anyway. */
private const val DISCARD_WAIT_MS = 5_000L

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
    val face = if (info.faceFilterActive) " " + FACE_FILTER_DISCLOSURE else ""
    return "Send ${info.photos} photos$clips to cloud AI on Cloudflare " +
        "(Meta's Llama 4 Scout on Workers AI; Meta Model API if enabled) via SIMT ECHO's server " +
        "to suggest landmarks. Files are deleted within 24 h (or now, from this screen). " +
        "If the Meta Model API is enabled, its standard tier is used, not the contributor tier " +
        "whose inputs may be used for training. Your route shape stays on this phone." +
        face
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
     * the keep-default flags. A non-null detector fails closed: only a
     * keyframe byte-identical to a scanned ledger frame can upload (the
     * rest are withheld and left out of the manifest), clips are forged
     * only from face-cleared strip frames, and a node without a cleared
     * strip gets no clip — the video-transcode path has no frame seam to
     * scan (no ML Kit face dep in the catalog yet), so it is never used.
     */
    private val faceDetector: FaceDetector? = null,
    /**
     * Where disk reads/writes and hashing run: keyframe filling, clip
     * cutting + sidecars, and upload staging all touch draft bytes, and
     * the screen's scope is Main.
     */
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher,
    /**
     * Lock-screen progress (plan W13-10). Null posts nothing. Otherwise
     * every phase change is mirrored by [LiveUpdateDirector.updateProcessing]:
     * ongoing while uploading or working, dismissed once the phase settles.
     */
    private val liveUpdates: LiveUpdateDirector? = null,
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

    /**
     * Constellation state (plan W13-06): one star per checkpoint, moved
     * only by `checkpoint` events, plus the frame-budget meter fed only
     * by `usage` events. Stars reflect the last-seen event and nothing
     * else — no timers, no stage inference.
     */
    private val _constellation = MutableStateFlow(ConstellationUiState())
    val constellation: StateFlow<ConstellationUiState> = _constellation.asStateFlow()

    /** Last-seen checkpoint status per node id (QUEUED when absent). */
    private val starStates = mutableMapOf<String, StarState>()

    /** Max-seen billed frames across `usage` events; never decreases. */
    private var framesUsed: Int = 0

    /** Sticky: the worker's governor refused a call for frame budget. */
    private var budgetExhausted: Boolean = false

    private var counts: ConsentInfo = ConsentInfo(0, 0, false)

    /** Route the in-flight upload/watch was built from; feeds partial merges. */
    private var lastRoute: EchoRoute? = null

    /** Accumulated v1.1 partial checkpoints for this job. */
    private var partialResult: AiJobResult? = null

    /** Builds the manifest and allow-listed files; its clip cache lives as long as this screen. */
    private val preparer = UploadPreparer(drafts, media, draftId, faceDetector, ioDispatcher, nowMs)

    init {
        if (liveUpdates != null) {
            viewModelScope.launch {
                ui.collect { runCatching { liveUpdates.updateProcessing(phase = it.phase) } }
            }
        }
        refresh()
    }

    /**
     * Polling stops with the screen, so an ongoing notification would
     * keep claiming progress nobody is watching: dismiss it. The job
     * itself is untouched; re-entering resumes it.
     */
    override fun onCleared() {
        runCatching { liveUpdates?.dismiss(PROCESSING_LIVE_UPDATE_ID) }
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
            try {
                ensureKeyframes(drafts, media, draftId, route, ioDispatcher = ioDispatcher)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Missing keyframes degrade to fewer photos; consent still shows.
            }
            val sam = runCatching { api.samEnabled() }.getOrDefault(false)
            val specs = keyframeSpecs(route.nodes)
            val photos = specs.sumOf { spec ->
                spec.timesMs.indices.count { i ->
                    drafts.draftFileExists(draftId, spec.fileName(i))
                }
            }
            val checkpoints = route.nodes.count { it.type != NodeType.START && it.captureTMs != null }
            counts = ConsentInfo(photos, if (sam) checkpoints else 0, sam, faceFilterActive = faceDetector != null)
            // Fresh skeleton for the constellation: the route is known,
            // no events have been seen in this session yet.
            lastRoute = route
            starStates.clear()
            framesUsed = 0
            budgetExhausted = false
            publishConstellation()
            val job = drafts.loadJobRecord(draftId)
            if (job != null && job.status != "complete" && job.status != "errored") {
                _ui.update { it.copy(phase = ProcessingPhase.Working(JobStage.QUEUED), jobId = job.jobId) }
                lastRoute = route
                restorePartials()
                // Replay the stream from its head, not from the persisted
                // Last-Event-ID: the stars and the frame meter are rebuilt
                // only from `checkpoint` / `usage` events (W13-06), and the
                // skeleton above starts empty, so resuming mid-stream would
                // show already-grounded nodes as Queued and the meter at 0.
                // Replays are idempotent (partials merge per node; the meter
                // keeps its high-water mark).
                watchOrPoll(job.jobId, lastEventId = null)
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
        // A previous job still on the server (errored, not deleted) is the
        // one a fresh job may reuse SAM work from (`resumeOf`).
        val previousJobId = _ui.value.jobId
        _ui.update { it.copy(phase = ProcessingPhase.Uploading) }
        viewModelScope.launch {
            try {
                val v11 = api as? V11CloudApi
                if (v11 != null) sendV11(v11, resumeOf = previousJobId) else sendLegacy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = ProcessingPhase.Failed(processingErrorCopy())) }
            }
        }
    }

    private suspend fun prepareUpload(): PreparedUpload {
        val route = checkNotNull(drafts.loadDraftRoute(draftId)) { "Draft is gone." }
        val log = checkNotNull(drafts.loadCapture(draftId)) { "Capture log is gone." }
        return preparer.prepare(route, log, samEnabled = counts.samEnabled).also {
            if (it.files.isEmpty()) throw EchoApiError.Server(0, "No keyframes to send.")
        }
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
     * A persisted session for the same upload resumes instead of
     * re-uploading, and resumes with its *stored* manifest and hash: the
     * manifest embeds the consent clock, so a rebuilt one would never match
     * and a lost `202` would create a second billed job. A stale session
     * (reforged bytes, expiry) restarts once from a fresh declaration.
     */
    private suspend fun sendV11(v11: V11CloudApi, resumeOf: String?, restarted: Boolean = false) {
        val prepared = prepareUpload()
        lastRoute = prepared.route
        if (!restarted) clearPartials()
        createJobFromWalk(v11, prepared, resumeOf)?.let { return watchNewJob(it, uploadId = null) }
        val stored = resumableRecord(drafts.loadUploadRecord(draftId), prepared.manifestJson)
        val resume = stored != null
        val manifestJson = stored?.manifestJson ?: prepared.manifestJson
        val manifestSha = stored?.manifestSha256 ?: sha256Hex(manifestJson.encodeToByteArray())
        val uploadId: String
        var missing: List<String>
        if (resume) {
            checkNotNull(stored)
            uploadId = stored.uploadId
            missing = try {
                v11.uploadMissing(uploadId)
            } catch (e: EchoApiError.UploadStale) {
                return restartUpload(v11, resumeOf, restarted)
            }
        } else {
            val refs = withContext(ioDispatcher) {
                prepared.files.map { file ->
                    UploadFileRef(file.id, sha256Hex(file.bytes), file.bytes.size.toLong())
                }
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
            return restartUpload(v11, resumeOf, restarted)
        }
        val jobId = v11.createJob(manifestJson, uploadId, manifestSha, resumeOf)
        drafts.clearUploadRecord(draftId)
        watchNewJob(jobId, uploadId)
    }

    /**
     * The job built from the uploads staged while walking (W11-09), sending only
     * what changed since; null means upload in full. That is the case when nothing
     * was staged, when a session is already in flight, when this is a re-send of an
     * errored job (staged jobs cannot name `resumeOf`) and when the worker no
     * longer holds the staged sessions (the dead record is already cleared).
     */
    private suspend fun createJobFromWalk(v11: V11CloudApi, prepared: PreparedUpload, resumeOf: String?): String? {
        if (resumeOf != null || drafts.loadUploadRecord(draftId) != null) return null
        return try {
            withContext(ioDispatcher) {
                createJobFromWalkStaging(v11, drafts, draftId, prepared.manifestJson, prepared.files, sha256Hex(prepared.manifestJson.encodeToByteArray()))
            }
        } catch (e: EchoApiError.UploadStale) {
            null
        }
    }

    private suspend fun watchNewJob(jobId: String, uploadId: String?) {
        persist(jobId, "queued", uploadId = uploadId)
        _ui.update { it.copy(phase = ProcessingPhase.Working(JobStage.QUEUED), jobId = jobId) }
        watchOrPoll(jobId, lastEventId = null)
    }

    /**
     * The persisted upload session when it is intact (its manifest still
     * hashes to the stored sha) and describes the same upload as the
     * freshly built [freshManifestJson] — identical but for the consent
     * clock, which is the only field that differs between two builds of
     * the same draft. Null means build and declare a new session.
     */
    private fun resumableRecord(stored: UploadRecord?, freshManifestJson: String): UploadRecord? {
        if (stored == null) return null
        if (sha256Hex(stored.manifestJson.encodeToByteArray()) != stored.manifestSha256) return null
        val storedManifest = decodeManifestOrNull(stored.manifestJson) ?: return null
        val freshManifest = decodeManifestOrNull(freshManifestJson) ?: return null
        return stored.takeIf { storedManifest.withoutConsentClock() == freshManifest.withoutConsentClock() }
    }

    private fun decodeManifestOrNull(json: String): Manifest? =
        runCatching { EchoJson.decodeFromString<Manifest>(json) }.getOrNull()

    private fun Manifest.withoutConsentClock(): Manifest = copy(consent = consent.copy(atEpochMs = 0L))

    /** Clears a stale session and runs the v1.1 send once more, fresh. */
    private suspend fun restartUpload(v11: V11CloudApi, resumeOf: String?, alreadyRestarted: Boolean) {
        drafts.clearUploadRecord(draftId)
        if (alreadyRestarted) throw EchoApiError.Server(0, "Upload session keeps failing.")
        sendV11(v11, resumeOf, restarted = true)
    }

    /**
     * Consent Not now / error Continue without AI: back to Review. Anything
     * staged while walking is deleted first, so declining leaves nothing on the
     * server. A slow or failed delete never holds the user here: the worker
     * sweeps staged uploads after an hour.
     */
    fun decline(onDone: () -> Unit) {
        viewModelScope.launch {
            val v11 = api as? V11CloudApi
            if (v11 != null) {
                try {
                    withTimeoutOrNull(DISCARD_WAIT_MS) { discardWalkStaging(v11, drafts, draftId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Kept on the record; the worker's staging TTL is the backstop.
                }
            }
            onDone()
        }
    }

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
            is JobEvent.Usage -> onUsageEvent(event)
            is JobEvent.Unknown -> Unit
        }
        return false
    }

    /**
     * Records one `usage` event on the budget meter (plan W13-06): the
     * billed-frames high-water mark only moves up, and the exhausted flag
     * is sticky. Anything else about the event is ignored — the meter
     * shows what the worker metered, never an estimate.
     */
    private fun onUsageEvent(event: JobEvent.Usage) {
        val frames = event.samFramesBilled ?: event.samFrames
        if (frames != null && frames > framesUsed) framesUsed = frames
        if (event.budgetExhausted) budgetExhausted = true
        publishConstellation()
    }

    /** Publishes the constellation from the route + last-seen event state. */
    private fun publishConstellation() {
        val route = lastRoute ?: return
        _constellation.update {
            ConstellationUiState(
                stars = constellationStars(route, starStates),
                framesUsed = framesUsed,
                budgetExhausted = budgetExhausted,
            )
        }
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
        // The star moves on the event status alone — even when the
        // candidates below turn out garbled, the checkpoint happened.
        starStateForStatus(event.status)?.let { starStates[event.nodeId] = it }
        publishConstellation()
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
        starStates.clear()
        framesUsed = 0
        budgetExhausted = false
        publishConstellation()
    }

    /**
     * Polling fallback after the stream fails (plan W11-08): backs off
     * through [FALLBACK_POLL_DELAYS_MS] (2 → 4 → 8 → 16 s) and then holds
     * at the last gap until the overall deadline — `maxPolls` of the
     * first gap, the 300 s the fixed-interval [poll] allows — because SAM
     * jobs take minutes. Same transient tolerance as [poll], `job.json`
     * on stage change only. The deadline counts the scheduled gaps, so it
     * is exact under a virtual clock.
     */
    private suspend fun fallbackPoll(jobId: String) {
        var consecutiveFailures = 0
        val deadlineMs = maxPolls * FALLBACK_POLL_DELAYS_MS.first()
        var elapsedMs = 0L
        var attempt = 0
        while (true) {
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
                null
            }
            if (poll != null) {
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
            val gapMs = FALLBACK_POLL_DELAYS_MS[minOf(attempt, FALLBACK_POLL_DELAYS_MS.lastIndex)]
            if (elapsedMs + gapMs > deadlineMs) break
            delay(gapMs)
            elapsedMs += gapMs
            attempt++
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
}
