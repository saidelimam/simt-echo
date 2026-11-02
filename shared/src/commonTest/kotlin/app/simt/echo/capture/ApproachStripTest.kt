package app.simt.echo.capture

import app.simt.echo.model.NodeType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan W11-02 gate: fixture ledger → expected frames per node type. */
class ApproachStripTest {

    private fun entry(
        d: Double,
        score: Double = 1.0,
        tNs: Long = (d * 1_000_000_000L).toLong(),
        jpeg: String? = "glimpse/b${(d * 2).toLong()}.jpg",
    ): GlimpseEntry = GlimpseEntry(
        bucketIndex = (d * 2).toLong(),
        tNs = tNs,
        odometerM = d,
        score = score,
        sharp = score,
        hash = d.toLong(),
        jpegPath = jpeg,
    )

    private fun ledger(ds: List<Double>, score: (Double) -> Double = { 1.0 }): List<GlimpseEntry> =
        ds.map { entry(it, score(it)) }

    private fun distances(frames: List<GlimpseEntry>): List<Double> =
        frames.map { it.odometerM }

    // ── window ───────────────────────────────────────────────────────────

    @Test
    fun windowBoundsAreInclusive() {
        val entries = ledger(listOf(11.9, 12.0, 15.0, 21.0, 21.1))
        val strip = approachStrip(entries, d = 20.0)
        assertEquals(listOf(12.0, 15.0, 21.0), distances(strip))
    }

    @Test
    fun emptyWindowFallsBackToEmpty() {
        val entries = ledger(listOf(0.0, 0.5, 1.0, 30.0))
        assertTrue(approachStrip(entries, d = 20.0).isEmpty())
        assertTrue(ledgerKeyframes(emptyList(), NodeType.TURN, 20.0).isEmpty())
        assertTrue(ledgerKeyframes(emptyList(), NodeType.CHECKPOINT, 20.0).isEmpty())
    }

    @Test
    fun metadataOnlyFramesAreExcludedFromTheStrip() {
        val entries = (2..11).map { it.toDouble() }.map { d ->
            entry(d, score = if (d == 5.0) 999.0 else 1.0, jpeg = if (d == 5.0) null else "glimpse/b.jpg")
        }
        val strip = approachStrip(entries, d = 10.0)
        assertFalse(distances(strip).contains(5.0), "a frame with no bytes must never be stored")
        assertEquals(9, strip.size)
    }

    // ── spacing + cap ────────────────────────────────────────────────────

    @Test
    fun spacingKeepsTheSharperNeighbour() {
        // Peak at 15.0; the near-tie at 15.1 must lose to spacing.
        // (Decimals via /10 so 15.0 and 15.1 equal their literals —
        // repeated +0.1 accumulates float error and breaks contains().)
        val ds = (120..210).map { it / 10.0 }
        val entries = ds.map { entry(it, score = 100.0 - abs(it - 15.0)) }
        val strip = approachStrip(entries, d = 20.0)
        assertTrue(distances(strip).contains(15.0), "the sharpest frame must survive")
        assertFalse(distances(strip).contains(15.1), "15.1 m is within 0.4 m of 15.0 m")
        for (i in strip.indices) {
            for (j in i + 1 until strip.size) {
                assertTrue(
                    abs(strip[i].odometerM - strip[j].odometerM) >= 0.4 - 1e-9,
                    "strip frames must stand ≥0.4 m apart: ${strip[i].odometerM} vs ${strip[j].odometerM}",
                )
            }
        }
    }

    @Test
    fun stripCapsAtTwelveByScore() {
        // 19 candidates at 0.5 m spacing; scores rise with distance.
        val ds = generateSequence(12.0) { it + 0.5 }.takeWhile { it <= 21.0 }.toList()
        assertEquals(19, ds.size)
        val entries = ds.map { entry(it, score = it) }
        val strip = approachStrip(entries, d = 20.0)
        assertEquals(12, strip.size)
        val expected = listOf(15.5, 16.0, 16.5, 17.0, 17.5, 18.0, 18.5, 19.0, 19.5, 20.0, 20.5, 21.0)
        assertEquals(expected, distances(strip).sorted())
    }

    @Test
    fun stripIsTimeOrdered() {
        // tNs deliberately runs against odometry: time order, not distance.
        val entries = listOf(12.0, 13.0, 14.0, 15.0, 16.0).map { entry(it, tNs = -(it * 1e9).toLong()) }
        val strip = approachStrip(entries, d = 20.0)
        assertEquals(listOf(16.0, 15.0, 14.0, 13.0, 12.0), distances(strip))
    }

    // ── keyframe picks ───────────────────────────────────────────────────

    @Test
    fun turnNodeGetsTripleNearestToOffsets() {
        // d−4=16.2, d−1=19.2, d+0.75=20.95 → 16, 19, 21 (no ties: the
        // 20.5/21.0 pair would split 20.75 evenly at d=20). The decoy at
        // 15.0 scores 100 but loses: picks are by distance, never by score.
        // d=20.2 also pushes 12.0 just outside the [d−8, d+1] window.
        val entries = (12..21).map { it.toDouble() }.map { d ->
            entry(d, score = if (d == 15.0) 100.0 else 1.0)
        }
        val strip = approachStrip(entries, d = 20.2)
        assertFalse(distances(strip).contains(12.0))
        assertEquals(listOf(16.0, 19.0, 21.0), distances(ledgerKeyframes(strip, NodeType.TURN, 20.2)))
    }

    @Test
    fun checkpointNodeGetsDouble() {
        val entries = ledger((12..21).map { it.toDouble() })
        val strip = approachStrip(entries, d = 20.0)
        assertEquals(listOf(17.0, 20.0), distances(ledgerKeyframes(strip, NodeType.CHECKPOINT, 20.0)))
    }

    @Test
    fun destinationNodeGetsDouble() {
        val entries = ledger((37..46).map { it.toDouble() })
        val strip = approachStrip(entries, d = 45.0)
        assertEquals(listOf(42.0, 45.0), distances(ledgerKeyframes(strip, NodeType.DESTINATION, 45.0)))
    }

    @Test
    fun levelChangeNodeGetsDouble() {
        val entries = ledger((2..11).map { it.toDouble() })
        val strip = approachStrip(entries, d = 10.0)
        assertEquals(listOf(7.0, 10.0), distances(ledgerKeyframes(strip, NodeType.LEVEL_CHANGE, 10.0)))
    }

    @Test
    fun nearestTiePrefersHigherScore() {
        // Target d−4=16: 15.5 and 16.5 tie at 0.5 m; sharper wins.
        val strip = listOf(entry(15.5, score = 1.0), entry(16.5, score = 9.0))
        val picks = ledgerKeyframes(strip, NodeType.TURN, 20.0)
        assertEquals(16.5, picks.first().odometerM)
    }

    @Test
    fun oneFrameNeverFillsTwoSlots() {
        val strip = listOf(entry(19.0))
        assertEquals(listOf(19.0), distances(ledgerKeyframes(strip, NodeType.TURN, 20.0)))
    }

    // ── start photo ──────────────────────────────────────────────────────

    @Test
    fun startPhotoPicksBestWithinOneMetre() {
        val entries = listOf(0.0, 0.5, 1.0, 1.5, 3.0).map { d ->
            entry(d, score = if (d == 3.0) 999.0 else d)
        }
        assertEquals(1.0, startPhotoEntry(entries)?.odometerM)
    }

    @Test
    fun startPhotoIsNullPastOneMetre() {
        assertNull(startPhotoEntry(ledger(listOf(1.5, 2.0, 5.0))))
        assertNull(startPhotoEntry(emptyList()))
    }

    @Test
    fun startPhotoTiePrefersEarliestFrame() {
        val entries = listOf(
            entry(0.5, score = 5.0, tNs = 200L),
            entry(1.0, score = 5.0, tNs = 100L),
        )
        assertEquals(100L, startPhotoEntry(entries)?.tNs)
    }

    @Test
    fun startPhotoIgnoresMetadataOnly() {
        val entries = listOf(
            entry(0.5, score = 9.0, jpeg = null),
            entry(1.0, score = 4.0),
        )
        assertEquals(1.0, startPhotoEntry(entries)?.odometerM)
    }
}
