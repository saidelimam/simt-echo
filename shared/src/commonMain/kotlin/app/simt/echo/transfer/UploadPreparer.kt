package app.simt.echo.transfer

import app.simt.echo.capture.CaptureSession
import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.GlimpseEntry
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
import app.simt.echo.media.failClosedFrameFlags
import app.simt.echo.media.keyframeFiles
import app.simt.echo.media.keyframeSpecs
import app.simt.echo.media.ledgerJpegName
import app.simt.echo.media.selectForgeSource
import app.simt.echo.media.sha256Hex
import app.simt.echo.media.stripClipWindow
import app.simt.echo.media.stripFallbackWindow
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** A manifest and exactly the files it lists, shared by every send path. */
data class PreparedUpload(
    val route: EchoRoute,
    val manifestJson: String,
    val files: List<UploadFile>,
)

/**
 * Turns a route into the bytes that upload: keyframe slots, forged clips,
 * the W11-04 face flags and the manifest that lists them, with every file
 * the manifest names read back from the draft. One class for both senders
 * of those bytes: the Processing screen after Finish, and the walk-time
 * uploader (plan W11-09) for checkpoints that closed mid-walk, so the two
 * can never disagree about which frames a node ships or what they are called.
 *
 * Callers fill the `kf/` slots first (`ensureKeyframes`, or the ledger-only
 * fill while the video is still being written). Forged clips are cached per
 * forge path for the life of the instance.
 */
class UploadPreparer(
    private val drafts: DraftRepository,
    private val media: MediaTools,
    private val draftId: String,
    private val faceDetector: FaceDetector?,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long,
) {
    /** Cut clips keyed by forge path: `clip:<node>` + window or `strip:<node>` + strip span (W11-03). */
    private val clipCache = mutableMapOf<String, ClipInfo>()

    /**
     * The upload for [route]'s checkpoints. [ledger] is the glimpse ledger the
     * strips are cut from: the persisted one after Finish, a live snapshot
     * during the walk. [useVideo] is false while `video.mp4` is still being
     * written, so only ledger strips can become clips. The files may be empty
     * (no keyframe survived); whether that is an error is the caller's call.
     */
    suspend fun prepare(
        route: EchoRoute,
        log: CaptureLog,
        samEnabled: Boolean,
        ledger: List<GlimpseEntry> = drafts.loadGlimpseEntries(draftId).orEmpty(),
        useVideo: Boolean = true,
    ): PreparedUpload = withContext(ioDispatcher) {
        val specs = keyframeSpecs(route.nodes)
        val clips = cutClips(route, log.videoStartNs, samEnabled, ledger, useVideo)
        // Fail closed (W11-04): with a detector, a keyframe whose bytes are
        // not a scanned ledger frame (absent/corrupt ledger, video-filled or
        // recaptured slot) is flagged and withheld rather than uploaded blind.
        val frameFlags = faceDetector?.let { detector ->
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
        PreparedUpload(route, EchoJson.encodeToString(manifest), collectFiles(manifest, specs))
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
    private suspend fun cutClips(
        route: EchoRoute,
        videoStartNs: Long,
        samEnabled: Boolean,
        ledger: List<GlimpseEntry>,
        useVideo: Boolean,
    ): Map<String, ForgedClip> {
        if (!samEnabled) return emptyMap()
        val videoFile = CaptureSession.VIDEO_FILE
        val video = drafts.draftFilePath(draftId, videoFile)
        val hasVideo = useVideo && drafts.draftFileExists(draftId, videoFile)
        if (!hasVideo && !media.supportsStripForge) return emptyMap()
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
