package app.simt.echo.media

import app.simt.echo.Fixtures
import app.simt.echo.capture.FaceDetector
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoJson
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import app.simt.echo.model.TurnDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import okio.Path.Companion.toPath

/** Plan W11-04 gate: manifest per-frame quality/faceFree flags with a faked detector. */
class ManifestFrameFlagsTest {

    /** Fake detector at the seam: verdicts keyed by frame time, default UNKNOWN. */
    private class FakeFaceDetector(
        private val verdicts: Map<Long, FaceVerdict> = emptyMap(),
    ) : FaceDetector {
        override fun verdictFor(entry: GlimpseEntry): FaceVerdict =
            verdicts[entry.tNs] ?: FaceVerdict.UNKNOWN
    }

    private fun entry(
        d: Double,
        score: Double = 1.0,
        tNs: Long = (d * 1_000_000_000L).toLong(),
    ): GlimpseEntry = GlimpseEntry(
        bucketIndex = (d * 2).toLong(),
        tNs = tNs,
        odometerM = d,
        score = score,
        sharp = score,
        hash = d.toLong(),
        jpegPath = "glimpse/b${(d * 2).toLong()}.jpg",
    )

    /** TURN n1 @ 20 m (capture t = 40 s), DESTINATION n3 @ 45 m (t = 70 s). */
    private fun flaggedRoute(): EchoRoute = Fixtures.L_ROUTE.copy(
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Entrance"),
            RouteNode(
                id = "n1",
                type = NodeType.TURN,
                atDistanceM = 20.0,
                label = "Turn right",
                turn = TurnDir.RIGHT,
                captureTMs = 40_000L,
            ),
            RouteNode(
                id = "n3",
                type = NodeType.DESTINATION,
                atDistanceM = 45.0,
                label = "Desk",
                captureTMs = 70_000L,
            ),
        ),
    )

    private fun testLog(): CaptureLog = CaptureLog(
        draftId = "draft-1",
        startedAtEpochMs = 1_762_000_000_000L,
        motionSource = "echo_builtin_pdr",
        videoStartNs = 0L,
        samples = (0..75).map { CaptureSample(it.toLong() * 1_000_000_000L, it.toDouble(), 0.0) },
        marks = emptyList(),
        startPhoto = "start.jpg",
    )

    // ── build defaults (no scan → keep) ────────────────────────────────

    @Test
    fun buildWithoutFlagsKeepsEveryKeyframe() {
        val route = flaggedRoute()
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = emptyMap(),
            consent = ManifestConsent(true, 0L),
        )
        for (cp in manifest.checkpoints) {
            for (kf in cp.keyframes) {
                assertTrue(kf.faceFree, "${kf.id} unflagged must keep faceFree")
                assertEquals(FRAME_QUALITY_GOOD, kf.quality)
                assertTrue(kf.exposureOk)
            }
        }
    }

    @Test
    fun buildAppliesFlagsByKeyframeId() {
        val route = flaggedRoute()
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = emptyMap(),
            consent = ManifestConsent(true, 0L),
            frameFlags = mapOf(
                "kf_n1_1" to FrameFlags(quality = FRAME_QUALITY_BAD, faceFree = true, exposureOk = false),
            ),
        )
        val turn = manifest.checkpoints.single { it.nodeId == "n1" }
        val flagged = turn.keyframes.single { it.id == "kf_n1_1" }
        assertTrue(flagged.faceFree)
        assertEquals(FRAME_QUALITY_BAD, flagged.quality)
        assertFalse(flagged.exposureOk)
        // Siblings untouched.
        assertTrue(turn.keyframes.single { it.id == "kf_n1_0" }.faceFree)
        assertTrue(turn.keyframes.single { it.id == "kf_n1_2" }.faceFree)
    }

    @Test
    fun withheldKeyframesAreDroppedAndTheHeroMovesToTheNearestKeptSlot() {
        val route = flaggedRoute()
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = emptyMap(),
            consent = ManifestConsent(true, 0L),
            frameFlags = mapOf("kf_n1_1" to FrameFlags(faceFree = false)),
        )
        val turn = manifest.checkpoints.single { it.nodeId == "n1" }
        // The withheld hero is not promised to the worker (it was never uploaded).
        assertEquals(listOf("kf_n1_0", "kf_n1_2"), turn.keyframes.map { it.id })
        assertEquals("kf_n1_0", turn.heroKeyframeId)
        // An unflagged sibling node is untouched.
        assertEquals("kf_n3_1", manifest.checkpoints.single { it.nodeId == "n3" }.heroKeyframeId)
    }

    @Test
    fun aCheckpointWithEveryKeyframeWithheldIsDroppedWithItsClip() {
        val route = flaggedRoute()
        val specs = keyframeSpecs(route.nodes)
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = specs,
            clips = mapOf(
                "n3" to ForgedClip(ClipInfo("/clip.mp4".toPath(), frameCount = 12, durationMs = 3_000L), ClipWindow(0L, 3_000L)),
            ),
            consent = ManifestConsent(true, 0L),
            frameFlags = mapOf(
                "kf_n3_0" to FrameFlags(faceFree = false),
                "kf_n3_1" to FrameFlags(faceFree = false),
            ),
        )
        assertEquals(listOf("n1"), manifest.checkpoints.map { it.nodeId })
        assertTrue(manifest.checkpoints.all { it.clip == null })
    }

    @Test
    fun flagsSerializeIntoTheManifestJson() {
        val route = flaggedRoute()
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = emptyMap(),
            consent = ManifestConsent(true, 0L),
            frameFlags = mapOf("kf_n1_0" to FrameFlags(quality = FRAME_QUALITY_BAD, exposureOk = false)),
        )
        val json = EchoJson.encodeToString(Manifest.serializer(), manifest)
        assertTrue(json.contains("\"exposureOk\":false"), "worker reads exposureOk === false")
        assertTrue(json.contains("\"quality\":\"${FRAME_QUALITY_BAD}\""))
        assertTrue(json.contains("\"quality\":\"${FRAME_QUALITY_GOOD}\""))
    }

    // ── frameFlagsFor ──────────────────────────────────────────────────

    @Test
    fun frameFlagsForMapsPicksToKeyframeIds() {
        // Window [12, 21]: TURN picks target 16, 19, 20.75 → entries 16, 19, 21.
        val ledger = (12..21).map { it.toDouble() }.map { entry(it) }
        val byT = ledger.associateBy { it.tNs }
        val detector = FakeFaceDetector(
            mapOf(
                byT[(16.0 * 1_000_000_000L).toLong()]!!.tNs to FaceVerdict.FACE_FREE,
                byT[(19.0 * 1_000_000_000L).toLong()]!!.tNs to FaceVerdict.HAS_FACE,
                // 21 unmapped → UNKNOWN → exclude-safe.
            ),
        )
        val route = flaggedRoute()
        val flags = frameFlagsFor(route, keyframeSpecs(route.nodes), ledger, detector)
        assertEquals(FrameFlags(FRAME_QUALITY_GOOD, faceFree = true, exposureOk = true), flags["kf_n1_0"])
        assertEquals(FrameFlags(FRAME_QUALITY_GOOD, faceFree = false, exposureOk = true), flags["kf_n1_1"])
        assertEquals(FrameFlags(FRAME_QUALITY_GOOD, faceFree = false, exposureOk = true), flags["kf_n1_2"])
        // n3's window [37, 46] holds no ledger frame → absent → keep-defaults.
        assertNull(flags["kf_n3_0"])
        assertNull(flags["kf_n3_1"])
    }

    @Test
    fun frameFlagsForMarksZeroScoreFramesBad() {
        val ledger = (12..21).map { it.toDouble() }.map { d ->
            entry(d, score = if (d == 16.0) 0.0 else 1.0)
        }
        val detector = FakeFaceDetector(ledger.associate { it.tNs to FaceVerdict.FACE_FREE })
        val route = flaggedRoute()
        val flags = frameFlagsFor(route, keyframeSpecs(route.nodes), ledger, detector)
        assertEquals(FrameFlags(FRAME_QUALITY_BAD, faceFree = true, exposureOk = false), flags["kf_n1_0"])
        assertEquals(FrameFlags(FRAME_QUALITY_GOOD, faceFree = true, exposureOk = true), flags["kf_n1_1"])
    }

    @Test
    fun qualityForFailsOnlyNonPositiveScores() {
        assertEquals(FRAME_QUALITY_BAD, qualityFor(0.0))
        assertEquals(FRAME_QUALITY_BAD, qualityFor(-0.5))
        assertEquals(FRAME_QUALITY_GOOD, qualityFor(0.5))
    }
}
