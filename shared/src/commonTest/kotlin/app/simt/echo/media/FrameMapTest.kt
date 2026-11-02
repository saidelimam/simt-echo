package app.simt.echo.media

import app.simt.echo.capture.GlimpseEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W11-03 gate: frame map, exact `visibleFromM`, sidecars, forge selection, SHA-256. */
class FrameMapTest {

    private fun entry(tNs: Long, d: Double, path: String? = "glimpse/0.jpg"): GlimpseEntry =
        GlimpseEntry(
            bucketIndex = 0L,
            tNs = tNs,
            odometerM = d,
            score = 1.0,
            sharp = 1.0,
            hash = 0L,
            jpegPath = path,
        )

    private class FakeForge(override val supportsStripForge: Boolean) : MediaTools {
        var cutFps = -1

        override suspend fun extractFrame(video: okio.Path, atMs: Long, maxSide: Int, out: okio.Path): Nothing =
            error("not exercised")

        override suspend fun cropJpeg(input: okio.Path, box: CropBox, maxSide: Int, out: okio.Path): Nothing =
            error("not exercised")

        override suspend fun cutClip(
            video: okio.Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: okio.Path,
        ): ClipInfo {
            cutFps = maxFps
            return ClipInfo(out, frameCount = 0, durationMs = endMs - startMs)
        }
    }

    // ── buildFrameMap ────────────────────────────────────────────────────

    @Test
    fun frameMapPreservesStripOrder() {
        val strip = listOf(entry(3_000L, 18.5), entry(1_000L, 17.9), entry(2_000L, 18.2))
        val map = buildFrameMap(strip)
        assertEquals(
            listOf(ClipFrame(3_000L, 18.5), ClipFrame(1_000L, 17.9), ClipFrame(2_000L, 18.2)),
            map,
        )
    }

    @Test
    fun emptyStripMapsToEmpty() {
        assertEquals(emptyList(), buildFrameMap(emptyList()))
    }

    // ── sidecar round-trip ───────────────────────────────────────────────

    @Test
    fun frameMapRoundTripsThroughJson() {
        val map = listOf(ClipFrame(1_000L, 17.9), ClipFrame(2_000L, 18.2))
        val json = encodeFrameMap(map, clipSha256 = "abc123")
        val parsed = decodeFrameMap(json)
        assertEquals("image-strip", parsed.source)
        assertEquals(CLIP_FORGE_FPS, parsed.fps)
        assertEquals(map, parsed.frames)
        assertEquals("abc123", parsed.clipSha256)
    }

    @Test
    fun fallbackSidecarRoundTripsWithEmptyFrames() {
        val json = encodeFrameMap(emptyList(), source = "video-transcode")
        val parsed = decodeFrameMap(json)
        assertEquals("video-transcode", parsed.source)
        assertEquals(emptyList(), parsed.frames)
        assertNull(parsed.clipSha256)
    }

    @Test
    fun sidecarNamingDerivesFromClipId() {
        assertEquals("kf/clip_n1.frames.json", clipSidecarFileName("n1"))
        assertEquals("clip_n1_frames", clipSidecarId("n1"))
    }

    // ── visibleFromM ─────────────────────────────────────────────────────

    @Test
    fun visibleFromMIsCheckpointMinusFirstTrackedDistance() {
        // Checkpoint at 20 m; SAM first tracks the object in frame 2 at 18.2 m.
        val map = listOf(ClipFrame(1_000L, 17.9), ClipFrame(2_000L, 18.2), ClipFrame(3_000L, 18.5))
        assertEquals(1.8, visibleFromM(20.0, map, 1)!!, 1e-9)
    }

    @Test
    fun visibleFromMFirstFrameYieldsFullApproach() {
        val map = listOf(ClipFrame(1_000L, 12.0), ClipFrame(2_000L, 12.5))
        assertEquals(8.0, visibleFromM(20.0, map, 0)!!, 1e-9)
    }

    @Test
    fun visibleFromMEmptyMapIsNull() {
        assertNull(visibleFromM(20.0, emptyList(), 0))
    }

    @Test
    fun visibleFromMOutOfRangeIndexIsNull() {
        val map = listOf(ClipFrame(1_000L, 17.9))
        assertNull(visibleFromM(20.0, map, 1))
        assertNull(visibleFromM(20.0, map, -1))
    }

    @Test
    fun visibleFromMTrackedPastCheckpointStaysExact() {
        // The tracked frame sits 0.5 m past the checkpoint: exact, not
        // clamped, so callers see what SAM saw.
        val map = listOf(ClipFrame(1_000L, 20.5))
        assertEquals(-0.5, visibleFromM(20.0, map, 0)!!, 1e-9)
    }

    // ── stripFallbackWindow ──────────────────────────────────────────────

    @Test
    fun fallbackWindowSpansTwelveFramesAtFourFps() {
        // 12 frames × 250 ms = 3000 ms: [t − 2250, t + 750].
        val window = stripFallbackWindow(40_000L)
        assertEquals(ClipWindow(37_750L, 40_750L), window)
    }

    @Test
    fun fallbackWindowClampsEndToDuration() {
        assertEquals(ClipWindow(37_750L, 37_750L), stripFallbackWindow(40_000L, 30_000L))
    }

    @Test
    fun fallbackWindowClampsStartToZero() {
        assertEquals(ClipWindow(0L, 1_750L), stripFallbackWindow(1_000L))
    }

    @Test
    fun fallbackWindowIgnoresNonPositiveDuration() {
        assertEquals(ClipWindow(37_750L, 40_750L), stripFallbackWindow(40_000L, 0L))
    }

    // ── selectForgeSource (with fakes) ───────────────────────────────────

    @Test
    fun stripForgeNeedsSupportAndFrames() {
        val supported = FakeForge(supportsStripForge = true)
        val unsupported = FakeForge(supportsStripForge = false)
        assertEquals(
            ForgeSource.IMAGE_STRIP,
            selectForgeSource(12, supported.supportsStripForge),
        )
        assertEquals(
            ForgeSource.IMAGE_STRIP,
            selectForgeSource(1, supported.supportsStripForge),
        )
        assertEquals(
            ForgeSource.VIDEO_TRANSCODE,
            selectForgeSource(0, supported.supportsStripForge),
        )
        assertEquals(
            ForgeSource.VIDEO_TRANSCODE,
            selectForgeSource(12, unsupported.supportsStripForge),
        )
        assertEquals(
            ForgeSource.VIDEO_TRANSCODE,
            selectForgeSource(0, unsupported.supportsStripForge),
        )
    }

    @Test
    fun stripForgeUnsupportedByDefault() {
        assertEquals(false, FakeForge(false).supportsStripForge)
        assertEquals(
            ForgeSource.VIDEO_TRANSCODE,
            selectForgeSource(12, FakeForge(false).supportsStripForge),
        )
    }

    // ── ledgerJpegName ───────────────────────────────────────────────────

    @Test
    fun ledgerJpegNameKeepsDraftRelativePaths() {
        assertEquals("glimpse/3.jpg", ledgerJpegName("d1", entry(0L, 0.0, "glimpse/3.jpg")))
    }

    @Test
    fun ledgerJpegNameStripsStoreAbsolutePrefix() {
        assertEquals(
            "glimpse/3.jpg",
            ledgerJpegName("d1", entry(0L, 0.0, "drafts/d1/glimpse/3.jpg")),
        )
    }

    @Test
    fun ledgerJpegNameNullWithoutFile() {
        assertNull(ledgerJpegName("d1", entry(0L, 0.0, null)))
    }

    // ── sha256Hex ────────────────────────────────────────────────────────

    @Test
    fun sha256MatchesNistVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun sha256IsLowercaseHexOfFullLength() {
        val hex = sha256Hex("clip".encodeToByteArray())
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' }, hex)
    }

    @Test
    fun forgeConstantsMeetTheBudgetShape() {
        // 12 frames at 4 fps = 3 s per clip; ~600 kbps × 3 s ≈ 225 KB, so a
        // 6–8 checkpoint route stays under the 2 MB upload budget.
        assertEquals(12, CLIP_FORGE_MAX_FRAMES)
        assertEquals(4, CLIP_FORGE_FPS)
        assertEquals(3_000L, CLIP_FORGE_MAX_FRAMES * CLIP_FORGE_FRAME_INTERVAL_MS)
        assertEquals(480, CLIP_FORGE_SHORT_SIDE_PX)
        assertEquals(600_000, CLIP_FORGE_VIDEO_BITRATE_BPS)
    }
}
