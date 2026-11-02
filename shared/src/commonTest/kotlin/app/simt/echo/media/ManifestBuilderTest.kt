package app.simt.echo.media

import app.simt.echo.Fixtures
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.RouteNode
import app.simt.echo.model.TurnDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

/** W3-01 gate: clip windows, route context, approach sampling, §5.5 manifest. */
class ManifestBuilderTest {

    // ── clipWindow ─────────────────────────────────────────────────────────

    @Test
    fun clipWindowIsFiveSecondsAroundNode() {
        val w = clipWindow(40_000L)
        assertEquals(36_000L, w.startMs)
        assertEquals(41_000L, w.endMs)
    }

    @Test
    fun clipWindowClampsStartToZero() {
        val w = clipWindow(1_000L)
        assertEquals(0L, w.startMs)
        assertEquals(2_000L, w.endMs)
    }

    @Test
    fun clipWindowClampsEndToDuration() {
        val w = clipWindow(99_500L, durationMs = 100_000L)
        assertEquals(95_500L, w.startMs)
        assertEquals(100_000L, w.endMs)
    }

    @Test
    fun clipWindowNeverInvertsWhenDurationIsShort() {
        val w = clipWindow(500L, durationMs = 600L)
        assertTrue(w.endMs >= w.startMs)
        assertEquals(0L, w.startMs)
        assertEquals(600L, w.endMs)
    }

    // ── routeContext ───────────────────────────────────────────────────────

    @Test
    fun routeContextMatchesPlanFormat() {
        // L_ROUTE: 45 m, 2 turns, 0 level changes.
        assertEquals("Indoor walk, 45 m, 2 turns, 0 level changes", routeContext(Fixtures.L_ROUTE))
    }

    @Test
    fun routeContextCountsLevelChanges() {
        val route = Fixtures.L_ROUTE.copy(
            nodes = Fixtures.L_ROUTE.nodes + RouteNode(
                id = "n4",
                type = NodeType.LEVEL_CHANGE,
                atDistanceM = 40.0,
                label = "Stairs",
                levelDelta = 1,
            ),
            totalLengthM = 50.0,
        )
        assertEquals("Indoor walk, 50 m, 2 turns, 1 level changes", routeContext(route))
    }

    @Test
    fun routeContextRoundsLength() {
        val route = Fixtures.L_ROUTE.copy(totalLengthM = 44.4)
        assertEquals("Indoor walk, 44 m, 2 turns, 0 level changes", routeContext(route))
    }

    // ── approachSamples / distanceAt ───────────────────────────────────────

    /** 2 Hz heartbeat log: 1 m per second, video starting at t = 0. */
    private fun logWith(samples: List<Pair<Long, Double>>): CaptureLog =
        CaptureLog(
            draftId = "d1",
            startedAtEpochMs = 0L,
            motionSource = "echo_builtin_pdr",
            videoStartNs = 0L,
            samples = samples.map { (tMs, d) -> CaptureSample(tMs * 1_000_000L, d, 0.0) },
            marks = emptyList(),
            startPhoto = "start.jpg",
        )

    @Test
    fun approachSamplesStepEveryFiveHundredMs() {
        val log = logWith(listOf(0L to 0.0, 10_000L to 10.0))
        val samples = approachSamples(log, clipWindow(5_000L))
        // window [1000, 6000] → t = 1000, 1500, …, 6000 (11 samples)
        assertEquals(11, samples.size)
        assertEquals(1_000L, samples.first().tMs)
        assertEquals(6_000L, samples.last().tMs)
    }

    @Test
    fun approachInterpolatesDistance() {
        val log = logWith(listOf(0L to 0.0, 10_000L to 10.0))
        val samples = approachSamples(log, ClipWindow(4_000L, 5_000L))
        // t=4000 → d=4.0, t=4500 → d=4.5, t=5000 → d=5.0
        assertEquals(4.0, samples[0].d, 1e-9)
        assertEquals(4.5, samples[1].d, 1e-9)
        assertEquals(5.0, samples[2].d, 1e-9)
    }

    @Test
    fun approachClampsOutsideSampleRange() {
        val log = logWith(listOf(0L to 0.0, 1_000L to 1.0))
        val samples = approachSamples(log, ClipWindow(0L, 5_000L))
        // Before first sample → first d; after last sample → last d.
        assertEquals(0.0, samples.first().d, 1e-9)
        assertEquals(1.0, samples.last().d, 1e-9)
    }

    @Test
    fun approachEmptyWhenLogHasNoSamples() {
        val log = logWith(emptyList())
        assertEquals(emptyList(), approachSamples(log, clipWindow(5_000L)))
    }

    @Test
    fun approachEmptyForCollapsedWindow() {
        val log = logWith(listOf(0L to 0.0))
        assertEquals(emptyList(), approachSamples(log, ClipWindow(1_000L, 1_000L)))
    }

    // ── ManifestBuilder ────────────────────────────────────────────────────

    /** L_ROUTE with capture times: n1 @ 40 s, n2 @ 60 s, n3 @ 70 s. */
    private fun timedRoute(): EchoRoute = Fixtures.L_ROUTE.copy(
        nodes = Fixtures.L_ROUTE.nodes.map { node ->
            node.copy(
                captureTMs = when (node.id) {
                    "n1" -> 40_000L
                    "n2" -> 60_000L
                    "n3" -> 70_000L
                    else -> null
                },
            )
        },
    )

    private fun testLog(): CaptureLog =
        CaptureLog(
            draftId = "draft-1",
            startedAtEpochMs = 1_762_000_000_000L,
            motionSource = "echo_builtin_pdr",
            videoStartNs = 0L,
            // 2 Hz heartbeat over 75 s, 1 m/s.
            samples = (0..75).map { CaptureSample(it.toLong() * 1_000_000_000L, it.toDouble(), 0.0) },
            marks = emptyList(),
            startPhoto = "start.jpg",
        )

    @Test
    fun manifestBuildsSection55ShapeForLRoute() {
        val route = timedRoute()
        val specs = keyframeSpecs(route.nodes)
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = specs,
            clips = emptyMap(),
            consent = ManifestConsent(sendFramesToCloudAi = true, atEpochMs = 1_762_000_001_000L),
        )

        assertEquals(1, manifest.schema)
        assertEquals("draft-1", manifest.draftId)
        assertEquals("Indoor walk, 45 m, 2 turns, 0 level changes", manifest.routeContext)
        // START excluded: 3 checkpoints.
        assertEquals(listOf("n1", "n2", "n3"), manifest.checkpoints.map { it.nodeId })

        val turn = manifest.checkpoints[0]
        assertEquals("TURN", turn.type)
        assertEquals("RIGHT", turn.turn)
        assertEquals(20.0, turn.atDistanceM)
        assertEquals(40_000L, turn.tMs)
        assertEquals("kf_n1_1", turn.heroKeyframeId)
        assertEquals(
            listOf("kf_n1_0", "kf_n1_1", "kf_n1_2"),
            turn.keyframes.map { it.id },
        )
        assertEquals(listOf(38_000L, 39_500L, 41_500L), turn.keyframes.map { it.tMs })
        assertNull(turn.clip)
        // approach over [36000, 41000] at 500 ms → 11 samples; the log walks
        // 1 m/s, so d(t) = t in seconds (36.0 … 41.0).
        assertEquals(11, turn.approach.size)
        assertEquals(36_000L, turn.approach.first().tMs)
        assertEquals(36.0, turn.approach.first().d, 1e-9)
        assertEquals(41.0, turn.approach.last().d, 1e-9)
    }

    @Test
    fun manifestDestinationUsesLastFrameAsHero() {
        val route = timedRoute()
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = emptyMap(),
            consent = ManifestConsent(true, 0L),
        )
        val dest = manifest.checkpoints.single { it.nodeId == "n3" }
        assertEquals("DESTINATION", dest.type)
        assertNull(dest.turn)
        assertEquals(2, dest.keyframes.size)
        // hero = last frame → kf_n3_1 at t = 70 000.
        assertEquals("kf_n3_1", dest.heroKeyframeId)
        assertEquals(70_000L, dest.keyframes.last().tMs)
    }

    @Test
    fun manifestIncludesClipWhenSamEnabled() {
        val route = timedRoute()
        val clips = mapOf(
            "n1" to ForgedClip(ClipInfo("clips/n1.mp4".toPath(), 50, 5_000L), ClipWindow(36_000L, 41_000L)),
        )
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = clips,
            consent = ManifestConsent(true, 0L),
        )
        val clip = manifest.checkpoints.first { it.nodeId == "n1" }.clip
        assertEquals("clip_n1", clip?.id)
        assertEquals(36_000L, clip?.startMs)
        assertEquals(41_000L, clip?.endMs)
        assertEquals(50, clip?.frameCount)
        assertEquals(5_000L, clip?.durationMs)
        assertNull(manifest.checkpoints.first { it.nodeId == "n2" }.clip)
    }

    @Test
    fun manifestDescribesTheWindowActuallyForgedNotThePlannedOne() {
        val route = timedRoute()
        // A 3 s fallback transcode, [t - 2250, t + 750], not the planned t - 4000..t + 1000.
        val forged = ForgedClip(ClipInfo("clips/n1.mp4".toPath(), 12, 3_000L), ClipWindow(37_750L, 40_750L))
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = mapOf("n1" to forged),
            consent = ManifestConsent(true, 0L),
        )
        val n1 = manifest.checkpoints.first { it.nodeId == "n1" }
        assertEquals(37_750L, n1.clip?.startMs)
        assertEquals(40_750L, n1.clip?.endMs)
        // Approach samples cover that same window every 500 ms, not 50 samples.
        assertEquals(37_750L, n1.approach.first().tMs)
        assertEquals(40_750L, n1.approach.last().tMs)
        assertEquals(7, n1.approach.size)
        // A node with no clip keeps the planned window for its approach samples.
        val n2 = manifest.checkpoints.first { it.nodeId == "n2" }
        assertEquals(56_000L, n2.approach.first().tMs)
        assertEquals(61_000L, n2.approach.last().tMs)
    }

    @Test
    fun stripClipWindowSpansFirstToLastFrameRelativeToVideoStart() {
        fun frame(tNs: Long) = GlimpseEntry(0, tNs, 0.0, 1.0, 1.0, 0L, "glimpse/b.jpg")
        val strip = listOf(frame(12_500_000_000L), frame(13_000_000_000L), frame(15_250_000_000L))
        assertEquals(ClipWindow(2_500L, 5_250L), stripClipWindow(strip, videoStartNs = 10_000_000_000L))
        // Never negative, never inverted.
        assertEquals(ClipWindow(0L, 0L), stripClipWindow(listOf(frame(5_000_000_000L)), videoStartNs = 10_000_000_000L))
    }

    @Test
    fun manifestExcludesNodesWithoutCaptureTime() {
        val route = Fixtures.L_ROUTE // no captureTMs anywhere
        val manifest = ManifestBuilder.build(
            route = route,
            log = testLog(),
            specs = keyframeSpecs(route.nodes),
            clips = emptyMap(),
            consent = ManifestConsent(true, 0L),
        )
        assertEquals(emptyList(), manifest.checkpoints)
    }

    @Test
    fun clipInfoDerivesFps() {
        val info = ClipInfo("clips/n1.mp4".toPath(), 50, 5_000L)
        assertEquals(10.0, info.fps, 1e-9)
        assertEquals(0.0, ClipInfo("x".toPath(), 0, 0L).fps, 1e-9)
    }
}
