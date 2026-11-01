package app.simt.echo.media

import app.simt.echo.capture.CaptureSession
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
 * just carry fewer files.
 */
suspend fun ensureKeyframes(
    drafts: DraftRepository,
    media: MediaTools,
    draftId: String,
    route: EchoRoute,
    maxSide: Int = KEYFRAME_MAX_SIDE,
) {
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
                maxSide,
                drafts.draftFilePath(draftId, name),
            )
        }
    }
}
