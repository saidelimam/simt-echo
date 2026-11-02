package app.simt.echo

import app.simt.echo.capture.FaceScanner
import app.simt.echo.capture.FaceVerdict
import app.simt.echo.capture.GlimpseEntry
import app.simt.echo.platform.AndroidPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * W11-04 binding: [AppGraph.faceDetectorFor] reads the draft's own ledger
 * frames from disk and hands exactly those bytes to the platform scanner;
 * with no scanner there is no filter at all.
 */
class AppGraphFaceDetectorTest {

    private fun entry(path: String) = GlimpseEntry(
        bucketIndex = 1L,
        tNs = 1L,
        odometerM = 0.5,
        score = 1.0,
        sharp = 1.0,
        hash = 1L,
        jpegPath = path,
    )

    @Test
    fun noScannerMeansNoFilter() {
        val dir = Files.createTempDirectory("echo-face").toFile()
        try {
            val graph = AppGraph(platform = AndroidPlatform(dataDir = dir.absolutePath))
            assertNull(graph.faceDetectorFor("draft-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theDetectorScansTheDraftsLedgerBytes() {
        val dir = Files.createTempDirectory("echo-face").toFile()
        try {
            val seen = mutableListOf<List<Byte>>()
            val scanner = FaceScanner { jpeg ->
                seen += jpeg.toList()
                if (jpeg.first() == 1.toByte()) 1 else 0
            }
            val graph = AppGraph(platform = AndroidPlatform(dataDir = dir.absolutePath), faceScanner = scanner)
            graph.draftRepository.writeDraftFile("draft-1", "glimpse/face.jpg", byteArrayOf(1, 9))
            graph.draftRepository.writeDraftFile("draft-1", "glimpse/clear.jpg", byteArrayOf(2, 9))

            val detector = checkNotNull(graph.faceDetectorFor("draft-1"))

            assertEquals(FaceVerdict.HAS_FACE, detector.verdictFor(entry("glimpse/face.jpg")))
            assertEquals(FaceVerdict.FACE_FREE, detector.verdictFor(entry("glimpse/clear.jpg")))
            assertEquals(listOf(listOf<Byte>(1, 9), listOf<Byte>(2, 9)), seen)
            // A frame this draft never wrote is unreadable, so excluded.
            assertEquals(FaceVerdict.UNKNOWN, detector.verdictFor(entry("glimpse/missing.jpg")))
            // Another draft's frames are not reachable through this detector.
            assertEquals(FaceVerdict.UNKNOWN, checkNotNull(graph.faceDetectorFor("draft-2")).verdictFor(entry("glimpse/face.jpg")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
