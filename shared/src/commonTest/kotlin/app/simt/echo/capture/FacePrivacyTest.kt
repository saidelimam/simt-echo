package app.simt.echo.capture

import app.simt.echo.transfer.FACE_FILTER_DISCLOSURE
import app.simt.echo.ui.screens.processing.ConsentInfo
import app.simt.echo.ui.screens.processing.consentCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan W11-04 gate: verdict mapping, strip exclusion, disclosure copy. */
class FacePrivacyTest {

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

    // ── verdict mapping ────────────────────────────────────────────────

    @Test
    fun onlyFaceFreeIsFaceFree() {
        assertTrue(FaceVerdict.FACE_FREE.isFaceFree())
        assertFalse(FaceVerdict.HAS_FACE.isFaceFree())
        assertFalse(FaceVerdict.UNKNOWN.isFaceFree(), "unknown must be exclude-safe")
    }

    @Test
    fun noOpDetectorReportsUnknown() {
        assertEquals(FaceVerdict.UNKNOWN, NoOpFaceDetector.verdictFor(entry(5.0)))
    }

    @Test
    fun excludeFacesKeepsOnlyFaceFree() {
        val entries = listOf(entry(1.0), entry(2.0), entry(3.0))
        val detector = FakeFaceDetector(
            mapOf(
                entries[0].tNs to FaceVerdict.FACE_FREE,
                entries[1].tNs to FaceVerdict.HAS_FACE,
                // entries[2] unmapped → UNKNOWN.
            ),
        )
        val kept = excludeFaces(entries, detector)
        assertEquals(listOf(1.0), kept.map { it.odometerM })
    }

    // ── strip exclusion ────────────────────────────────────────────────

    @Test
    fun stripWithoutDetectorIsUnfiltered() {
        val entries = listOf(entry(18.0), entry(19.0), entry(20.0))
        assertEquals(3, approachStrip(entries, d = 20.0).size)
    }

    @Test
    fun stripWithDetectorExcludesFacesAndUnknowns() {
        val entries = listOf(entry(18.0), entry(19.0), entry(20.0))
        val detector = FakeFaceDetector(
            mapOf(
                entries[0].tNs to FaceVerdict.FACE_FREE,
                entries[1].tNs to FaceVerdict.HAS_FACE,
                // entries[2] unmapped → UNKNOWN → excluded-safe.
            ),
        )
        val strip = approachStrip(entries, d = 20.0, faceDetector = detector)
        assertEquals(listOf(18.0), strip.map { it.odometerM })
    }

    @Test
    fun stripWithCleanDetectorKeepsAllFrames() {
        val entries = listOf(entry(18.0), entry(19.0), entry(20.0))
        val detector = FakeFaceDetector(entries.associate { it.tNs to FaceVerdict.FACE_FREE })
        val strip = approachStrip(entries, d = 20.0, faceDetector = detector)
        assertEquals(listOf(18.0, 19.0, 20.0), strip.map { it.odometerM })
    }

    // ── disclosure ─────────────────────────────────────────────────────

    @Test
    fun consentCopyDisclosesTheFaceFilterAndKeepsTheBaseCopy() {
        val copy = consentCopy(ConsentInfo(photos = 3, clips = 1, samEnabled = true, faceFilterActive = true))
        assertTrue(copy.contains(FACE_FILTER_DISCLOSURE), "export preview must disclose the filter")
        assertTrue(copy.startsWith("Send 3 photos"), "W3-02 base copy stays byte-identical")
        assertTrue(copy.contains("Your route shape stays on this phone."))
    }

    @Test
    fun consentCopyOmitsTheFaceFilterWhenNoDetectorIsWired() {
        // Production passes no detector today: the copy must not promise a
        // pre-filter that is not active (audit W14-04).
        val copy = consentCopy(ConsentInfo(photos = 3, clips = 1, samEnabled = true))
        assertTrue(!copy.contains(FACE_FILTER_DISCLOSURE), "inactive filter must not be promised")
        assertTrue(copy.startsWith("Send 3 photos"), "W3-02 base copy stays byte-identical")
        assertTrue(copy.contains("Your route shape stays on this phone."))
    }
}
