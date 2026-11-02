package app.simt.echo.media

import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.model.EchoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Clip forge (plan W11-03): approach-strip JPEGs → H.264 MP4.
 *
 * Every forged clip is [CLIP_FORGE_MAX_FRAMES] frames or fewer at
 * [CLIP_FORGE_FPS] fps, 480 px on the short side, ~600 kbps — a 60 m route
 * (6–8 checkpoints) then uploads ≤ 2 MB total (device evidence owed: no
 * device in this slice; see the W11-03 report).
 *
 * Each clip carries a sidecar `kf/clip_<nodeId>.frames.json` ([ClipFrameMap])
 * mapping output frame index → (tNs, odometer m). That map makes
 * [visibleFromM] exact — d_checkpoint − d(first frame where SAM tracks the
 * object) — replacing the old fps estimate. The manifest shape is frozen
 * (W10-06), so the sidecar is a separate file ([clipSidecarId]). It stays
 * on the phone until the worker consumes it (W11-10): the worker's upload
 * allow-list drops it today, so uploading it would only spend bytes.
 *
 * Pure and deterministic: no coroutines (except where noted), no platform
 * imports. The platform encode itself lives behind [MediaTools.forgeStripClip]
 * / [MediaTools.transcodeStripClip]; this file is the maths both paths share.
 */

/** Output frame rate of forged clips, frames per second. */
const val CLIP_FORGE_FPS = 4

/** At most this many frames per forged clip (plan W11-03). */
const val CLIP_FORGE_MAX_FRAMES = 12

/** Short side of forged clips, px (480p). */
const val CLIP_FORGE_SHORT_SIDE_PX = 480

/** Target video bitrate of forged clips, bits per second (~600 kbps). */
const val CLIP_FORGE_VIDEO_BITRATE_BPS = 600_000

/** One output frame's duration: 250 ms at [CLIP_FORGE_FPS]. */
const val CLIP_FORGE_FRAME_INTERVAL_MS = 250L

/** Fallback window reach behind the checkpoint: 2250 ms (9 frames). */
const val STRIP_WINDOW_BEFORE_MS = 2_250L

/** Fallback window reach past the checkpoint: 750 ms (3 frames). */
const val STRIP_WINDOW_AFTER_MS = 750L

/**
 * One forged output frame: the capture timestamp and odometer distance of
 * the source frame. Index in [ClipFrameMap.frames] is the clip frame index.
 */
@Serializable
data class ClipFrame(
    val tNs: Long,
    val odometerM: Double,
)

/**
 * Sidecar body (`kf/clip_<nodeId>.frames.json`).
 *
 * [source] is `"image-strip"` for the primary path (exact per-frame ledger
 * distances) or `"video-transcode"` for the fallback (frames unknown — the
 * list is empty and honest about it). [clipSha256] is the lowercase hex
 * SHA-256 of the clip bytes, so every artefact has a hash without touching
 * the frozen manifest shape. Defaults keep old sidecars readable.
 */
@Serializable
data class ClipFrameMap(
    val source: String = "image-strip",
    val fps: Int = CLIP_FORGE_FPS,
    val frames: List<ClipFrame> = emptyList(),
    val clipSha256: String? = null,
)

/**
 * Builds the frame map for a time-ordered approach strip: output frame `i`
 * is strip entry `i`. The forge preserves order, so the map is exact.
 */
fun buildFrameMap(strip: List<GlimpseEntry>): List<ClipFrame> =
    strip.map { ClipFrame(tNs = it.tNs, odometerM = it.odometerM) }

/** Serializes a frame map sidecar with [EchoJson] (the single JSON config). */
fun encodeFrameMap(
    map: List<ClipFrame>,
    source: String = "image-strip",
    fps: Int = CLIP_FORGE_FPS,
    clipSha256: String? = null,
): String = EchoJson.encodeToString(ClipFrameMap(source, fps, map, clipSha256))

/** Parses a sidecar written by [encodeFrameMap]. */
fun decodeFrameMap(json: String): ClipFrameMap = EchoJson.decodeFromString(json)

/**
 * Exact visibility distance (plan W11-03): d_checkpoint − d(first frame
 * where SAM tracks the object). Null when the map is empty or
 * [firstTrackedIndex] names no frame (negative or past the end). A tracked
 * frame past the checkpoint yields a negative distance — exact, not
 * clamped, so callers see what SAM saw.
 */
fun visibleFromM(
    checkpointDM: Double,
    map: List<ClipFrame>,
    firstTrackedIndex: Int,
): Double? = map.getOrNull(firstTrackedIndex)?.let { checkpointDM - it.odometerM }

/** Draft-relative sidecar file holding one node's frame map. */
fun clipSidecarFileName(nodeId: String): String = "kf/${clipId(nodeId)}.frames.json"

/** Upload field id for one node's sidecar (derived from the clip id). */
fun clipSidecarId(nodeId: String): String = "${clipId(nodeId)}_frames"

/**
 * A forged clip plus the video-relative [window] it actually covers. The
 * manifest describes this window, never an assumed one: the two forge paths
 * cover different spans (see [stripFallbackWindow], [stripClipWindow]).
 */
data class ForgedClip(
    val info: ClipInfo,
    val window: ClipWindow,
)

/**
 * The video-relative span an image-strip clip covers: first to last strip
 * frame, capture ns rebased on [videoStartNs]. [strip] is time-ordered and
 * non-empty; the end never precedes the start.
 */
fun stripClipWindow(strip: List<GlimpseEntry>, videoStartNs: Long): ClipWindow {
    val start = ((strip.first().tNs - videoStartNs) / 1_000_000L).coerceAtLeast(0L)
    val end = ((strip.last().tNs - videoStartNs) / 1_000_000L).coerceAtLeast(start)
    return ClipWindow(start, end)
}

/** Which forge path produces one node's clip. */
enum class ForgeSource {
    /** Strip JPEGs → MP4 via the image-sequence forge. */
    IMAGE_STRIP,

    /** Cut from video when no strip frame is forgeable. */
    VIDEO_TRANSCODE,
}

/**
 * Picks the forge path: the image strip whenever the platform supports it
 * and the strip holds at least one frame, else the video transcode.
 * Thin strips still forge (fewer than 12 frames) — only an empty strip, or
 * a platform without the image forge, falls back.
 */
fun selectForgeSource(stripFrameCount: Int, imageStripSupported: Boolean): ForgeSource =
    if (imageStripSupported && stripFrameCount > 0) {
        ForgeSource.IMAGE_STRIP
    } else {
        ForgeSource.VIDEO_TRANSCODE
    }

/**
 * Fallback clip window for [MediaTools.transcodeStripClip]: 12 frames at
 * [CLIP_FORGE_FPS] = 3000 ms, `[t − 2250, t + 750]`, clamped to
 * `[0, durationMs]` when a positive [durationMs] is known and never
 * inverted — the same clamp discipline as [clipWindow].
 */
fun stripFallbackWindow(tMs: Long, durationMs: Long? = null): ClipWindow {
    val start = (tMs - STRIP_WINDOW_BEFORE_MS).coerceAtLeast(0L)
    var end = tMs + STRIP_WINDOW_AFTER_MS
    if (durationMs != null && durationMs > 0L) end = end.coerceAtMost(durationMs)
    if (end < start) end = start
    return ClipWindow(start, end)
}

/** Raised for a clip window with nothing to cut (`end <= start`). */
class EmptyClipWindowException(val startMs: Long, val endMs: Long) :
    IllegalArgumentException("Empty clip window [$startMs, $endMs] ms")

/**
 * The window a platform clip export honours. Callers clamp the window to
 * the video duration ([clipWindow], [stripFallbackWindow]), so exporters
 * neither probe the duration again nor second-guess the end; they only
 * make the start non-negative and refuse a window with nothing in it, as
 * a typed failure the caller can omit the clip on.
 *
 * @throws EmptyClipWindowException when `endMs <= startMs` after clamping.
 */
fun checkedClipWindow(startMs: Long, endMs: Long): ClipWindow {
    val start = startMs.coerceAtLeast(0L)
    if (endMs <= start) throw EmptyClipWindowException(start, endMs)
    return ClipWindow(start, endMs)
}

/**
 * Draft-relative JPEG name for a ledger [entry], tolerating the
 * store-absolute `drafts/<draftId>/…` form the same way keyframe filling
 * does (see `readLedgerJpeg`). Null when the entry carries no file.
 */
internal fun ledgerJpegName(draftId: String, entry: GlimpseEntry): String? {
    val path = entry.jpegPath ?: return null
    val prefix = "drafts/$draftId/"
    return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
}

// ── SHA-256 (pure Kotlin; every forged artefact gets one) ──────────────────

private val SHA256_K = intArrayOf(
    0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
    0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
    0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
    0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
    0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
    0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
    0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
    0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)

private fun Int.rotr(n: Int): Int = (this ushr n) or (this shl (32 - n))

/**
 * SHA-256 of [bytes] as lowercase hex. Pure Kotlin (no provider setup) so
 * host tests and every target share it; verified against the NIST vectors
 * in `FrameMapTest`. Used for forged clips and their sidecars (W11-03:
 * every artefact gets a SHA-256).
 */
fun sha256Hex(bytes: ByteArray): String {
    var h0 = 0x6a09e667.toInt()
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372.toInt()
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f.toInt()
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab.toInt()
    var h7 = 0x5be0cd19.toInt()

    val bitLen = bytes.size.toLong() * 8L
    val paddedLen = (((bytes.size + 8) / 64) + 1) * 64
    val msg = ByteArray(paddedLen)
    bytes.copyInto(msg)
    msg[bytes.size] = 0x80.toByte()
    for (i in 0 until 8) {
        msg[paddedLen - 1 - i] = (bitLen ushr (i * 8)).toByte()
    }

    val w = IntArray(64)
    var off = 0
    while (off < paddedLen) {
        for (i in 0 until 16) {
            val j = off + i * 4
            w[i] = ((msg[j].toInt() and 0xff) shl 24) or
                ((msg[j + 1].toInt() and 0xff) shl 16) or
                ((msg[j + 2].toInt() and 0xff) shl 8) or
                (msg[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotr(7) xor w[i - 15].rotr(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotr(17) xor w[i - 2].rotr(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7
        for (i in 0 until 64) {
            val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + SHA256_K[i] + w[i]
            val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
        off += 64
    }

    val out = CharArray(64)
    val words = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
    val hex = "0123456789abcdef"
    for (i in words.indices) {
        for (j in 0 until 8) {
            // Unsigned shift: Int wraps mod 2³² throughout, which is exactly
            // SHA-256's word arithmetic; ushr keeps the nibble extraction
            // logical rather than arithmetic.
            out[i * 8 + j] = hex[(words[i] ushr ((7 - j) * 4)) and 0xf]
        }
    }
    return out.concatToString()
}
