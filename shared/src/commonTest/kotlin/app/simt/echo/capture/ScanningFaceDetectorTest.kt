package app.simt.echo.capture

import kotlin.test.Test
import kotlin.test.assertEquals

/** Plan W11-04: the scanner-to-verdict mapping is fail-closed and memoised. */
class ScanningFaceDetectorTest {

    private fun entry(path: String? = "glimpse/b1.jpg") = GlimpseEntry(
        bucketIndex = 1L,
        tNs = 1L,
        odometerM = 0.5,
        score = 1.0,
        sharp = 1.0,
        hash = 1L,
        jpegPath = path,
    )

    private class CountingScanner(private val answer: () -> Int) : FaceScanner {
        var calls = 0
        override fun countFaces(jpeg: ByteArray): Int {
            calls++
            return answer()
        }
    }

    private val bytes = byteArrayOf(1, 2, 3)

    private fun detector(scanner: FaceScanner, read: (GlimpseEntry) -> ByteArray? = { bytes }) =
        ScanningFaceDetector(scanner, read)

    @Test
    fun noFacesIsTheOnlyFaceFreeVerdict() {
        assertEquals(FaceVerdict.FACE_FREE, detector(CountingScanner { 0 }).verdictFor(entry()))
    }

    @Test
    fun anyFaceExcludesTheFrame() {
        assertEquals(FaceVerdict.HAS_FACE, detector(CountingScanner { 1 }).verdictFor(entry()))
        assertEquals(FaceVerdict.HAS_FACE, detector(CountingScanner { 5 }).verdictFor(entry()))
    }

    @Test
    fun aScannerThatCannotLookIsNeverFaceFree() {
        val failing = detector(CountingScanner { error("model not loaded") })
        assertEquals(FaceVerdict.UNKNOWN, failing.verdictFor(entry()))
        // A nonsense count is not "no faces" either.
        assertEquals(FaceVerdict.UNKNOWN, detector(CountingScanner { -1 }).verdictFor(entry()))
    }

    @Test
    fun missingBytesOrPathNeverReachTheScanner() {
        val scanner = CountingScanner { 0 }
        assertEquals(FaceVerdict.UNKNOWN, detector(scanner) { null }.verdictFor(entry()))
        assertEquals(FaceVerdict.UNKNOWN, detector(scanner).verdictFor(entry(path = null)))
        assertEquals(0, scanner.calls)
    }

    @Test
    fun theScannerReceivesTheFramesBytes() {
        var seen: ByteArray? = null
        val scanner = FaceScanner { jpeg ->
            seen = jpeg
            0
        }
        detector(scanner).verdictFor(entry())
        assertEquals(bytes.toList(), seen?.toList())
    }

    @Test
    fun aFrameIsScannedOnceAndKeepsItsVerdict() {
        var answer = 1
        val scanner = CountingScanner { answer }
        val d = detector(scanner)
        val first = d.verdictFor(entry())
        answer = 0
        assertEquals(first, d.verdictFor(entry()))
        assertEquals(FaceVerdict.HAS_FACE, d.verdictFor(entry()))
        assertEquals(1, scanner.calls)
    }

    @Test
    fun aFailedScanStaysExcludedOnRepeatQueries() {
        var healthy = false
        val scanner = CountingScanner { if (healthy) 0 else error("transient") }
        val d = detector(scanner)
        assertEquals(FaceVerdict.UNKNOWN, d.verdictFor(entry()))
        healthy = true
        // Same verdict for the same entry: callers key flags on it.
        assertEquals(FaceVerdict.UNKNOWN, d.verdictFor(entry()))
    }

    @Test
    fun framesAreCachedPerPath() {
        val scanner = CountingScanner { 0 }
        val d = detector(scanner)
        d.verdictFor(entry("glimpse/b1.jpg"))
        d.verdictFor(entry("glimpse/b2.jpg"))
        d.verdictFor(entry("glimpse/b1.jpg"))
        assertEquals(2, scanner.calls)
    }
}
