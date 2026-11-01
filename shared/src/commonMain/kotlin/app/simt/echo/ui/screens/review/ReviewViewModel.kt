package app.simt.echo.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.simt.echo.capture.CaptureSession
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.RouteRepository
import app.simt.echo.media.CropBox
import app.simt.echo.media.MediaTools
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.model.Confirmation
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.Landmark
import app.simt.echo.model.LandmarkSource
import app.simt.echo.model.defaultCue
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteNode
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
    val saving: Boolean = false,
    val savedRouteId: String? = null,
    val error: String? = null,
)

/** Keyframes are extracted at most this wide (plan W1-05: ≤ 1280 px). */
const val REVIEW_MAX_SIDE = 1280

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
 */
class ReviewViewModel(
    private val draftId: String,
    private val drafts: DraftRepository,
    private val routes: RouteRepository,
    private val media: MediaTools,
    private val nowMs: () -> Long,
    private val newId: () -> String,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReviewUiState())
    val ui: StateFlow<ReviewUiState> = _ui.asStateFlow()

    private var route: EchoRoute? = null

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
                ensureKeyframes(loaded)
                route = loaded
                publish(loaded)
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message) }
            }
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
                val heroName = heroFileName(current, node)
                    ?: throw IllegalStateException("No keyframe for this node yet.")
                val landmarkId = newId()
                val cropName = "kf/manual_$landmarkId.jpg"
                media.cropJpeg(
                    drafts.draftFilePath(draftId, heroName),
                    box,
                    REVIEW_MAX_SIDE,
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
                        heroBytes = heroFileName(loaded, node)
                            ?.let { drafts.readDraftFile(draftId, it) },
                        landmarks = loaded.landmarks.filter { landmark -> landmark.nodeId == node.id },
                    )
                },
            )
        }
    }

    /** File inside the draft dir holding [node]'s hero, or null when none yet. */
    private fun heroFileName(route: EchoRoute, node: RouteNode): String? {
        if (node.type == NodeType.START) return CaptureSession.START_PHOTO
        val spec = keyframeSpecs(listOf(node)).firstOrNull() ?: return null
        if (spec.timesMs.isEmpty()) return null
        return spec.fileName(spec.heroIndex)
    }

    /** Extracts any keyframe file missing from the draft (idempotent). */
    private suspend fun ensureKeyframes(route: EchoRoute) {
        val videoMissing = drafts.readDraftFile(draftId, CaptureSession.VIDEO_FILE) == null
        for (node in route.nodes) {
            if (node.type == NodeType.START) continue
            val spec = keyframeSpecs(listOf(node)).firstOrNull() ?: continue
            spec.timesMs.forEachIndexed { index, atMs ->
                val name = spec.fileName(index)
                if (drafts.readDraftFile(draftId, name) != null) return@forEachIndexed
                if (videoMissing) return@forEachIndexed
                media.extractFrame(
                    drafts.draftFilePath(draftId, CaptureSession.VIDEO_FILE),
                    atMs,
                    REVIEW_MAX_SIDE,
                    drafts.draftFilePath(draftId, name),
                )
            }
        }
    }
}
