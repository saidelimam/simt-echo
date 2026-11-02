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
import app.simt.echo.media.ForgeSource
import app.simt.echo.media.ForgedClip
import app.simt.echo.media.KeyframeSpec
import app.simt.echo.media.Manifest
import app.simt.echo.media.ManifestBuilder
import app.simt.echo.media.ManifestConsent
import app.simt.echo.media.MediaTools
import app.simt.echo.media.buildFrameMap
import app.simt.echo.media.clipFileName
import app.simt.echo.media.clipId
import app.simt.echo.media.clipSidecarFileName
import app.simt.echo.media.encodeFrameMap
import app.simt.echo.media.ensureKeyframes
import app.simt.echo.media.failClosedFrameFlags
import app.simt.echo.media.keyframeFiles
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.media.ledgerJpegName
import app.simt.echo.media.selectForgeSource
import app.simt.echo.media.sha256Hex
import app.simt.echo.media.stripClipWindow
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    /** Manifest + allow-listed files, shared by the v1 and v1.1 send paths. */
    private data class PreparedUpload(
        val route: EchoRoute,
        val manifestJson: String,
        val files: List<UploadFile>,
    )

    private suspend fun prepareUpload(): PreparedUpload = withContext(ioDispatcher) {
        val route = checkNotNull(drafts.loadDraftRoute(draftId)) { "Draft is gone." }
        val log = checkNotNull(drafts.loadCapture(draftId)) { "Capture log is gone." }
        val specs = keyframeSpecs(route.nodes)
        val clips = cutClips(route, log.videoStartNs)
        // Fail closed (W11-04): with a detector, a keyframe whose bytes are
        // not a scanned ledger frame (absent/corrupt ledger, video-filled or
        // recaptured slot) is flagged and withheld rather than uploaded blind.
        val frameFlags = faceDetector?.let { detector ->
            val ledger = drafts.loadGlimpseEntries(draftId).orEmpty()
            failClosedFrameFlags(drafts, draftId, route, specs, ledger, detector)
        } ?: emptyMap()
        val manifest = ManifestBuilder.build(
            route = route,
            log = log,
            specs = specs,
            clips = clips,
            consent = ManifestConsent(sendFramesToCloudAi = true, atEpochMs = nowMs()),
            frameFlags = frameFlags,
        )
        val files = collectFiles(manifest, specs)
        if (files.isEmpty()) throw EchoApiError.Server(0, "No keyframes to send.")
        PreparedUpload(route, EchoJson.encodeToString(manifest), files)
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

    /** Forges one ≤12-frame 480p clip per checkpoint when SAM is enabled (plan W11-03).
     *
     * Primary path: the node's approach strip (W11-02 ledger frames) through
     * the image-sequence forge, with a `frames.json` sidecar mapping output
     * frame index → (tNs, odometer m) so `visibleFromM` is exact. Fallback:
     * a 3 s video transcode with an empty-frames sidecar — honest about the
     * unknown map. Every sidecar carries the clip's SHA-256 and stays local
     * (the worker does not read it yet; see [collectFiles]).
     *
     * Each clip is returned with the video-relative window it actually
     * covers, so the manifest describes the clip that uploads. A fallback
     * window that collapses (`end <= start`, e.g. clamped past the video's
     * end) is skipped rather than cut empty.
     *
     * Clip ends clamp to the video duration (W10-02 G5), and forges are
     * cached per forge path: a second Send reuses the file on disk instead
     * of re-encoding it. A cache entry is only trusted while its output file
     * still exists.
     */
    private suspend fun cutClips(route: EchoRoute, videoStartNs: Long): Map<String, ForgedClip> {
        if (!counts.samEnabled) return emptyMap()
        val videoFile = CaptureSession.VIDEO_FILE
        val video = drafts.draftFilePath(draftId, videoFile)
        val hasVideo = drafts.draftFileExists(draftId, videoFile)
        if (!hasVideo && !media.supportsStripForge) return emptyMap()
        val ledger = drafts.loadGlimpseEntries(draftId) ?: emptyList()
        val durationMs = if (hasVideo) {
            try {
                media.videoDurationMs(video)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        val out = mutableMapOf<String, ForgedClip>()
        for (node in route.nodes) {
            val t = node.captureTMs ?: continue
            if (node.type == NodeType.START) continue
            val file = clipFileName(node.id)
            try {
                val strip = approachStrip(ledger, node.atDistanceM, faceDetector = faceDetector)
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
                    // The frame map is exact only while output frame i is
                    // strip entry i; a forge that dropped frames cannot say.
                    if (info.frameCount == kept.size) {
                        writeSidecar(node.id, file, source = "image-strip", map = buildFrameMap(kept))
                    } else {
                        writeSidecar(node.id, file, source = "video-transcode", map = emptyList())
                    }
                    out[node.id] = ForgedClip(info, stripClipWindow(kept, videoStartNs))
                } else {
                    // The video transcode has no frame seam to scan, so with a
                    // detector configured a node without a cleared strip gets no
                    // clip at all (W11-04) rather than un-scanned frames.
                    if (!hasVideo || faceDetector != null) continue
                    val window = stripFallbackWindow(t, durationMs)
                    if (window.endMs <= window.startMs) continue
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
                    out[node.id] = ForgedClip(info, window)
                }
            } catch (e: CancellationException) {
                throw e
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
     * Upload allow-list: exactly the keyframe and clip ids the [manifest]
     * lists, so a keyframe the pre-filter withheld (W11-04) never leaves
     * the phone and is never promised to the worker either. Bad-quality
     * (non-face) frames still upload; the worker drops those boxes itself.
     * The clip's `frames.json` sidecar stays on the phone until the worker
     * reads it (W11-10): its allow-list drops the sidecar id today.
     */
    private fun collectFiles(manifest: Manifest, specs: List<KeyframeSpec>): List<UploadFile> {
        val listed = manifest.checkpoints.flatMap { cp -> cp.keyframes.map { it.id } }.toSet()
        val files = mutableListOf<UploadFile>()
        for ((id, name) in keyframeFiles(specs)) {
            if (id !in listed) continue
            val bytes = drafts.readDraftFile(draftId, name) ?: continue
            files += UploadFile(id, "image/jpeg", bytes)
        }
        for (cp in manifest.checkpoints) {
            if (cp.clip == null) continue
            val bytes = drafts.readDraftFile(draftId, clipFileName(cp.nodeId)) ?: continue
            files += UploadFile(clipId(cp.nodeId), "video/mp4", bytes)
        }
        return files
    }
}
