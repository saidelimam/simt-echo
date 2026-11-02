package app.simt.echo.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan W11-01 gate: the glimpse ledger keeps the sharpest novel frame per 0.5 m. */
class GlimpseLedgerTest {

    companion object {
        private const val W = 32
        private const val H = 32
    }

    /** Sharp fixture: 4 px checkerboard — strong Laplacian responses at every cell edge. */
    private fun sharpLuma(): ByteArray {
        val out = ByteArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                out[y * W + x] = (if ((x / 4 + y / 4) % 2 == 0) 200 else 40).toByte()
            }
        }
        return out
    }

    /** Blurred fixture: smooth mid-range gradient — near-zero Laplacian, fully exposed. */
    private fun blurredLuma(): ByteArray {
        val out = ByteArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                out[y * W + x] = (110 + x / 4).toByte()
            }
        }
        return out
    }

    /** Dark fixture: crushed blacks — exposure gate rejects it. */
    private fun darkLuma(): ByteArray = ByteArray(W * H) { 5 }

    private fun sample(
        luma: ByteArray,
        tNs: Long = 0L,
        jpeg: (() -> ByteArray?)? = null,
        releaseFn: () -> Unit = {},
    ): FrameSample = FrameSample(
        tNs = tNs,
        width = W,
        height = H,
        decimation = 1,
        luma = luma,
        jpeg = jpeg,
        releaseFn = releaseFn,
    )

    private class FakeStore : LedgerStore {
        val persists = mutableListOf<Triple<Long, Long, ByteArray>>()
        override fun persist(bucketIndex: Long, tNs: Long, jpeg: ByteArray): String {
            persists.add(Triple(bucketIndex, tNs, jpeg))
            return "drafts/test/glimpse/$tNs.jpg"
        }
    }

    // Score maths.

    @Test
    fun sharpFixtureBeatsBlurredFixture() {
        val sharp = FrameQuality.sharpness(sharpLuma(), W, H)
        val blurred = FrameQuality.sharpness(blurredLuma(), W, H)
        assertTrue(sharp > 0.0, "checkerboard must carry Laplacian energy, was $sharp")
        assertTrue(blurred < sharp / 100, "staircase gradient ($blurred) must trail texture ($sharp)")
        assertTrue(sharp > blurred)
    }

    @Test
    fun exposureGatePassesMidTonesRejectsDark() {
        assertTrue(FrameQuality.exposureOk(sharpLuma()))
        assertTrue(FrameQuality.exposureOk(blurredLuma()))
        assertFalse(FrameQuality.exposureOk(darkLuma()))
        assertEquals(1.0, FrameQuality.exposureFraction(blurredLuma()), 1e-9)
        assertEquals(0.0, FrameQuality.exposureFraction(darkLuma()), 1e-9)
    }

    @Test
    fun exposureBoundaryIsInclusive() {
        val edge = byteArrayOf(16, 239.toByte(), 15, 240.toByte())
        assertEquals(0.5, FrameQuality.exposureFraction(edge), 1e-9)
        assertFalse(FrameQuality.exposureOk(edge))
    }

    @Test
    fun stabilityFallsWithTurnRate() {
        assertEquals(1.0, FrameQuality.stability(0.0), 1e-12)
        assertEquals(0.5, FrameQuality.stability(0.6), 1e-12)
        assertEquals(
            FrameQuality.stability(0.6),
            FrameQuality.stability(-0.6),
            1e-12,
            "turn direction must not matter",
        )
        assertTrue(FrameQuality.stability(6.0) < FrameQuality.stability(0.6))
    }

    @Test
    fun sharpFactorCapsAtTwoAndNeedsNoNonzeroMedian() {
        assertEquals(2.0, FrameQuality.sharpFactor(sharp = 10.0, routeMedian = 1.0), 1e-12)
        assertEquals(0.0, FrameQuality.sharpFactor(sharp = 0.0, routeMedian = 0.0), 1e-12)
        assertEquals(1.0, FrameQuality.sharpFactor(sharp = 5.0, routeMedian = 5.0), 1e-6)
    }

    @Test
    fun unexposedScoresZeroHoweverSharpOrNovel() {
        assertEquals(0.0, FrameQuality.score(1e6, 1.0, exposed = false, stable = 1.0, novelty = 1.0), 1e-12)
        assertTrue(FrameQuality.score(10.0, 1.0, exposed = true, stable = 1.0, novelty = 1.0) > 0.0)
    }

    @Test
    fun noveltyIsOneWithoutHistoryZeroForDuplicates() {
        val hash = FrameQuality.dHash(sharpLuma(), W, H)
        assertEquals(1.0, FrameQuality.novelty(hash, null), 1e-12)
        assertEquals(0.0, FrameQuality.novelty(hash, hash), 1e-12)
        val inverted = sharpLuma().map { (255 - (it.toInt() and 0xFF)).toByte() }.toByteArray()
        val flipped = FrameQuality.dHash(inverted, W, H)
        assertTrue(FrameQuality.hamming(hash, flipped) > 0, "inversion must move the hash")
        assertEquals(0, FrameQuality.hamming(hash, hash))
        assertEquals(64, FrameQuality.hamming(0L, -1L))
    }

    @Test
    fun dHashSetsOneBitPerRisingStep() {
        // 9x8 plane: row 0 rises left-to-right, every other row is flat.
        val plane = ByteArray(9 * 8)
        for (x in 0 until 9) plane[x] = x.toByte()
        assertEquals(0xFFL, FrameQuality.dHash(plane, 9, 8))
        for (x in 0 until 9) plane[x] = (8 - x).toByte()
        assertEquals(0L, FrameQuality.dHash(plane, 9, 8))
    }

    @Test
    fun uniformPlaneHashesToZero() {
        assertEquals(0L, FrameQuality.dHash(ByteArray(W * H) { 128.toByte() }, W, H))
    }

    // Ledger behaviour.

    @Test
    fun sharpReplacesBlurredInSameBucket() {
        val ledger = GlimpseLedger()
        val first = ledger.offer(sample(blurredLuma(), tNs = 1L), odometerM = 0.1)
        assertTrue(first is LedgerOffer.Kept)
        val second = ledger.offer(sample(sharpLuma(), tNs = 2L), odometerM = 0.2)
        assertTrue(second is LedgerOffer.Replaced, "sharp must displace blurred, was $second")
        assertEquals(1, ledger.keptCount())
        assertEquals(2L, ledger.entries().single().tNs)
    }

    @Test
    fun blurredDoesNotDisplaceSharp() {
        val ledger = GlimpseLedger()
        ledger.offer(sample(sharpLuma(), tNs = 1L), odometerM = 0.1)
        val result = ledger.offer(sample(blurredLuma(), tNs = 2L), odometerM = 0.2)
        assertTrue(result is LedgerOffer.Dropped, "blurred must lose to sharp, was $result")
        assertEquals(1L, ledger.entries().single().tNs)
    }

    @Test
    fun darkFrameNeverDisplacesKeptFrame() {
        val ledger = GlimpseLedger()
        ledger.offer(sample(sharpLuma(), tNs = 1L), odometerM = 0.1)
        val result = ledger.offer(sample(darkLuma(), tNs = 2L), odometerM = 0.2)
        assertTrue(result is LedgerOffer.Dropped)
        assertEquals(0.0, result.score, 1e-12, "dark scores exactly zero via the exposure gate")
        assertEquals(1L, ledger.entries().single().tNs)
    }

    @Test
    fun darkFrameHoldsAnEmptyBucketButLosesToAnyExposedFrame() {
        val ledger = GlimpseLedger()
        val first = ledger.offer(sample(darkLuma(), tNs = 1L), odometerM = 0.1)
        assertTrue(first is LedgerOffer.Kept, "empty bucket keeps coverage, was $first")
        assertEquals(0.0, first.score, 1e-12)
        // Any exposed frame with Laplacian energy outscores the zero gate.
        val second = ledger.offer(sample(sharpLuma(), tNs = 2L), odometerM = 0.2)
        assertTrue(second is LedgerOffer.Replaced, "exposed must displace dark, was $second")
    }

    @Test
    fun duplicateScoresHalfOfNovelInFreshBuckets() {
        val ledger = GlimpseLedger()
        ledger.offer(sample(sharpLuma(), tNs = 1L), odometerM = 0.1)
        ledger.offer(sample(sharpLuma(), tNs = 2L), odometerM = 0.6)
        val entries = ledger.entries()
        assertEquals(2, entries.size, "distinct buckets both keep")
        assertEquals(entries[0].score * 0.5, entries[1].score, 1e-9)
    }

    @Test
    fun bucketsSpanHalfAMetreInOrder() {
        val ledger = GlimpseLedger()
        assertEquals(ledger.bucketFor(0.1), ledger.bucketFor(0.49))
        assertEquals(ledger.bucketFor(0.1) + 1, ledger.bucketFor(0.5))
        ledger.offer(sample(sharpLuma(), tNs = 1L), odometerM = 0.1)
        ledger.offer(sample(sharpLuma(), tNs = 2L), odometerM = 0.49)
        ledger.offer(sample(sharpLuma(), tNs = 3L), odometerM = 0.5)
        assertEquals(2, ledger.keptCount())
        assertEquals(listOf(1L, 3L), ledger.entries().map { it.tNs })
    }

    @Test
    fun tiesLoseSoTheFirstFrameWins() {
        val ledger = GlimpseLedger()
        ledger.offer(sample(blurredLuma(), tNs = 1L), odometerM = 0.1)
        // Same bytes, but novelty is now 0 while the incumbent was scored novel:
        // strictly worse, so the incumbent stands.
        val result = ledger.offer(sample(blurredLuma(), tNs = 2L), odometerM = 0.2)
        assertTrue(result is LedgerOffer.Dropped)
        assertEquals(1L, ledger.entries().single().tNs)
    }

    @Test
    fun sameOfferSequenceYieldsTheSameKeptSet() {
        val script = listOf(
            Triple(sharpLuma(), 0.1, 0.0),
            Triple(blurredLuma(), 0.2, 0.0),
            Triple(darkLuma(), 0.35, 0.0),
            Triple(sharpLuma(), 0.6, 1.2),
            Triple(blurredLuma(), 0.7, 0.0),
            Triple(sharpLuma(), 1.05, 0.3),
        )
        fun run(): List<GlimpseEntry> {
            val ledger = GlimpseLedger()
            var t = 0L
            for ((luma, odo, omega) in script) {
                ledger.offer(sample(luma.copyOf(), tNs = ++t), odometerM = odo, omegaRadPerSec = omega)
            }
            return ledger.entries()
        }
        val first = run()
        val second = run()
        assertEquals(first.size, second.size)
        for ((a, b) in first.zip(second)) {
            assertEquals(a, b, "ledger must be deterministic")
        }
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun jpegPersistedOnlyForKeptFrames() {
        val store = FakeStore()
        val ledger = GlimpseLedger(store = store)
        var droppedEncoded = false
        ledger.offer(sample(sharpLuma(), tNs = 1L, jpeg = { byteArrayOf(1, 2, 3) }), odometerM = 0.1)
        ledger.offer(
            sample(blurredLuma(), tNs = 2L, jpeg = { droppedEncoded = true; byteArrayOf(9) }),
            odometerM = 0.2,
        )
        assertFalse(droppedEncoded, "dropped frames must never trigger JPEG encode")
        assertEquals(1, store.persists.size)
        val (bucket, tNs, bytes) = store.persists.single()
        assertEquals(ledger.bucketFor(0.1), bucket)
        assertEquals(1L, tNs)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(bytes))
        assertEquals("drafts/test/glimpse/1.jpg", ledger.entries().single().jpegPath)
    }

    @Test
    fun metadataAlwaysEvenWithoutJpegOrStore() {
        val ledger = GlimpseLedger()
        ledger.offer(sample(sharpLuma(), tNs = 7L), odometerM = 3.3)
        val entry = ledger.entries().single()
        assertNull(entry.jpegPath)
        assertEquals(7L, entry.tNs)
        assertEquals(3.3, entry.odometerM, 1e-12)
        assertTrue(entry.score > 0.0)
        assertEquals(ledger.bucketFor(3.3), entry.bucketIndex)
    }

    @Test
    fun offerReleasesTheSampleExactlyOnce() {
        var releases = 0
        val ledger = GlimpseLedger()
        ledger.offer(sample(sharpLuma(), releaseFn = { releases++ }), odometerM = 0.1)
        ledger.offer(sample(blurredLuma(), releaseFn = { releases++ }), odometerM = 0.1)
        assertEquals(2, releases)
    }

    @Test
    fun offerReleasesTheSampleWhenPersistenceThrows() {
        var releases = 0
        val failingStore = object : LedgerStore {
            override fun persist(bucketIndex: Long, tNs: Long, jpeg: ByteArray): String? = error("disk full")
        }
        val ledger = GlimpseLedger(store = failingStore)
        assertFailsWith<IllegalStateException> {
            ledger.offer(
                sample(sharpLuma(), jpeg = { byteArrayOf(1) }, releaseFn = { releases++ }),
                odometerM = 0.1,
            )
        }
        assertEquals(1, releases)
    }

    @Test
    fun routeMedianStaysCorrectAcrossManyOffers() {
        // 600 offers outgrow the initial sorted-array capacity; scoring must
        // stay identical to a fresh ledger fed the same final frame.
        val ledger = GlimpseLedger()
        repeat(600) { i -> ledger.offer(sample(sharpLuma(), tNs = i.toLong()), odometerM = i * 0.5) }
        assertEquals(600, ledger.keptCount())
        assertTrue(ledger.entries().all { it.score > 0.0 })
    }
}
