package app.simt.echo.capture

/**
 * On-device privacy pre-filter: face detection on strip frames (plan W11-04).
 *
 * Before anything is offered for upload, strip frames are scanned for faces;
 * frames with faces are **excluded** from clips and keyframes, and the
 * manifest carries per-frame `quality` / `faceFree` flags so the worker
 * drops scout boxes on bad frames before any SAM spend.
 *
 * Decision record (plan default, S may revisit): **exclude, not blur**.
 * Blurring keeps the frame (and its bytes) in the upload set and spends
 * on-device GPU/CPU to rewrite JPEGs; exclusion is fail-closed, needs no
 * pixel rewrite, and matches the ledger's existing "metadata-only entries
 * are excluded" discipline. Recorded here so a future blur slice knows
 * what it is overturning.
 *
 * Detector status: the seam is [FaceDetector]. [ScanningFaceDetector] is the
 * shippable implementation: it reads a ledger frame and asks a platform
 * [FaceScanner]. Android scans with ML Kit (`MlKitFaceScanner`, bundled
 * model, offline) and `AppGraph.faceDetectorFor` hands each Processing
 * screen one bound to its draft. A platform with no scanner (iOS until a
 * Vision scanner lands) passes `null`: no filtering, and the consent copy
 * stays silent about a filter that is not there. [NoOpFaceDetector]
 * reports [FaceVerdict.UNKNOWN] for every frame, which maps exclude-safe
 * ([FaceVerdict.isFaceFree] is false), so it would drop every frame and is
 * never wired into a filtering path.
 *
 * Owed:
 * 1. A Vision-backed [FaceScanner] for iOS.
 * 2. Per-frame scans of cut video clips (Media3 cut path has no frame seam).
 * 3. Device evidence on a frame with a printed face, and a measured
 *    minimum-face size and scan time (`MlKitFaceScanner` values are guesses).
 *
 * Pure and deterministic: no coroutines, no platform imports, no RNG.
 * [FaceDetector] implementations must return the same verdict for the
 * same entry on repeat calls — callers (e.g. [approachStrip] plus manifest
 * flag mapping) may query an entry more than once per upload.
 */

/** Face-scan outcome for one ledger frame. */
enum class FaceVerdict {
    /** Scanned, no face found — the frame may upload. */
    FACE_FREE,

    /** Scanned, a face was found — the frame is excluded. */
    HAS_FACE,

    /**
     * Not scanned, or the scan failed. Exclude-safe: maps to "not
     * face-free", so unknown frames are dropped exactly like faces.
     */
    UNKNOWN,
}

/** True only for [FaceVerdict.FACE_FREE]; UNKNOWN is exclude-safe. */
fun FaceVerdict.isFaceFree(): Boolean = this == FaceVerdict.FACE_FREE

/**
 * Face-detection seam. The verdict is keyed on the ledger entry; platform
 * implementations resolve the JPEG bytes via [GlimpseEntry.jpegPath]
 * (draft-relative, see [LedgerStore]) and scan them (see [ScanningFaceDetector]).
 */
interface FaceDetector {
    fun verdictFor(entry: GlimpseEntry): FaceVerdict
}

/**
 * No detector available: every frame reports [FaceVerdict.UNKNOWN], which
 * is exclude-safe. Never wire this into a filtering path as if it were a
 * real scan — it would drop every frame. A real scan is
 * [ScanningFaceDetector]; a platform without one passes `null`.
 */
object NoOpFaceDetector : FaceDetector {
    override fun verdictFor(entry: GlimpseEntry): FaceVerdict = FaceVerdict.UNKNOWN
}

/**
 * Excludes every entry the [detector] does not clear as
 * [FaceVerdict.FACE_FREE]: faces and UNKNOWNs alike are dropped, so a
 * failed scan can never leak a face into the upload set.
 */
fun excludeFaces(entries: List<GlimpseEntry>, detector: FaceDetector): List<GlimpseEntry> =
    entries.filter { detector.verdictFor(it).isFaceFree() }
