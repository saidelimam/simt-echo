package app.simt.echo.capture

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plan W11-04 device evidence: the real bundled ML Kit model on a phone.
 * The host JVM cannot load it, so this is the only place the scanner's
 * contract with ML Kit is proven. Run with
 * `./gradlew :androidApp:connectedDebugAndroidTest` on a connected device
 * (a named manual gate, never pre-commit; docs/perf.md).
 *
 * Fixtures are ledger-sized JPEGs of a public-domain portrait; see
 * `assets/faces/README.md`.
 */
class MlKitFaceScannerDeviceTest {
    private val scanner = MlKitFaceScanner()

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("faces/$name").use { it.readBytes() }

    private fun entry(name: String) = GlimpseEntry(
        bucketIndex = 1L,
        tNs = 1L,
        odometerM = 0.5,
        score = 1.0,
        sharp = 1.0,
        hash = 1L,
        jpegPath = "faces/$name",
    )

    @Test
    fun aClearFaceIsDetected() {
        assertTrue(scanner.countFaces(fixture("face_large.jpg")) >= 1)
    }

    @Test
    fun aFaceAtSixPercentOfTheFrameIsStillDetected() {
        assertTrue(scanner.countFaces(fixture("face_small.jpg")) >= 1)
    }

    @Test
    fun aFrameWithoutFacesReportsNone() {
        assertEquals(0, scanner.countFaces(fixture("no_face.jpg")))
    }

    @Test
    fun garbageBytesThrowSoTheFrameIsNeverFaceFree() {
        try {
            scanner.countFaces(byteArrayOf(1, 2, 3, 4))
            fail("an unreadable jpeg must not report a face count")
        } catch (expected: Exception) {
            // Fail-closed contract: ScanningFaceDetector maps this to UNKNOWN.
        }
    }

    @Test
    fun scanningOnTheMainThreadFailsClosedInsteadOfBlockingIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = fixture("no_face.jpg")
        var verdict: FaceVerdict? = null
        val detector = ScanningFaceDetector(scanner) { bytes }
        instrumentation.runOnMainSync { verdict = detector.verdictFor(entry("no_face.jpg")) }
        assertEquals(FaceVerdict.UNKNOWN, verdict)
    }

    @Test
    fun theDetectorMapsRealScansToVerdicts() {
        val files = mapOf("face_large.jpg" to fixture("face_large.jpg"), "no_face.jpg" to fixture("no_face.jpg"))
        val detector = ScanningFaceDetector(scanner) { entry -> files[entry.jpegPath?.substringAfter('/')] }
        assertEquals(FaceVerdict.HAS_FACE, detector.verdictFor(entry("face_large.jpg")))
        assertEquals(FaceVerdict.FACE_FREE, detector.verdictFor(entry("no_face.jpg")))
        assertEquals(FaceVerdict.UNKNOWN, detector.verdictFor(entry("absent.jpg")))
    }

    /** Not an assertion: records the measurements W11-04 still owed (logcat tag EchoFaceScan). */
    @Test
    fun recordsMinimumFaceAndScanTime() {
        val names = listOf("face_large.jpg", "face_small.jpg", "face_tiny.jpg", "no_face.jpg")
        // Warm-up: the first scan pays model load.
        val warmStart = System.nanoTime()
        scanner.countFaces(fixture("face_large.jpg"))
        Log.i(TAG, "first scan (model load) ${(System.nanoTime() - warmStart) / 1_000_000} ms")
        for (name in names) {
            val bytes = fixture(name)
            val runs = 10
            var faces = -1
            val start = System.nanoTime()
            repeat(runs) { faces = scanner.countFaces(bytes) }
            val perScanMs = (System.nanoTime() - start) / runs / 1_000_000.0
            Log.i(TAG, "$name faces=$faces perScanMs=$perScanMs")
        }
    }

    private companion object {
        const val TAG = "EchoFaceScan"
    }
}
