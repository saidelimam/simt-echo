package app.simt.echo.media

import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.capture.approachStrip
import app.simt.echo.capture.ledgerKeyframes
import app.simt.echo.capture.startPhotoEntry
import app.simt.echo.data.DraftRepository
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode

/** Keyframes and manual crops are extracted at most this wide (plan W1-05). */
const val KEYFRAME_MAX_SIDE = 1280

/**
 * File inside the draft dir holding [node]'s hero keyframe, or null when
 * the node has none yet. START nodes use the start photo; other nodes use
 * the W1-05 keyframe plan (`kf/<nodeId>_<i>.jpg`).
 */
fun keyframeFileName(route: EchoRoute, node: RouteNode): String? {
    if (node.type == NodeType.START) return CaptureSession.START_PHOTO
    val spec = keyframeSpecs(listOf(node)).firstOrNull() ?: return null
    if (spec.timesMs.isEmpty()) return null
    return spec.fileName(spec.heroIndex)
}

/**
 * Extracts any keyframe file missing from the draft (idempotent). Skips
 * extraction when the draft has no video — Review still edits, uploads
 * just carry fewer files. Existence checks are metadata-only, never whole
 * file reads (W10-02 G3); missing frames go out as one [MediaTools]
 * batch, so the Android actual opens a single retriever (W10-02 G1).
 *
 * Ledger first (plan W11-02): when the draft holds persisted ledger
 * entries (`glimpse/entries.json`), each missing `kf/` slot is filled by
 * copying the distance-picked ledger frame instead of seeking the video —
 * same file names, same hero indices, so Review, the manifest and upload
 * work unchanged. Slots the ledger cannot fill (empty approach window,
 * metadata-only pick, unreadable JPEG) fall back to video extraction;
 * drafts with no ledger behave exactly as before. The ledger start photo
 * fills a missing `start.jpg` only and never overwrites the creator's.
 */
suspend fun ensureKeyframes(
    drafts: DraftRepository,
    media: MediaTools,
    draftId: String,
    route: EchoRoute,
    maxSide: Int = KEYFRAME_MAX_SIDE,
) {
    val ledger = drafts.loadGlimpseEntries(draftId) ?: emptyList()
    fillLedgerStartPhoto(drafts, draftId, ledger)
    val videoMissing = !drafts.draftFileExists(draftId, CaptureSession.VIDEO_FILE)
    val video = drafts.draftFilePath(draftId, CaptureSession.VIDEO_FILE)
    val missing = buildList {
        for (node in route.nodes) {
            if (node.type == NodeType.START) continue
            val spec = keyframeSpecs(listOf(node)).firstOrNull() ?: continue
            val picks = ledgerPicksFor(ledger, node)
            spec.timesMs.forEachIndexed { index, atMs ->
                val name = spec.fileName(index)
                if (drafts.draftFileExists(draftId, name)) return@forEachIndexed
                val bytes = picks.getOrNull(index)?.let { drafts.readLedgerJpeg(draftId, it) }
                if (bytes != null) {
                    drafts.writeDraftFile(draftId, name, bytes)
                } else if (!videoMissing) {
                    add(FrameRequest(atMs, maxSide, drafts.draftFilePath(draftId, name)))
                }
                // No ledger frame and no video: the slot stays missing;
                // Review still edits, uploads just carry fewer files.
            }
        }
    }
    if (missing.isNotEmpty()) media.extractFrames(video, missing)
}

/**
 * The ledger frames that fill [node]'s keyframe slots, in slot order: the
 * distance-picked [ledgerKeyframes] of its approach strip (plan W11-02).
 * Shared by `ensureKeyframes` (which copies them into `kf/`) and the W11-04
 * flags (which scan them), so both always address the same frames.
 */
internal fun ledgerPicksFor(ledger: List<GlimpseEntry>, node: RouteNode): List<GlimpseEntry> =
    ledgerKeyframes(approachStrip(ledger, node.atDistanceM), node.type, node.atDistanceM)

/** Flags that withhold a keyframe: its bytes are not a scanned ledger frame. */
private val UNVERIFIED_FRAME = FrameFlags(faceFree = false)

/**
 * Fail-closed W11-04 flags for every planned keyframe. A keyframe is
 * flag-able by the scan only when its `kf/` file is byte-identical to the
 * ledger pick the [detector] verdict was computed on. Anything else — a
 * slot filled from video, a recapture, an absent or corrupt ledger — has
 * no scan behind it and is flagged `faceFree = false`, so its bytes never
 * upload and the manifest drops it. Call only with a detector configured;
 * without one, keyframes keep today's unflagged behavior.
 */
fun failClosedFrameFlags(
    drafts: DraftRepository,
    draftId: String,
    route: EchoRoute,
    specs: List<KeyframeSpec>,
    ledger: List<GlimpseEntry>,
    detector: FaceDetector,
): Map<String, FrameFlags> {
    val scanned = frameFlagsFor(route, specs, ledger, detector)
    val nodes = route.nodes.associateBy { it.id }
    return buildMap {
        for (spec in specs) {
            val node = nodes[spec.nodeId]?.takeIf { it.type != NodeType.START }
            val picks = if (node == null) emptyList() else ledgerPicksFor(ledger, node)
            spec.timesMs.indices.forEach { index ->
                val id = keyframeId(spec, index)
                val pickBytes = picks.getOrNull(index)?.let { drafts.readLedgerJpeg(draftId, it) }
                val fileBytes = drafts.readDraftFile(draftId, spec.fileName(index))
                val verified = pickBytes != null && fileBytes != null && pickBytes.contentEquals(fileBytes)
                put(id, if (verified) scanned[id] ?: UNVERIFIED_FRAME else UNVERIFIED_FRAME)
            }
        }
    }
}

/**
 * Copies the ledger start photo over a missing `start.jpg`. Never
 * overwrites: the camera capture stays authoritative when present.
 */
private fun fillLedgerStartPhoto(
    drafts: DraftRepository,
    draftId: String,
    ledger: List<GlimpseEntry>,
) {
    if (drafts.draftFileExists(draftId, CaptureSession.START_PHOTO)) return
    val bytes = startPhotoEntry(ledger)?.let { drafts.readLedgerJpeg(draftId, it) } ?: return
    drafts.writeDraftFile(draftId, CaptureSession.START_PHOTO, bytes)
}

/**
 * JPEG bytes for a ledger [entry]: [GlimpseEntry.jpegPath] is
 * draft-relative (`glimpse/<bucket>.jpg`). Store-absolute paths of the
 * form `drafts/<draftId>/…` (as W11-01's test store emits) are tolerated
 * by stripping the prefix; anything unreadable yields null and the
 * caller falls back to video.
 */
internal fun DraftRepository.readLedgerJpeg(draftId: String, entry: GlimpseEntry): ByteArray? {
    val path = entry.jpegPath ?: return null
    readDraftFile(draftId, path)?.let { return it }
    val prefix = "drafts/$draftId/"
    if (path.startsWith(prefix)) return readDraftFile(draftId, path.removePrefix(prefix))
    return null
}
