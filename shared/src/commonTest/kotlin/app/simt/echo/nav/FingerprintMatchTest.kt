package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.model.EmbeddedPrint
import app.simt.echo.nav.ghost.FallbackEmbedder
import app.simt.echo.nav.ghost.FingerprintCandidate
import app.simt.echo.nav.ghost.FingerprintCapture
import app.simt.echo.nav.ghost.FingerprintHit
import app.simt.echo.nav.ghost.FingerprintMatch
import app.simt.echo.nav.ghost.FingerprintRaw
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.Observation
import app.simt.echo.nav.ghost.PrintSourceFrame
import app.simt.echo.platform.MotionUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W12-05 gate: visual-fingerprint matching as a pure unit — fixture
 * embeddings, identity + window gating, τ threshold, the never-silent
 * confirm rule, low-weight filter observation, capture policy, and
 * determinism.
 */
class FingerprintMatchTest {

    private val embedder = FallbackEmbedder()
    private val base = ByteArray(EmbeddedPrint.PRINT_BYTES) { 100 }
    private fun candidate(
        atS: Double = 20.0,
        vector: ByteArray = base,
        id: String = FallbackEmbedder.FALLBACK_EMBEDDER_ID,
        seed: Int = FingerprintCapture.PROJECTION_SEED_VERSION,
        label: String = "Red pillar",
    ) = FingerprintCandidate(atS, id, seed, vector, label)

    @Test
    fun matchAboveTauInWindow() {
        val hit = FingerprintMatch.matchFingerprints(
            query = base.copyOf(),
            queryEmbedderId = FallbackEmbedder.FALLBACK_EMBEDDER_ID,
            querySeedVersion = FingerprintCapture.PROJECTION_SEED_VERSION,
            meanS = 19.0,
            sigma = 1.0,
            candidates = listOf(candidate()),
        )
        assertTrue(hit != null, "identical in-window print must match")
        assertEquals(20.0, hit.nodeS)
        assertEquals(1.0, hit.similarity01, 1e-9)
        assertEquals("Red pillar", hit.label)
    }

    @Test
    fun belowTauRejected() {
        // Alternating ±127 sums to zero against the flat candidate: cosine 0.
        val orthogonal = ByteArray(EmbeddedPrint.PRINT_BYTES) { i ->
            if (i % 2 == 0) 127 else -127
        }
        val hit = FingerprintMatch.matchFingerprints(
            query = orthogonal,
            queryEmbedderId = FallbackEmbedder.FALLBACK_EMBEDDER_ID,
            querySeedVersion = FingerprintCapture.PROJECTION_SEED_VERSION,
            meanS = 19.0,
            sigma = 1.0,
            candidates = listOf(candidate()),
        )
        assertNull(hit, "cosine 0 must stay below τ")
    }

    @Test
    fun outOfWindowIgnored() {
        val hit = FingerprintMatch.matchFingerprints(
            query = base.copyOf(),
            queryEmbedderId = FallbackEmbedder.FALLBACK_EMBEDDER_ID,
            querySeedVersion = FingerprintCapture.PROJECTION_SEED_VERSION,
            meanS = 0.0,
            sigma = 1.0,
            candidates = listOf(candidate()),
        )
        // |20 − 0| = 20 m > 3·1 + 5 = 8 m: identical bytes, still ignored.
        assertNull(hit, "identical print outside s ± (3σ + 5 m) must not match")
    }

    @Test
    fun windowMathIsThreeSigmaPlusFive() {
        assertEquals(5.0, FingerprintMatch.matchRadiusM(0.0), 1e-9)
        assertEquals(11.0, FingerprintMatch.matchRadiusM(2.0), 1e-9)
    }

    @Test
    fun identityGateRejectsForeignPrints() {
        val foreignId = candidate(id = "other-model")
        val foreignSeed = candidate(seed = FingerprintCapture.PROJECTION_SEED_VERSION + 1)
        for (candidate in listOf(foreignId, foreignSeed)) {
            val hit = FingerprintMatch.matchFingerprints(
                query = base.copyOf(),
                queryEmbedderId = FallbackEmbedder.FALLBACK_EMBEDDER_ID,
                querySeedVersion = FingerprintCapture.PROJECTION_SEED_VERSION,
                meanS = 19.0,
                sigma = 1.0,
                candidates = listOf(candidate),
            )
            assertNull(hit, "embedder id + seed must gate even identical bytes")
        }
    }

    @Test
    fun bareMatchNeverConfirms() {
        assertEquals(false, FingerprintMatch.isConfirmedBy(userConfirmed = false, consistentTurnEvent = false))
        assertEquals(true, FingerprintMatch.isConfirmedBy(userConfirmed = true, consistentTurnEvent = false))
        assertEquals(true, FingerprintMatch.isConfirmedBy(userConfirmed = false, consistentTurnEvent = true))
    }

    @Test
    fun chipCopyAsksNeverAsserts() {
        val hit = FingerprintHit(nodeS = 20.0, similarity01 = 0.9, label = "Red pillar")
        val nav = Navigator(Fixtures.L_ROUTE)
        nav.onInput(NavInput.Aligned)
        nav.onFingerprintHit(hit)
        // A suggestion proposes: the phase does not move on match alone.
        assertEquals(NavPhase.WALK, nav.ui.value.phase)
        assertEquals("That's the Red pillar?", nav.ui.value.fingerprintPrompt?.chipCopy())
        nav.dismissFingerprint()
        assertNull(nav.ui.value.fingerprintPrompt)
        // The same landmark may be suggested again once the debounce
        // window (3 s and 2 m) has passed.
        nav.onInput(NavInput.Motion(MotionUpdate(4_000_000_000L, 3.0, 0.0, 0, true, null, "test")))
        nav.onFingerprintHit(hit)
        assertEquals("That's the Red pillar?", nav.ui.value.fingerprintPrompt?.chipCopy())
        nav.confirmFingerprint()
        assertNull(nav.ui.value.fingerprintPrompt)
    }

    @Test
    fun fingerprintObservationIsLowWeight() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 1L)
        filter.reset(10.0)
        filter.observe(Observation.Fingerprint(nodeS = 10.0, similarity01 = 1.0))
        // A full-similarity hit at the cloud centre must not resample: the
        // 2:1 likelihood cap keeps the weights near-uniform.
        assertEquals(0, filter.resampleCount)
        assertTrue(filter.effectiveN > 200.0, "effectiveN was ${filter.effectiveN}")
        assertTrue(filter.meanS in 8.0..12.0, "meanS was ${filter.meanS}")
    }

    @Test
    fun fallbackEmbedderIsDeterministic() {
        val raw = FingerprintRaw(dHash = 0x123456789ABCDEF0L, moments = List(16) { 0.5 })
        val first = embedder.embed(raw).dataB64
        val second = embedder.embed(raw).dataB64
        assertEquals(first, second)
        val other = embedder.embed(raw.copy(dHash = 0x0FEDCBA987654321L)).dataB64
        assertTrue(other != first, "distinct frames must embed distinctly")
    }

    @Test
    fun matchIsDeterministic() {
        fun run() = FingerprintMatch.matchFingerprints(
            query = base.copyOf(),
            queryEmbedderId = FallbackEmbedder.FALLBACK_EMBEDDER_ID,
            querySeedVersion = FingerprintCapture.PROJECTION_SEED_VERSION,
            meanS = 19.0,
            sigma = 1.0,
            candidates = listOf(candidate(), candidate(atS = 21.0)),
        )
        assertEquals(run(), run())
    }

    @Test
    fun checkpointPrintsCapAtThreeInWindow() {
        fun frame(odometerM: Double, score: Double) = PrintSourceFrame(
            raw = FingerprintRaw(dHash = odometerM.toBits(), moments = List(16) { 0.5 }),
            score = score,
            odometerM = odometerM,
        )
        val frames = listOf(
            frame(odometerM = 5.0, score = 9.0), // outside [12, 21]: excluded
            frame(odometerM = 13.0, score = 0.1),
            frame(odometerM = 15.0, score = 0.9),
            frame(odometerM = 17.0, score = 0.5),
            frame(odometerM = 19.0, score = 0.7),
            frame(odometerM = 20.5, score = 0.8),
            frame(odometerM = 30.0, score = 9.0), // outside: excluded
        )
        val prints = FingerprintCapture.checkpointPrints(frames, nodeS = 20.0, embedder = embedder)
        assertEquals(EmbeddedPrint.MAX_PER_NODE, prints.size)
        prints.forEach { it.validate("checkpoint") }
        assertEquals(prints.map { it.dataB64 }, FingerprintCapture.checkpointPrints(frames, 20.0, embedder).map { it.dataB64 })
    }

    @Test
    fun waypointDistancesStepEveryFiveMetres() {
        assertEquals(
            (0..45 step 5).map { it.toDouble() },
            FingerprintCapture.waypointDistances(45.0),
        )
        assertEquals(listOf(0.0, 5.0, 10.0), FingerprintCapture.waypointDistances(12.0))
    }

    @Test
    fun candidatesPreferLandmarkTitle() {
        val candidates = FingerprintMatch.candidatesFor(Fixtures.V2_ROUTE)
        assertTrue(candidates.isNotEmpty(), "v2 fixture must yield candidates")
        val atTurn = candidates.firstOrNull { it.atDistanceM == 20.0 && it.label == "Red pillar" }
        assertTrue(atTurn != null, "node print must carry the landmark title")
        // The stored print matches itself through the full bundle path.
        val hit = FingerprintMatch.matchFingerprints(
            query = atTurn.vector.copyOf(),
            queryEmbedderId = atTurn.embedderId,
            querySeedVersion = atTurn.projectionSeedVersion,
            meanS = 19.0,
            sigma = 1.0,
            candidates = candidates,
        )
        assertTrue(hit != null, "stored print must self-match in window")
        assertEquals("Red pillar", hit.label)
    }
}
