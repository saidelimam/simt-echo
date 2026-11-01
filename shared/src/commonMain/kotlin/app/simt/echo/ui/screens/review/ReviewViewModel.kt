package app.simt.echo.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.ai.AiCandidate
import app.simt.echo.ai.AiJobResult
import app.simt.echo.ai.CandidateMerger
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.RouteRepository
import app.simt.echo.media.CropBox
import app.simt.echo.media.KEYFRAME_MAX_SIDE
import app.simt.echo.media.MediaTools
import app.simt.echo.media.ensureKeyframes
import app.simt.echo.media.keyframeFileName
import app.simt.echo.media.keyframeFiles
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.model.AiProvenance
import app.simt.echo.model.Confirmation
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.Landmark
import app.simt.echo.model.LandmarkSource
import app.simt.echo.model.defaultCue
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteNode
import app.simt.echo.platform.decodeImage
import app.simt.echo.transfer.CloudApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One node card: the sensor-owned node, its hero bytes, its landmarks. */
data class ReviewNode(
    val node: RouteNode,
    val heroBytes: ByteArray? = null,
    val landmarks: List<Landmark> = emptyList(),
)

/** Review screen state (plan §8.3): edit draft → draw landmarks → Save. */
data class ReviewUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val routeName: String = "",
    val startDescription: String = "",
    val path: List<PathPoint> = emptyList(),
    val nodes: List<ReviewNode> = emptyList(),
    /** Node id with the full-screen draw overlay open, or null. */
    val drawNodeId: String? = null,
    val drawTitle: String = "",
    /** AI suggestions by node id, rank-descending (plan W3-07). Side state:
     * unreviewed suggestions never enter the route, so skipping export is
     * structural, not a filter someone can forget. */
    val suggestions: Map<String, List<AiCandidate>> = emptyMap(),
    /** Preselected suggestion index per node (top candidate first). */
    val suggestionIndex: Map<String, Int> = emptyMap(),
    /** Candidate id → bytes of the keyframe that candidate was grounded
     * on (may differ from the node's hero; overlay and crop must use the
     * frame the box actually belongs to — review 0004 R3). */
    val suggestionImages: Map<String, ByteArray> = emptyMap(),
    /** Provenance footnote: the models that ran, or null when no AI result. */
    val aiModels: String? = null,
    val saving: Boolean = false,
    val savedRouteId: String? = null,
    val error: String? = null,
)

/**
 * Review + manual landmark editor (plan W2-01). Pure orchestration over
 * [DraftRepository], [RouteRepository] and [MediaTools]: every mutation
 * rewrites the draft route immediately, so a killed process loses nothing;
 * [save] copies geometry untouched (plan rule §0.4), nulls `captureTMs` on
 * the export copies, and deletes the consumed draft.
 *
 * Creator-verification semantics (S-reviewed surface): only an explicit
 * [confirmDraw] mints [LandmarkSource.MANUAL]/[Confirmation.CREATOR_VERIFIED];
 * this slice never writes [Confirmation.AI_SUGGESTED] (W3-07 owns those).
 *
 * AI suggestions (plan W3-07): when the draft has a completed cloud job,
 * [refresh] fetches its `result.json` through [api] and preselects the top
 * candidate per node card. Suggestions attach to sensor-owned nodes or not
 * at all — [useSuggestion] mints a [Confirmation.CREATOR_VERIFIED] landmark
 * (source from the candidate) without touching nodes, legs or path.
 */
class ReviewViewModel(
    private val draftId: String,
    private val drafts: DraftRepository,
    private val routes: RouteRepository,
    private val media: MediaTools,
    private val nowMs: () -> Long,
    private val newId: () -> String,
    /** Cloud API for the AI result; null keeps Review manual-only. */
    private val api: CloudApi? = null,
    /** Local-model router (W5-01); null keeps Review cloud/manual only. */
    private val router: app.simt.echo.ai.AiRouter? = null,
    /** "Prefer local model" from Settings (W5-01). */
    private val preferLocal: Boolean = false,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReviewUiState())
    val ui: StateFlow<ReviewUiState> = _ui.asStateFlow()

    private var route: EchoRoute? = null
    private var aiResult: AiJobResult? = null

    init {
        refresh()
    }

    /** (Re)loads the draft, extracting any missing keyframes first. */
    fun refresh() {
        viewModelScope.launch {
            try {
                val loaded = drafts.loadDraftRoute(draftId)
                if (loaded == null) {
                    _ui.update { it.copy(loading = false, missing = true) }
                    return@launch
                }
                ensureKeyframes(drafts, media, draftId, loaded)
                route = loaded
                publish(loaded)
                loadSuggestions()
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /**
     * AI suggestions for the completed cloud job, if any (plan W3-07).
     * Fail-closed and non-blocking: without an API, without a completed
     * job, or with an unreadable result, Review stays manual-only and the
     * draft is untouched. A transport failure surfaces as an error line;
     * the manual flow keeps working underneath it.
     */
    /**
     * Suggestion routing (W3-07 cloud, W5-01 local): the cloud result is
     * the default; a configured local model runs when "Prefer local" is
     * on, or when the cloud produced nothing usable. With neither, Review
     * stays manual-only and the draft is untouched.
     */
    private fun loadSuggestions() {
        viewModelScope.launch {
            val localFirst = preferLocal && router?.localAvailable == true
            if (localFirst && proposeLocally()) return@launch
            if (loadCloudSuggestions()) return@launch
            if (!localFirst && router?.localAvailable == true) {
                proposeLocally()
            }
        }
    }

    /** Cloud path (W3-07): true when a usable result was published. */
    private suspend fun loadCloudSuggestions(): Boolean {
        val api = api ?: return false
        return try {
            val job = drafts.loadJobRecord(draftId) ?: return false
            if (job.status != "complete") return false
            val raw = api.get(job.jobId).resultRaw
            if (raw == null) {
                _ui.update { it.copy(error = "The AI job finished but returned no result.") }
                return false
            }
            val parsed = CandidateMerger.parseResult(raw)
            if (parsed == null) {
                _ui.update { it.copy(error = "Could not read the AI result. You can still add landmarks by hand.") }
                return false
            }
            publishSuggestions(parsed)
            true
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: "Could not load AI suggestions.") }
            false
        }
    }

    /**
     * Local path (W5-01): builds one checkpoint per non-start node from
     * the draft's own keyframes and asks the configured provider. True
     * when candidates were published; a typed provider failure is
     * surfaced, never silently swallowed.
     */
    private suspend fun proposeLocally(): Boolean {
        val router = router ?: return false
        val current = route ?: return false
        return try {
            val checkpoints = current.nodes
                .filter { it.type != app.simt.echo.model.NodeType.START }
                .mapNotNull { node ->
                    val name = keyframeFileName(current, node) ?: return@mapNotNull null
                    val bytes = drafts.readDraftFile(draftId, name) ?: return@mapNotNull null
                    val keyframeId = keyframeFiles(keyframeSpecs(listOf(node)))
                        .entries.firstOrNull { it.value == name }?.key ?: name
                    app.simt.echo.ai.LocalCheckpoint(
                        nodeId = node.id,
                        keyframeId = keyframeId,
                        context = localContext(current, node),
                        heroJpeg = bytes,
                    )
                }
            if (checkpoints.isEmpty()) return false
            val result = router.proposeLocal(checkpoints)
            if (result.checkpoints.isEmpty()) {
                _ui.update { it.copy(error = "The local model found no landmarks. You can still add landmarks by hand.") }
                return false
            }
            publishSuggestions(result)
            true
        } catch (e: app.simt.echo.ai.LocalModelException) {
            _ui.update { it.copy(error = e.message ?: "Local model unavailable.") }
            false
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: "Could not load local suggestions.") }
            false
        }
    }

    /** Sensor-measured context line for one checkpoint (never inferred). */
    private fun localContext(route: EchoRoute, node: RouteNode): String {
        val what = when (node.type) {
            app.simt.echo.model.NodeType.TURN -> "TURN ${node.turn ?: ""}".trim()
            app.simt.echo.model.NodeType.LEVEL_CHANGE -> "LEVEL CHANGE"
            else -> node.type.name
        }
        return "Route: ${route.name}, ${route.totalLengthM.toInt()} m. Checkpoint: " +
            "$what at ${node.atDistanceM} m (sensor-measured)."
    }

    /** Publishes any provider's result: merge, preselect, load card images. */
    private fun publishSuggestions(parsed: AiJobResult) {
        val current = route ?: return
        val merged = CandidateMerger.merge(current, parsed)
        aiResult = parsed
        // Load each candidate's own keyframe bytes for the card:
        // the box belongs to that frame, not necessarily the hero.
        val images = mutableMapOf<String, ByteArray>()
        for ((nodeId, candidates) in merged) {
            val node = current.nodes.firstOrNull { it.id == nodeId } ?: continue
            for (candidate in candidates) {
                val name = candidateFileName(current, node, candidate) ?: continue
                drafts.readDraftFile(draftId, name)?.let { images[candidate.candidateId] = it }
            }
        }
        _ui.update {
            it.copy(
                suggestions = merged,
                suggestionIndex = merged.mapValues { 0 },
                suggestionImages = images,
                aiModels = CandidateMerger.modelsLine(parsed),
            )
        }
    }

    /** The draft file holding [candidate]'s own keyframe (its box's frame). */
    private fun candidateFileName(route: EchoRoute, node: RouteNode, candidate: AiCandidate): String? {
        val byId = keyframeFiles(keyframeSpecs(listOf(node)))
        return byId[candidate.keyframeId] ?: keyframeFileName(route, node)
    }

    /**
     * Use the preselected suggestion: crops its box out of the keyframe
     * the candidate was grounded on and attaches a creator-verified
     * landmark. The candidate's source, models and geometry travel onto
     * the landmark and the route gains AI provenance; nodes, legs and
     * path are copied untouched.
     */
    fun useSuggestion(nodeId: String) {
        viewModelScope.launch {
            try {
                val current = route ?: return@launch
                val node = current.nodes.firstOrNull { it.id == nodeId } ?: return@launch
                val list = _ui.value.suggestions[nodeId] ?: return@launch
                val index = _ui.value.suggestionIndex[nodeId] ?: 0
                val candidate = list.getOrNull(index) ?: return@launch
                val heroName = candidateFileName(current, node, candidate)
                    ?: throw IllegalStateException("No keyframe for this node yet.")
                val box = CandidateMerger.candidateCropBox(candidate)
                val landmarkId = newId()
                val cropName = "kf/ai_$landmarkId.jpg"
                media.cropJpeg(
                    drafts.draftFilePath(draftId, heroName),
                    box,
                    KEYFRAME_MAX_SIDE,
                    drafts.draftFilePath(draftId, cropName),
                )
                val landmark = Landmark(
                    id = landmarkId,
                    nodeId = nodeId,
                    title = candidate.title,
                    description = candidate.description,
                    cue = candidate.cue.ifEmpty { defaultCue(node).ifEmpty { candidate.title } },
                    samPhrase = candidate.samPhrase.ifEmpty { null },
                    signText = candidate.signText,
                    cropAssetId = cropName,
                    visibleFromM = candidate.visibleFromM,
                    trackability = candidate.trackability,
                    samConfidence = candidate.samConfidence,
                    source = CandidateMerger.landmarkSourceOf(candidate),
                    models = candidate.models.ifEmpty { null },
                    confirmation = Confirmation.CREATOR_VERIFIED,
                    // Geometry persists so Navigate can render the mask
                    // over the crop (W5-02); scalars above are not enough.
                    box = candidate.box.takeIf { it.size == 4 },
                    frameW = candidate.frameW,
                    frameH = candidate.frameH,
                    maskSvgPath = candidate.maskSvgPath,
                    maskW = candidate.maskW,
                    maskH = candidate.maskH,
                    keyframeId = candidate.keyframeId.ifEmpty { null },
                )
                val result = aiResult
                mutate { r ->
                    r.copy(
                        nodes = r.nodes.map {
                            if (it.id == nodeId) it.copy(landmarkIds = it.landmarkIds + landmarkId)
                            else it
                        },
                        landmarks = r.landmarks + landmark,
                        ai = r.ai ?: result?.let {
                            AiProvenance(
                                jobId = it.jobId,
                                scoutModel = it.models.scout,
                                groundModel = it.models.ground,
                                processedAtEpochMs = nowMs(),
                            )
                        },
                    )
                }
                // Consume the used option; the next-best preselects.
                _ui.update { state ->
                    val remaining = list.filterIndexed { i, _ -> i != index }
                    state.copy(
                        suggestions = if (remaining.isEmpty()) {
                            state.suggestions - nodeId
                        } else {
                            state.suggestions + (nodeId to remaining)
                        },
                        suggestionIndex = if (remaining.isEmpty()) {
                            state.suggestionIndex - nodeId
                        } else {
                            state.suggestionIndex + (nodeId to 0)
                        },
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Could not use the AI suggestion.") }
            }
        }
    }

    /** Next option: cycles the preselected suggestion for [nodeId]. */
    fun nextSuggestion(nodeId: String) {
        _ui.update { state ->
            val list = state.suggestions[nodeId] ?: return@update state
            if (list.size < 2) return@update state
            val next = ((state.suggestionIndex[nodeId] ?: 0) + 1) % list.size
            state.copy(suggestionIndex = state.suggestionIndex + (nodeId to next))
        }
    }

    /** Skip: dismisses every suggestion for [nodeId] without touching the route. */
    fun skipSuggestions(nodeId: String) {
        _ui.update {
            it.copy(
                suggestions = it.suggestions - nodeId,
                suggestionIndex = it.suggestionIndex - nodeId,
            )
        }
    }

    fun renameNode(nodeId: String, label: String) = mutate { route ->
        route.copy(nodes = route.nodes.map { node ->
            if (node.id != nodeId) node
            else {
                val renamed = node.copy(label = label)
                // Keep an untouched auto-cue in sync; a hand-edited cue stays.
                val cue = if (node.cue == defaultCue(node)) defaultCue(renamed) else node.cue
                renamed.copy(cue = cue)
            }
        })
    }

    fun editCue(nodeId: String, cue: String) = mutate { route ->
        route.copy(nodes = route.nodes.map { if (it.id == nodeId) it.copy(cue = cue) else it })
    }

    fun editStartDescription(description: String) = mutate { route ->
        route.copy(start = route.start.copy(description = description))
    }

    fun openDraw(nodeId: String) {
        _ui.update { it.copy(drawNodeId = nodeId, drawTitle = "", error = null) }
    }

    fun closeDraw() {
        _ui.update { it.copy(drawNodeId = null, drawTitle = "") }
    }

    fun editDrawTitle(title: String) {
        _ui.update { it.copy(drawTitle = title) }
    }

    /**
     * Draw-overlay confirm: crops [box] (0..1 of the hero) to a draft file
     * and attaches a creator-verified manual landmark to [nodeId].
     */
    fun confirmDraw(nodeId: String, box: CropBox) {
        val title = _ui.value.drawTitle.trim()
        if (title.isEmpty()) {
            _ui.update { it.copy(error = "Name the landmark first.") }
            return
        }
        viewModelScope.launch {
            try {
                val current = route ?: return@launch
                val node = current.nodes.firstOrNull { it.id == nodeId } ?: return@launch
                val heroName = keyframeFileName(current, node)
                    ?: throw IllegalStateException("No keyframe for this node yet.")
                val landmarkId = newId()
                val cropName = "kf/manual_$landmarkId.jpg"
                media.cropJpeg(
                    drafts.draftFilePath(draftId, heroName),
                    box,
                    KEYFRAME_MAX_SIDE,
                    drafts.draftFilePath(draftId, cropName),
                )
                val landmark = Landmark(
                    id = landmarkId,
                    nodeId = nodeId,
                    title = title,
                    description = "",
                    cue = defaultCue(node).ifEmpty { title },
                    cropAssetId = cropName,
                    source = LandmarkSource.MANUAL,
                    confirmation = Confirmation.CREATOR_VERIFIED,
                )
                mutate { r ->
                    r.copy(
                        nodes = r.nodes.map {
                            if (it.id == nodeId) it.copy(landmarkIds = it.landmarkIds + landmarkId)
                            else it
                        },
                        landmarks = r.landmarks + landmark,
                    )
                }
                closeDraw()
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Could not save the landmark.") }
            }
        }
    }

    fun editLandmark(landmarkId: String, title: String, cue: String) = mutate { route ->
        route.copy(landmarks = route.landmarks.map {
            if (it.id == landmarkId) it.copy(title = title, cue = cue) else it
        })
    }

    fun deleteLandmark(landmarkId: String) = mutate { route ->
        route.copy(
            nodes = route.nodes.map { it.copy(landmarkIds = it.landmarkIds - landmarkId) },
            landmarks = route.landmarks.filterNot { it.id == landmarkId },
        )
    }

    /**
     * Save ECHO: geometry (nodes, legs, path) is copied untouched, export
     * copies get `captureTMs = null`, start photo + manual crops land in
     * `routes/<draftId>/assets/`, then the consumed draft is deleted.
     */
    fun save(onSaved: (String) -> Unit) {
        if (_ui.value.saving || _ui.value.savedRouteId != null) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            try {
                val src = checkNotNull(route) { "No draft route for $draftId." }
                val wanted = buildList {
                    src.start.photoAssetId?.let { add(it) }
                    src.landmarks.mapNotNullTo(this) { it.cropAssetId }
                }.distinct()
                val assets = wanted
                    .filter { drafts.readDraftFile(draftId, it) != null }
                    .associateWith { drafts.draftFilePath(draftId, it) }
                val export = src.copy(
                    createdAtEpochMs = nowMs(),
                    start = src.start.copy(
                        photoAssetId = src.start.photoAssetId?.takeIf { it in assets },
                    ),
                    nodes = src.nodes.map { it.copy(captureTMs = null) },
                    landmarks = src.landmarks.map { landmark ->
                        if (landmark.cropAssetId != null && landmark.cropAssetId !in assets) {
                            landmark.copy(cropAssetId = null)
                        } else landmark
                    },
                )
                routes.save(export, assets)
                drafts.delete(draftId)
                _ui.update { it.copy(saving = false, savedRouteId = export.routeId) }
                onSaved(export.routeId)
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = e.message ?: "Could not save the route.") }
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    /** Applies [change] to the in-memory route, persists the draft, republishes. */
    private fun mutate(change: (EchoRoute) -> EchoRoute) {
        val current = route ?: return
        val next = change(current)
        if (next == current) return
        route = next
        drafts.saveDraftRoute(next)
        publish(next)
    }

    private fun publish(loaded: EchoRoute) {
        _ui.update {
            it.copy(
                loading = false,
                missing = false,
                routeName = loaded.name,
                startDescription = loaded.start.description,
                path = loaded.path,
                nodes = loaded.nodes.map { node ->
                    ReviewNode(
                        node = node,
                        heroBytes = keyframeFileName(loaded, node)
                            ?.let { drafts.readDraftFile(draftId, it) },
                        landmarks = loaded.landmarks.filter { landmark -> landmark.nodeId == node.id },
                    )
                },
            )
        }
    }
}
